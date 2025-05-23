package io.zabbix4j.api.async;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zabbix4j.api.dto.ClusterNode;
import io.zabbix4j.api.dto.ItemValue;
import io.zabbix4j.api.dto.Node;
import io.zabbix4j.api.dto.TrapperResponse;
import io.zabbix4j.api.exception.CommunicationException;
import io.zabbix4j.api.exception.ProcessingException;
import io.zabbix4j.api.protocol.ZabbixProtocol;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.AsynchronousChannelGroup;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.io.ByteArrayOutputStream;


/**
 * Asynchronous client for sending data to Zabbix server/proxy using the Zabbix Sender protocol
 * with non-blocking I/O.
 * <p>
 * Use the {@link Builder} to construct instances of this class.
 * </p>
 * Example:
 * <pre>{@code
 * // Assuming an AsynchronousChannelGroup is managed elsewhere or use null for default
 * ZabbixSenderAsync sender = new ZabbixSenderAsync.Builder()
 * .server("zabbix.example.com", 10051)
 * .timeout(5) // seconds
 * .build();
 *
 * List<ItemValue> items = List.of(
 * ItemValue.builder().host("Host1").key("item.key1").value("123").build()
 * );
 *
 * sender.send(items)
 * .thenAccept(response -> System.out.println("Items processed: " + response.getProcessed()))
 * .exceptionally(ex -> {
 * System.err.println("Failed to send data: " + ex.getMessage());
 * return null;
 * }).join(); // Wait for completion in example
 *
 * // Remember to shut down the channel group if you created and manage one.
 * }</pre>
 *
 * @author ShortRoundDev
 */
public final class ZabbixSenderAsync implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ZabbixSenderAsync.class);

    private final List<ClusterNode> clusters;
    private final int timeoutMillis;
    private final SocketAddress sourceIpAddress; // InetSocketAddress for local bind
    private final int chunkSize;
    private final boolean useCompression;
    private final ObjectMapper objectMapper;
    private final AsynchronousChannelGroup channelGroup;
    private final boolean managedChannelGroup; // If this class created it.

    // Dedicated executor for CPU-bound tasks like JSON processing, if needed,
    // to avoid stealing threads from the AsynchronousChannelGroup's pool.
    // For now, CompletableFuture's default async methods will use ForkJoinPool.commonPool().
    // private final ExecutorService processingExecutor;


    private ZabbixSenderAsync(Builder builder) {
        if (builder.clusters.isEmpty()) {
            throw new IllegalArgumentException("At least one server or cluster must be configured.");
        }
        this.clusters = Collections.unmodifiableList(new ArrayList<>(builder.clusters));
        this.timeoutMillis = builder.timeoutSeconds > 0 ? builder.timeoutSeconds * 1000 : 0;
        this.sourceIpAddress = builder.sourceIpAddress;
        this.chunkSize = builder.chunkSize > 0 ? builder.chunkSize : 250;
        this.useCompression = builder.useCompression;
        this.objectMapper = new ObjectMapper();

        if (builder.channelGroup != null) {
            this.channelGroup = builder.channelGroup;
            this.managedChannelGroup = false;
        } else {
            try {
                // Using a fixed thread pool for the group for simplicity.
                // Adjust size based on expected concurrency.
                // For a library, it might be better to use a shared/default group or allow more configuration.
                ExecutorService groupExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
                this.channelGroup = AsynchronousChannelGroup.withThreadPool(groupExecutor);
                this.managedChannelGroup = true;
            } catch (IOException e) {
                throw new ProcessingException("Failed to create default AsynchronousChannelGroup", e);
            }
        }
        // this.processingExecutor = Executors.newFixedThreadPool(Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
    }

    /**
     * Asynchronously sends a list of item values to the configured Zabbix servers/proxies.
     * The items are split into chunks and sent sequentially (chunks are sequential,
     * attempts to clusters for a given chunk can be parallel).
     *
     * @param items The list of {@link ItemValue}s to send.
     * @return A {@link CompletableFuture} resolving to an aggregated {@link TrapperResponse}
     *         summarizing the results from all chunks and successful cluster communications.
     * @throws IllegalArgumentException if items is null or empty.
     */
    public CompletableFuture<TrapperResponse> send(List<ItemValue> items) {
        if (items == null || items.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Items list cannot be null or empty."));
        }
        for (ItemValue item : items) {
            Objects.requireNonNull(item, "ItemValue in list cannot be null.");
        }

        List<List<ItemValue>> itemChunks = new ArrayList<>();
        for (int i = 0; i < items.size(); i += chunkSize) {
            itemChunks.add(items.subList(i, Math.min(items.size(), i + chunkSize)));
        }

        logger.info("Asynchronously sending {} item(s) in {} chunk(s) of size up to {}. Target clusters: {}",
                items.size(), itemChunks.size(), chunkSize, this.clusters.size());

        TrapperResponse initialResponse = new TrapperResponse(0, 0, 0, 0.0, null);
        CompletableFuture<TrapperResponse> overallFuture = CompletableFuture.completedFuture(initialResponse);

        for (int i = 0; i < itemChunks.size(); i++) {
            List<ItemValue> chunk = itemChunks.get(i);
            int chunkNumber = i + 1;
            logger.debug("Processing chunk {} of {} asynchronously. Items in chunk: {}", chunkNumber, itemChunks.size(), chunk.size());

            final List<ItemValue> currentChunk = Collections.unmodifiableList(new ArrayList<>(chunk)); // Make effectively final for lambda

            overallFuture = overallFuture.thenComposeAsync(aggregatedResponse ->
                    sendChunkToAllClustersAsync(currentChunk, chunkNumber)
                            .thenApply(chunkClusterResponses -> {
                                TrapperResponse currentAggregated = aggregatedResponse;
                                for (Optional<Pair<Node, TrapperResponse>> optRespPair : chunkClusterResponses) {
                                    optRespPair.ifPresent(respPair -> {
                                        // Log individual successful node responses for this chunk
                                        logger.info("Chunk {} successfully processed by node {}: P:{}, F:{}, T:{}",
                                                chunkNumber, respPair.getKey(), respPair.getValue().getProcessed(),
                                                respPair.getValue().getFailed(), respPair.getValue().getTotal());
                                    });
                                }
                                // Aggregate responses from this chunk into the overall aggregatedResponse
                                // The Python code aggregates all successful responses.
                                for(Optional<Pair<Node, TrapperResponse>> respOpt : chunkClusterResponses){
                                    if(respOpt.isPresent()){
                                        currentAggregated = currentAggregated.add(respOpt.get().getValue());
                                    }
                                }
                                logger.info("Chunk {} processed. Current overall aggregated: P:{}, F:{}, T:{}",
                                        chunkNumber, currentAggregated.getProcessed(), currentAggregated.getFailed(), currentAggregated.getTotal());
                                return currentAggregated;
                            })
            );
        }
        
        // Final check: if no items were successfully processed after all chunks, and items were provided.
        // This is a bit tricky because `sendChunkToAllClustersAsync` might return empty list if all clusters fail for a chunk.
        // The aggregatedResponse might still be initial if all chunks failed for all clusters.
        return overallFuture.thenApply(finalResponse -> {
             if (finalResponse.getTotal() == 0 && !items.isEmpty()) {
                // This check might need to be more robust, e.g. by tracking if any cluster succeeded for any chunk.
                // For now, if total items processed is 0, it indicates a failure if there were items to send.
                // However, sendChunkToAllClustersAsync is designed to not throw if one cluster fails, but rather return empty list of responses.
                // Let's assume if finalResponse.getTotal() is 0, it means nothing was confirmed processed.
                boolean anySuccess = finalResponse.getProcessed() > 0 || finalResponse.getFailed() > 0; // A bit loose, but implies some communication
                if(!anySuccess && !items.isEmpty()){
                     logger.warn("No items were confirmed processed or failed by any Zabbix cluster. This might indicate a total communication failure for all chunks.");
                     // Depending on strictness, could throw here. For now, return the zeroed response.
                }
            }
            logger.info("Finished sending all chunks asynchronously. Final aggregated response: Processed: {}, Failed: {}, Total: {}, Time Spent: {}s",
                finalResponse.getProcessed(), finalResponse.getFailed(), finalResponse.getTotal(), finalResponse.getTimeSpentSeconds());
            return finalResponse;
        });
    }


    /**
     * Sends a single chunk of items asynchronously to ALL configured clusters.
     * Collects responses from each cluster that successfully processes the chunk.
     *
     * @param chunkItems  The list of items in the current chunk.
     * @param chunkNumber The number of the current chunk (for logging).
     * @return A {@link CompletableFuture} resolving to a list of {@code Optional<Pair<Node, TrapperResponse>>}.
     *         Each element corresponds to a cluster. Optional is empty if the cluster failed,
     *         otherwise it contains the successful Node and its TrapperResponse.
     */
    private CompletableFuture<List<Optional<Pair<Node, TrapperResponse>>>> sendChunkToAllClustersAsync(
            List<ItemValue> chunkItems, int chunkNumber) {

        List<CompletableFuture<Optional<Pair<Node, TrapperResponse>>>> allClusterFutures = new ArrayList<>();

        for (ClusterNode cluster : this.clusters) {
            // For each cluster, attempt to send the chunk.
            // sendChunkToSingleClusterAsync will try nodes within that cluster.
            CompletableFuture<Optional<Pair<Node, TrapperResponse>>> clusterAttemptFuture =
                    sendChunkToSingleClusterAsync(cluster, chunkItems, chunkNumber)
                            .exceptionally(ex -> { // Handle failure for a single cluster
                                logger.warn("Chunk {} completely failed for cluster '{}': {}. This cluster's response will be empty.",
                                        chunkNumber, cluster, ex.getMessage());
                                return Optional.empty(); // Represent cluster failure as empty Optional
                            });
            allClusterFutures.add(clusterAttemptFuture);
        }

        // Wait for all cluster attempts to complete
        return CompletableFuture.allOf(allClusterFutures.toArray(new CompletableFuture[0]))
                .thenApply(v -> allClusterFutures.stream()
                        .map(CompletableFuture::join) // Join is safe here after allOf
                        .collect(Collectors.toList()));
    }


    /**
     * Sends a single chunk of items asynchronously to one of the nodes within a single cluster, attempting failover.
     *
     * @param cluster     The {@link ClusterNode} configuration for the current cluster.
     * @param chunkItems  The list of items in the current chunk.
     * @param chunkNumber The number of the current chunk (for logging).
     * @return A {@link CompletableFuture} resolving to an {@code Optional<Pair<Node, TrapperResponse>>}.
     *         The Optional is empty if all nodes in this cluster failed.
     *         Otherwise, it contains the successfully contacted {@link Node} and its {@link TrapperResponse}.
     */
    private CompletableFuture<Optional<Pair<Node, TrapperResponse>>> sendChunkToSingleClusterAsync(
            ClusterNode cluster, List<ItemValue> chunkItems, int chunkNumber) {

        Map<String, Object> payloadMap = new HashMap<>();
        payloadMap.put("request", "sender data");
        payloadMap.put("data", chunkItems);
        // payloadMap.put("clock", System.currentTimeMillis() / 1000L); // Optional

        String jsonDataPayload;
        try {
            jsonDataPayload = objectMapper.writeValueAsString(payloadMap);
        } catch (JsonProcessingException e) {
            return CompletableFuture.failedFuture(
                    new ProcessingException("Failed to serialize chunk " + chunkNumber + " to JSON for cluster '" + cluster + "': " + e.getMessage(), e)
            );
        }

        byte[] packetBytes = ZabbixProtocol.createPacket(jsonDataPayload, this.useCompression);
        ByteBuffer packetBuffer = ByteBuffer.wrap(packetBytes);

        // Try nodes recursively/iteratively. Start with a copy of the node list.
        List<Node> nodesToTry = new ArrayList<>(cluster.getNodes());
        return trySendToNodeRecursiveAsync(packetBuffer.asReadOnlyBuffer(), nodesToTry, cluster.toString(), chunkNumber, new ArrayList<>());
    }

    /**
     * Recursive helper to try sending a packet to a list of nodes one by one until success or list exhaustion.
     *
     * @param packetBufferReadOnly A read-only ByteBuffer of the packet to send.
     * @param remainingNodes       The list of nodes yet to try.
     * @param clusterNameForLog    Cluster name for logging.
     * @param chunkNumberForLog    Chunk number for logging.
     * @param accumulatedErrors    List to accumulate errors from failed nodes in this cluster.
     * @return CompletableFuture resolving to Optional of successful (Node, TrapperResponse) pair, or empty if all fail.
     */
    private CompletableFuture<Optional<Pair<Node, TrapperResponse>>> trySendToNodeRecursiveAsync(
            ByteBuffer packetBufferReadOnly, List<Node> remainingNodes, String clusterNameForLog, int chunkNumberForLog, List<Throwable> accumulatedErrors) {

        if (remainingNodes.isEmpty()) {
            if (accumulatedErrors.isEmpty()) {
                 return CompletableFuture.completedFuture(Optional.empty()); // No nodes provided or all processed somehow without error
            }
            // All nodes in the cluster failed.
            String combinedErrors = accumulatedErrors.stream().map(Throwable::getMessage).collect(Collectors.joining("; "));
            ProcessingException overallClusterFailure = new ProcessingException(
                    String.format("Chunk %d: All nodes in cluster '%s' failed. Errors: [%s]", chunkNumberForLog, clusterNameForLog, combinedErrors)
            );
            // This exception is for this specific cluster. It will be caught by sendChunkToAllClustersAsync's exceptionally block.
            return CompletableFuture.failedFuture(overallClusterFailure);
        }

        Node currentNode = remainingNodes.get(0);
        List<Node> nextNodesToTry = remainingNodes.subList(1, remainingNodes.size());
        ByteBuffer packetForThisAttempt = packetBufferReadOnly.duplicate(); // Each attempt needs its own buffer state

        CompletableFuture<Optional<Pair<Node, TrapperResponse>>> future = new CompletableFuture<>();

        try {
            AsynchronousSocketChannel channel = AsynchronousSocketChannel.open(this.channelGroup);
            if (this.sourceIpAddress != null) {
                channel.bind(this.sourceIpAddress);
            }
            // Set options if needed, e.g., channel.setOption(StandardSocketOptions.SO_KEEPALIVE, true);

            SocketAddress serverAddress = new InetSocketAddress(currentNode.getAddress(), currentNode.getPort());
            logger.debug("Chunk {}: Attempting connection to node {} in cluster '{}'", chunkNumberForLog, currentNode, clusterNameForLog);

            channel.connect(serverAddress, channel, new CompletionHandler<Void, AsynchronousSocketChannel>() {
                @Override
                public void completed(Void result, AsynchronousSocketChannel connectedChannel) {
                    logger.debug("Chunk {}: Connected to node {} in cluster '{}'. Sending packet.", chunkNumberForLog, currentNode, clusterNameForLog);
                    sendAndReceiveInternalAsync(connectedChannel, packetForThisAttempt, currentNode, chunkNumberForLog)
                            .thenApply(trapperResponse -> {
                                // Success for this node
                                // TODO: Implement failover reordering if needed for a mutable shared list of nodes.
                                // For now, this success is self-contained for this call.
                                return Optional.of(Pair.of(currentNode, trapperResponse));
                            })
                            .whenComplete((optionalResult, ex) -> {
                                if (ex != null) {
                                    // Failure with this node (send/receive failed)
                                    logger.warn("Chunk {}: Communication failed with node {} in cluster '{}': {}",
                                            chunkNumberForLog, currentNode, clusterNameForLog, ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage());
                                    accumulatedErrors.add(ex.getCause() != null ? ex.getCause() : ex);
                                    closeChannelQuietly(connectedChannel); // Ensure channel closed on error
                                    // Try next node
                                    trySendToNodeRecursiveAsync(packetBufferReadOnly, nextNodesToTry, clusterNameForLog, chunkNumberForLog, accumulatedErrors)
                                            .whenComplete((nextAttemptResult, nextAttemptEx) -> {
                                                if (nextAttemptEx != null) {
                                                    future.completeExceptionally(nextAttemptEx);
                                                } else {
                                                    future.complete(nextAttemptResult);
                                                }
                                            });
                                } else {
                                    // Success for this node, no need to try next nodes for this cluster
                                    future.complete(optionalResult);
                                    // Channel should be closed by sendAndReceiveInternalAsync on success path.
                                }
                            });
                }

                @Override
                public void failed(Throwable exc, AsynchronousSocketChannel attachmentChannel) {
                    logger.warn("Chunk {}: Failed to connect to node {} in cluster '{}': {}",
                            chunkNumberForLog, currentNode, clusterNameForLog, exc.getMessage());
                    accumulatedErrors.add(exc);
                    closeChannelQuietly(attachmentChannel); // Ensure channel closed on connect failure
                    // Try next node
                    trySendToNodeRecursiveAsync(packetBufferReadOnly, nextNodesToTry, clusterNameForLog, chunkNumberForLog, accumulatedErrors)
                            .whenComplete((nextAttemptResult, nextAttemptEx) -> {
                                if (nextAttemptEx != null) {
                                    future.completeExceptionally(nextAttemptEx);
                                } else {
                                    future.complete(nextAttemptResult);
                                }
                            });
                }
            });

        } catch (IOException e) { // From AsynchronousSocketChannel.open() or .bind()
            logger.error("Chunk {}: IOException setting up channel for node {} in cluster '{}': {}",
                    chunkNumberForLog, currentNode, clusterNameForLog, e.getMessage());
            accumulatedErrors.add(e);
            // Try next node (synchronous decision to recurse, but returns a future)
            return trySendToNodeRecursiveAsync(packetBufferReadOnly, nextNodesToTry, clusterNameForLog, chunkNumberForLog, accumulatedErrors);
        }
        return future;
    }


    /**
     * Handles sending the packet and receiving/parsing the response on an established channel.
     */
    private CompletableFuture<TrapperResponse> sendAndReceiveInternalAsync(
            AsynchronousSocketChannel channel, ByteBuffer packetBuffer, Node node, int chunkNumber) {

        CompletableFuture<TrapperResponse> overallCommunicationFuture = new CompletableFuture<>();

        // 1. Write Request
        channel.write(packetBuffer, this.timeoutMillis, TimeUnit.MILLISECONDS, null,
                new CompletionHandler<Integer, Void>() {
                    @Override
                    public void completed(Integer bytesWritten, Void attachment) {
                        if (bytesWritten < packetBuffer.limit() && packetBuffer.hasRemaining()) {
                            // Partial write, need to re-issue write for remaining.
                            // For simplicity, assuming full write or failure for now.
                            // A robust implementation would loop channel.write for remaining bytes.
                            logger.warn("Chunk {}: Partial write to node {}. Expected {}, wrote {}. Re-issuing write.",
                                    chunkNumber, node, packetBuffer.limit(), bytesWritten);
                            // Recursive call to write for remaining: channel.write(packetBuffer, timeout, attachment, this);
                            // For now, if not all written, consider it an error.
                            if(packetBuffer.hasRemaining()){
                                failed(new IOException("Partial write, remaining bytes: " + packetBuffer.remaining()), null);
                                return;
                            }
                        }
                        logger.debug("Chunk {}: Successfully wrote {} bytes to node {}.", chunkNumber, bytesWritten, node);

                        // 2. Read Response Header
                        ByteBuffer headerReadBuffer = ByteBuffer.allocate(ZabbixProtocol.HEADER_SIZE);
                        channel.read(headerReadBuffer, timeoutMillis, TimeUnit.MILLISECONDS, null,
                                new CompletionHandler<Integer, Void>() {
                                    @Override
                                    public void completed(Integer headerBytesRead, Void attachment) {
                                        if (headerBytesRead < ZabbixProtocol.HEADER_SIZE) {
                                            // Handle partial header read, similar to write. Loop read.
                                            failed(new IOException("Partial header read: " + headerBytesRead + " of " + ZabbixProtocol.HEADER_SIZE), null);
                                            return;
                                        }
                                        headerReadBuffer.flip();
                                        logger.debug("Chunk {}: Read {} header bytes from node {}.", chunkNumber, headerBytesRead, node);

                                        // Parse header
                                        headerReadBuffer.order(ByteOrder.LITTLE_ENDIAN);
                                        byte[] signature = new byte[ZabbixProtocol.ZABBIX_HEADER_SIGNATURE.length];
                                        headerReadBuffer.get(signature);
                                        if (!java.util.Arrays.equals(signature, ZabbixProtocol.ZABBIX_HEADER_SIGNATURE)) {
                                            failed(new ProcessingException("Invalid Zabbix header signature from node " + node), null);
                                            return;
                                        }
                                        byte flags = headerReadBuffer.get();
                                        int dataLength = headerReadBuffer.getInt();
                                        int reservedLength = headerReadBuffer.getInt(); // Uncompressed length if compressed

                                        if ((flags & ZabbixProtocol.FLAG_ZABBIX_PROTOCOL) == 0) {
                                            failed(new ProcessingException("Packet from node " + node + " does not conform to Zabbix protocol."), null);
                                            return;
                                        }
                                        if ((flags & ZabbixProtocol.FLAG_LARGE_PACKET) != 0) {
                                            failed(new ProcessingException("Large packet flag not supported from node " + node), null);
                                            return;
                                        }
                                        if (dataLength < 0 || dataLength > (128 * 1024 * 1024)) { // Sanity limit
                                            failed(new ProcessingException("Invalid data length " + dataLength + " from node " + node), null);
                                            return;
                                        }

                                        // 3. Read Response Body
                                        ByteBuffer bodyReadBuffer = ByteBuffer.allocate(dataLength);
                                        channel.read(bodyReadBuffer, timeoutMillis, TimeUnit.MILLISECONDS, null,
                                                new CompletionHandler<Integer, Void>() {
                                                    @Override
                                                    public void completed(Integer bodyBytesRead, Void attachment) {
                                                        if (bodyBytesRead < dataLength) {
                                                            failed(new IOException("Partial body read: " + bodyBytesRead + " of " + dataLength), null);
                                                            return;
                                                        }
                                                        bodyReadBuffer.flip();
                                                        logger.debug("Chunk {}: Read {} body bytes from node {}.", chunkNumber, bodyBytesRead, node);

                                                        byte[] payloadBytes = new byte[dataLength];
                                                        bodyReadBuffer.get(payloadBytes);

                                                        try {
                                                            byte[] finalPayloadBytes;
                                                            if ((flags & ZabbixProtocol.FLAG_COMPRESSION) != 0) {
                                                                finalPayloadBytes = decompressPayload(payloadBytes, reservedLength, node);
                                                            } else {
                                                                finalPayloadBytes = payloadBytes;
                                                            }

                                                            String responseJson = new String(finalPayloadBytes, StandardCharsets.UTF_8);
                                                            logger.debug("Chunk {}: Parsed response from node {}: {}", chunkNumber, node, responseJson.substring(0, Math.min(responseJson.length(), 200)));

                                                            com.fasterxml.jackson.databind.JsonNode responseNode = objectMapper.readTree(responseJson);
                                                            if (!responseNode.has("response") || !"success".equalsIgnoreCase(responseNode.get("response").asText())) {
                                                                String errorMsg = "Zabbix sender protocol response indicates failure from node " + node;
                                                                if (responseNode.has("info")) { errorMsg += ": " + responseNode.get("info").asText(); }
                                                                throw new ProcessingException(errorMsg);
                                                            }
                                                            if (!responseNode.has("info")) {
                                                                throw new ProcessingException("Zabbix sender protocol response from node " + node + " missing 'info' field.");
                                                            }
                                                            TrapperResponse trapperResponse = TrapperResponse.parseInfoString(responseNode.get("info").asText(), chunkNumber);
                                                            overallCommunicationFuture.complete(trapperResponse);

                                                        } catch (ProcessingException | IOException e) { // IOException from readTree or decompress
                                                            failed(e, null); // Completes overallCommunicationFuture exceptionally
                                                        } finally {
                                                            closeChannelQuietly(channel);
                                                        }
                                                    }

                                                    @Override
                                                    public void failed(Throwable exc, Void attachment) {
                                                        logger.warn("Chunk {}: Failed to read body from node {}: {}", chunkNumber, node, exc.getMessage());
                                                        overallCommunicationFuture.completeExceptionally(new CommunicationException("Failed to read response body from " + node, exc));
                                                        closeChannelQuietly(channel);
                                                    }
                                                });
                                    }
                                    @Override
                                    public void failed(Throwable exc, Void attachment) {
                                        logger.warn("Chunk {}: Failed to read header from node {}: {}", chunkNumber, node, exc.getMessage());
                                        overallCommunicationFuture.completeExceptionally(new CommunicationException("Failed to read response header from " + node, exc));
                                        closeChannelQuietly(channel);
                                    }
                                });
                    }

                    @Override
                    public void failed(Throwable exc, Void attachment) {
                        logger.warn("Chunk {}: Failed to write packet to node {}: {}", chunkNumber, node, exc.getMessage());
                        overallCommunicationFuture.completeExceptionally(new CommunicationException("Failed to write request to " + node, exc));
                        closeChannelQuietly(channel);
                    }
                });
        return overallCommunicationFuture;
    }

    private byte[] decompressPayload(byte[] compressedData, int uncompressedSize, Node node) throws ProcessingException {
        Inflater inflater = new Inflater();
        inflater.setInput(compressedData);
        ByteArrayOutputStream baos = new ByteArrayOutputStream(uncompressedSize > 0 ? uncompressedSize : compressedData.length * 2);
        byte[] buffer = new byte[1024];
        try {
            while (!inflater.finished()) {
                if (inflater.needsInput()) {
                    throw new ProcessingException("Inflater needs input unexpectedly during Zabbix payload decompression from node " + node);
                }
                int count = inflater.inflate(buffer);
                if (count == 0 && inflater.needsDictionary()) {
                    throw new ProcessingException("Zabbix payload decompression failed from node " + node + ": needs dictionary.");
                }
                if (count == 0 && inflater.finished()) break;
                if (count == 0) throw new ProcessingException("Zabbix payload decompression stalled or error from node " + node);
                baos.write(buffer, 0, count);
            }
        } catch (DataFormatException e) {
            throw new ProcessingException("Failed to decompress Zabbix payload from node " + node + ": " + e.getMessage(), e);
        } finally {
            inflater.end();
        }
        byte[] decompressed = baos.toByteArray();
        if (uncompressedSize > 0 && decompressed.length != uncompressedSize) {
            logger.warn("Decompressed payload size ({}) from node {} does not match expected uncompressed size ({}).",
                    decompressed.length, node, uncompressedSize);
        }
        return decompressed;
    }


    private void closeChannelQuietly(AsynchronousSocketChannel channel) {
        if (channel != null && channel.isOpen()) {
            try {
                channel.close();
            } catch (IOException e) {
                logger.warn("IOException while closing AsynchronousSocketChannel: {}", e.getMessage());
            }
        }
    }

    /**
     * Convenience method to asynchronously send a single item value.
     *
     * @param host  The hostname of the monitored host.
     * @param key   The item key.
     * @param value The item value.
     * @param clock Optional timestamp for the value (seconds since Unix epoch). Can be null.
     * @param ns    Optional nanoseconds part of the timestamp. Can be null.
     * @return A {@link CompletableFuture} resolving to an aggregated {@link TrapperResponse}.
     */
    public CompletableFuture<TrapperResponse> sendValue(String host, String key, String value, Long clock, Integer ns) {
        ItemValue.Builder itemBuilder = ItemValue.builder().host(host).key(key).value(value);
        if (clock != null) {
            itemBuilder.clock(clock);
        }
        if (ns != null) {
            itemBuilder.ns(ns);
        }
        return send(Collections.singletonList(itemBuilder.build()));
    }


    @Override
    public void close() throws IOException {
        if (this.channelGroup != null && this.managedChannelGroup) {
            try {
                logger.info("Shutting down managed AsynchronousChannelGroup for ZabbixSenderAsync.");
                this.channelGroup.shutdown();
                if (!this.channelGroup.awaitTermination(5, TimeUnit.SECONDS)) {
                    this.channelGroup.shutdownNow();
                }
            } catch (InterruptedException e) {
                this.channelGroup.shutdownNow();
                Thread.currentThread().interrupt();
            }
            // if (this.processingExecutor != null) {
            //    this.processingExecutor.shutdown();
            // }
            logger.info("Managed AsynchronousChannelGroup ZabbixSenderAsync closed.");
        }
    }


    /**
     * Builder for {@link ZabbixSenderAsync}.
     */
    public static class Builder {
        private List<ClusterNode> clusters = new ArrayList<>();
        private int timeoutSeconds = 5;
        private SocketAddress sourceIpAddress; // InetSocketAddress
        private int chunkSize = 250;
        private boolean useCompression = false;
        private AsynchronousChannelGroup channelGroup;

        public Builder server(String host, int port) {
            this.clusters.add(new ClusterNode(Collections.singletonList(new Node(host, port)), true));
            return this;
        }

        public Builder cluster(ClusterNode clusterNode) {
            Objects.requireNonNull(clusterNode, "ClusterNode cannot be null.");
            this.clusters.add(clusterNode);
            return this;
        }

        public Builder clusters(List<ClusterNode> clusters) {
            Objects.requireNonNull(clusters, "Clusters list cannot be null.");
            this.clusters = new ArrayList<>(clusters);
            return this;
        }

        public Builder timeout(int seconds) {
            this.timeoutSeconds = seconds;
            return this;
        }

        public Builder sourceIp(String ip) {
            try {
                // Note: InetSocketAddress constructor doesn't resolve, it just stores.
                // Resolution happens at connect. For bind, it should be a local address.
                // For simplicity, storing as InetSocketAddress without resolving host, assuming IP.
                // If hostname needs resolving for bind, it's more complex.
                // Typically, for bind, one provides local IP string, not hostname.
                this.sourceIpAddress = new InetSocketAddress(ip, 0); // 0 for ephemeral port
            } catch (Exception e) { // Catches SecurityException or IllegalArgumentException from InetSocketAddress
                throw new IllegalArgumentException("Invalid source IP address string: " + ip, e);
            }
            return this;
        }
         public Builder sourceIp(InetSocketAddress sourceAddress) {
            this.sourceIpAddress = sourceAddress;
            return this;
        }


        public Builder chunkSize(int chunkSize) {
            this.chunkSize = chunkSize;
            return this;
        }

        public Builder useCompression(boolean useCompression) {
            this.useCompression = useCompression;
            return this;
        }

        public Builder channelGroup(AsynchronousChannelGroup channelGroup) {
            this.channelGroup = channelGroup;
            return this;
        }

        public ZabbixSenderAsync build() {
            if (clusters.isEmpty()) {
                throw new IllegalArgumentException("At least one Zabbix server or cluster must be configured.");
            }
            return new ZabbixSenderAsync(this);
        }
    }
}
