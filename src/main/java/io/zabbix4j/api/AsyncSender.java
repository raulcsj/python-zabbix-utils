package io.zabbix4j.api;

import io.zabbix4j.api.exception.ZabbixProcessingException;
import io.zabbix4j.api.protocol.ZabbixProtocol;
import io.zabbix4j.api.types.Cluster;
import io.zabbix4j.api.types.ItemValue;
import io.zabbix4j.api.types.Node;
import io.zabbix4j.api.types.TrapperResponse;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Sends data asynchronously to Zabbix Server or Zabbix Proxy using the Zabbix Sender protocol
 * with Java NIO's AsynchronousSocketChannel.
 * This class supports sending data in chunks and using Zabbix agent configuration files
 * for server/proxy details.
 * <p>
 * Use the {@link Builder} to construct instances of this class.
 * SSL/TLS is not directly handled by this raw NIO implementation; `tlsConfig` is stored if parsed
 * but its application would require a custom SSL/TLS layer (e.g., using SSLEngine).
 *
 * @author CSJ
 */
public final class AsyncSender {

    private static final Logger logger = LoggerFactory.getLogger(AsyncSender.class);

    /**
     * Default Zabbix Sender port (same as Trapper port).
     */
    public static final int DEFAULT_ZABBIX_SENDER_PORT = 10051;
    /**
     * Default timeout for socket operations in milliseconds.
     */
    public static final int DEFAULT_TIMEOUT_MS = 10000;
    /**
     * Default chunk size for sending items.
     */
    public static final int DEFAULT_CHUNK_SIZE = 250;

    private final List<Cluster> clusters;
    private final int timeout;
    private final String sourceIp;
    private final int chunkSize;
    private final boolean compressionEnabled;
    private final Map<String, String> tlsConfig;

    private static final ScheduledExecutorService timeoutScheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "AsyncSender-TimeoutScheduler");
            t.setDaemon(true);
            return t;
        });

    private AsyncSender(Builder builder) {
        this.clusters = Collections.unmodifiableList(new ArrayList<>(builder.clusters));
        this.timeout = builder.timeout;
        this.sourceIp = builder.sourceIp;
        this.chunkSize = builder.chunkSize;
        this.compressionEnabled = builder.compressionEnabled;
        this.tlsConfig = Collections.unmodifiableMap(new HashMap<>(builder.tlsConfig));

        logger.debug("AsyncSender initialized. Clusters: {}, Timeout: {}ms, SourceIP: {}, ChunkSize: {}, Compression: {}, TLS Config Keys: {}",
                this.clusters.stream().map(Cluster::toString).collect(Collectors.joining("; ")),
                this.timeout,
                this.sourceIp == null ? "N/A" : this.sourceIp,
                this.chunkSize,
                this.compressionEnabled,
                this.tlsConfig.keySet());
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<Cluster> getClusters() { return clusters; }
    public int getTimeout() { return timeout; }
    public String getSourceIp() { return sourceIp; }
    public int getChunkSize() { return chunkSize; }
    public boolean isCompressionEnabled() { return compressionEnabled; }
    public Map<String, String> getTlsConfig() { return tlsConfig; }

    public CompletableFuture<TrapperResponse> send(List<ItemValue> items) {
        if (items == null || items.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Items list cannot be null or empty."));
        }
        for (ItemValue item : items) {
            if (item == null) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("ItemValue within the list cannot be null."));
            }
        }

        List<List<ItemValue>> chunks = new ArrayList<>();
        for (int i = 0; i < items.size(); i += this.chunkSize) {
            chunks.add(items.subList(i, Math.min(items.size(), i + this.chunkSize)));
        }

        TrapperResponse totalAggregatedResponse = new TrapperResponse();
        CompletableFuture<TrapperResponse> overallFuture = CompletableFuture.completedFuture(totalAggregatedResponse);

        for (int i = 0; i < chunks.size(); i++) {
            List<ItemValue> currentChunk = chunks.get(i);
            final int chunkNumber = i + 1;
            final int totalChunks = chunks.size();

            overallFuture = overallFuture.thenCompose(aggregatedResp -> {
                logger.debug("Processing chunk {}/{} ({} items)", chunkNumber, totalChunks, currentChunk.size());
                return sendChunkToAnyCluster(currentChunk)
                    .handle((chunkResp, ex) -> {
                        if (ex != null) {
                            logger.error("Chunk {}/{} failed to send to any cluster: {}", chunkNumber, totalChunks, ex.getMessage(), ex);
                            String failedChunkInfo = String.format("processed: 0; failed: %d; total: %d; seconds spent: 0.0",
                                                                   currentChunk.size(), currentChunk.size());
                            aggregatedResp.parseAndAdd(failedChunkInfo);
                        } else if (chunkResp != null) {
                            String successChunkInfo = String.format("processed: %d; failed: %d; total: %d; seconds spent: %f",
                                                                    chunkResp.getProcessed(), chunkResp.getFailed(),
                                                                    chunkResp.getTotal(), chunkResp.getTimeSpent());
                            aggregatedResp.parseAndAdd(successChunkInfo);
                        }
                        return aggregatedResp; 
                    });
            });
        }
        return overallFuture;
    }

    private CompletableFuture<TrapperResponse> sendChunkToAnyCluster(List<ItemValue> chunk) {
        CompletableFuture<TrapperResponse> overallChunkFuture = new CompletableFuture<>();
        AtomicInteger clusterAttemptIndex = new AtomicInteger(0);

        Runnable tryNextCluster = new Runnable() {
            @Override
            public void run() {
                if (clusterAttemptIndex.get() >= clusters.size()) {
                    overallChunkFuture.completeExceptionally(
                        new ZabbixProcessingException("Chunk failed: All clusters unavailable for " + chunk.size() + " items."));
                    return;
                }
                Cluster currentCluster = clusters.get(clusterAttemptIndex.getAndIncrement());
                logger.debug("Attempting to send chunk to cluster ({} nodes)", currentCluster.getNodes().size());
                sendChunkToSpecificCluster(chunk, currentCluster)
                    .thenAccept(overallChunkFuture::complete) 
                    .exceptionally(ex -> {
                        logger.warn("Failed to send chunk to cluster {}: {}", clusterAttemptIndex.get(), ex.getMessage());
                        run(); 
                        return null;
                    });
            }
        };
        tryNextCluster.run();
        return overallChunkFuture;
    }

    private CompletableFuture<TrapperResponse> sendChunkToSpecificCluster(List<ItemValue> chunk, Cluster cluster) {
        CompletableFuture<TrapperResponse> clusterSendFuture = new CompletableFuture<>();
        AtomicInteger nodeAttemptIndex = new AtomicInteger(0);
        List<Node> nodesInCluster = cluster.getNodes();

        Runnable tryNextNode = new Runnable() {
            @Override
            public void run() {
                if (nodeAttemptIndex.get() >= nodesInCluster.size()) {
                    clusterSendFuture.completeExceptionally(
                        new ZabbixProcessingException("Chunk failed for cluster: All " + nodesInCluster.size() + " nodes failed."));
                    return;
                }
                Node currentNode = nodesInCluster.get(nodeAttemptIndex.getAndIncrement());
                logger.info("Attempting to send chunk to node: {}:{}", currentNode.getAddress(), currentNode.getPort());
                sendChunkToNode(chunk, currentNode)
                    .thenAccept(clusterSendFuture::complete)
                    .exceptionally(ex -> {
                        logger.warn("Failed to send chunk to node {}:{}: {}", currentNode.getAddress(), currentNode.getPort(), ex.getMessage());
                        run(); 
                        return null;
                    });
            }
        };
        tryNextNode.run();
        return clusterSendFuture;
    }

    private CompletableFuture<TrapperResponse> sendChunkToNode(List<ItemValue> chunk, Node node) {
        final CompletableFuture<TrapperResponse> nodeSendFuture = new CompletableFuture<>();
        AsynchronousSocketChannel channel = null;

        try {
            channel = AsynchronousSocketChannel.open();
            if (sourceIp != null) {
                channel.bind(new InetSocketAddress(sourceIp, 0));
            }
            channel.setOption(StandardSocketOptions.SO_KEEPALIVE, true);

            List<Map<String, Object>> itemDataList = new ArrayList<>();
            for (ItemValue itemValue : chunk) {
                itemDataList.add(itemValue.toMap());
            }
            Map<String, Object> requestPayloadMap = new HashMap<>();
            requestPayloadMap.put("request", "sender data");
            requestPayloadMap.put("data", new JSONArray(itemDataList));
            String jsonPayloadString = new JSONObject(requestPayloadMap).toString();

            byte[] requestPacket = ZabbixProtocol.createPacket(jsonPayloadString, this.compressionEnabled);
            ByteBuffer writeBuffer = ByteBuffer.wrap(requestPacket);

            final AsynchronousSocketChannel finalChannel = channel; 
            
            java.util.concurrent.ScheduledFuture<?> timeoutHandle = timeoutScheduler.schedule(() -> {
                if (!nodeSendFuture.isDone()) {
                    nodeSendFuture.completeExceptionally(new ZabbixProcessingException("Timeout sending to node " + node + " after " + timeout + "ms"));
                    closeChannel(finalChannel, "Node operation timeout");
                }
            }, timeout, TimeUnit.MILLISECONDS);

            nodeSendFuture.whenComplete((res, ex) -> timeoutHandle.cancel(false));

            finalChannel.connect(new InetSocketAddress(node.getAddress(), node.getPort()), null, new CompletionHandler<Void, Void>() {
                @Override
                public void completed(Void result, Void attachment) {
                    logger.debug("Connected to {}. Writing packet...", node);
                    finalChannel.write(writeBuffer, timeout, TimeUnit.MILLISECONDS, null, new CompletionHandler<Integer, Void>() {
                        @Override
                        public void completed(Integer bytesWritten, Void attachment) {
                            if (bytesWritten < requestPacket.length) {
                                nodeSendFuture.completeExceptionally(new ZabbixProcessingException("Incomplete write to " + node));
                                closeChannel(finalChannel, "Incomplete write");
                                return;
                            }
                            readHeaderFromNode(finalChannel, node, nodeSendFuture);
                        }
                        @Override
                        public void failed(Throwable exc, Void attachment) {
                            nodeSendFuture.completeExceptionally(new ZabbixProcessingException("Failed to write to " + node, exc));
                            closeChannel(finalChannel, "Write failed");
                        }
                    });
                }
                @Override
                public void failed(Throwable exc, Void attachment) {
                    nodeSendFuture.completeExceptionally(new ZabbixProcessingException("Failed to connect to " + node, exc));
                    closeChannel(finalChannel, "Connect failed");
                }
            });

        } catch (IOException | JSONException | ZabbixProcessingException e) {
            nodeSendFuture.completeExceptionally(new ZabbixProcessingException("Error setting up connection to " + node, e));
            closeChannel(channel, "Setup failed");
        }
        return nodeSendFuture;
    }

    private void readHeaderFromNode(AsynchronousSocketChannel channel, Node node, CompletableFuture<TrapperResponse> nodeSendFuture) {
        ByteBuffer headerReadBuffer = ByteBuffer.allocate(ZabbixProtocol.HEADER_SIZE);
        channel.read(headerReadBuffer, timeout, TimeUnit.MILLISECONDS, null, new CompletionHandler<Integer, Void>() {
            @Override
            public void completed(Integer bytesRead, Void attachment) {
                if (bytesRead < ZabbixProtocol.HEADER_SIZE) {
                    nodeSendFuture.completeExceptionally(new ZabbixProcessingException("Incomplete header from " + node + ". Read " + bytesRead + " bytes."));
                    closeChannel(channel, "Incomplete header");
                    return;
                }
                headerReadBuffer.flip();
                byte[] headerBytes = new byte[ZabbixProtocol.HEADER_SIZE];
                headerReadBuffer.get(headerBytes);

                try {
                    if (!Arrays.equals(Arrays.copyOfRange(headerBytes, 0, ZabbixProtocol.ZABBIX_HEADER_BYTES.length), ZabbixProtocol.ZABBIX_HEADER_BYTES)) {
                        throw new ZabbixProcessingException("Invalid Zabbix header magic from " + node);
                    }
                    ByteBuffer parsedHeader = ByteBuffer.wrap(headerBytes);
                    parsedHeader.order(ByteOrder.LITTLE_ENDIAN);
                    parsedHeader.position(ZabbixProtocol.ZABBIX_HEADER_BYTES.length);
                    byte flags = parsedHeader.get();
                    int dataLength = parsedHeader.getInt();

                    if ((flags & ZabbixProtocol.FLAGS_PROTOCOL_VERSION) == 0) throw new ZabbixProcessingException("Invalid protocol version from " + node);
                    if ((flags & ZabbixProtocol.FLAGS_LARGE_PACKET) != 0) throw new ZabbixProcessingException("Large packets not supported from " + node);
                    if (dataLength < 0) throw new ZabbixProcessingException("Invalid data length from " + node + ": " + dataLength);
                    
                    if (dataLength == 0) { 
                        logger.warn("Received empty payload from {}, assuming no items processed or error. Check server logs.", node);
                        TrapperResponse emptyResp = new TrapperResponse(); 
                        nodeSendFuture.complete(emptyResp);
                        closeChannel(channel, "Empty valid payload");
                        return;
                    }
                    readPayloadFromNode(channel, node, nodeSendFuture, dataLength, (flags & ZabbixProtocol.FLAGS_COMPRESSION) != 0);
                } catch (ZabbixProcessingException e) {
                    nodeSendFuture.completeExceptionally(e);
                    closeChannel(channel, "Header processing error");
                }
            }
            @Override
            public void failed(Throwable exc, Void attachment) {
                nodeSendFuture.completeExceptionally(new ZabbixProcessingException("Failed to read header from " + node, exc));
                closeChannel(channel, "Header read failed");
            }
        });
    }

    private void readPayloadFromNode(AsynchronousSocketChannel channel, Node node, CompletableFuture<TrapperResponse> nodeSendFuture, int dataLength, boolean isCompressed) {
        ByteBuffer payloadBuffer = ByteBuffer.allocate(dataLength);
        channel.read(payloadBuffer, timeout, TimeUnit.MILLISECONDS, null, new CompletionHandler<Integer, Void>() {
            @Override
            public void completed(Integer bytesRead, Void attachment) {
                 if (bytesRead < 0 || payloadBuffer.hasRemaining()) { 
                    if(payloadBuffer.position() < dataLength && bytesRead > 0) { 
                        channel.read(payloadBuffer, timeout, TimeUnit.MILLISECONDS, null, this);
                        return;
                    }
                    nodeSendFuture.completeExceptionally(new ZabbixProcessingException("Incomplete payload from " + node + ". Expected " + dataLength + ", got " + payloadBuffer.position()));
                    closeChannel(channel, "Incomplete payload");
                    return;
                }
                payloadBuffer.flip();
                byte[] receivedData = new byte[dataLength];
                payloadBuffer.get(receivedData);
                byte[] finalPayloadBytes = receivedData;

                try {
                    if (isCompressed) {
                        Inflater inflater = new Inflater();
                        inflater.setInput(receivedData);
                        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                            byte[] buffer = new byte[1024];
                            while (!inflater.finished()) {
                                int count = inflater.inflate(buffer);
                                 if (count == 0) {
                                    if (inflater.needsInput()) {
                                        throw new ZabbixProcessingException("Decompression error: Inflater needs input unexpectedly from " + node);
                                    }
                                    break; 
                                }
                                baos.write(buffer, 0, count);
                            }
                            finalPayloadBytes = baos.toByteArray();
                        } finally {
                            inflater.end();
                        }
                    }

                    String responseString = new String(finalPayloadBytes, StandardCharsets.UTF_8);
                    logger.debug("Raw response string from {}: {}", node, responseString);
                    JSONObject jsonResponse = new JSONObject(responseString);
                    String responseStatus = jsonResponse.optString("response", "failed");

                    if ("success".equalsIgnoreCase(responseStatus)) {
                        String info = jsonResponse.optString("info");
                        TrapperResponse trapperResponse = new TrapperResponse();
                        trapperResponse.parseAndAdd(info);
                        logger.info("Chunk successfully sent to {}. Info: {}", node, info);
                        nodeSendFuture.complete(trapperResponse);
                    } else {
                        String info = jsonResponse.optString("info", "No specific error info from Zabbix.");
                        if (jsonResponse.has("redirect")) {
                             JSONObject redirect = jsonResponse.getJSONObject("redirect");
                             logger.warn("Received redirect from {} to {}. Redirection not automatically handled. Info: {}", node, redirect.optString("address"), info);
                             nodeSendFuture.completeExceptionally(new ZabbixProcessingException("Redirected by " + node + " to " + redirect.optString("address") + ". Info: " + info));
                        } else {
                            logger.error("Failed response from {}. Response: {}, Info: {}", node, responseStatus, info);
                            nodeSendFuture.completeExceptionally(new ZabbixProcessingException("Zabbix Sender failed at " + node + ". Response: " + responseStatus + ", Info: " + info));
                        }
                    }
                } catch (Exception e) { 
                    nodeSendFuture.completeExceptionally(new ZabbixProcessingException("Error processing payload from " + node, e));
                } finally {
                    closeChannel(channel, "Payload processing finished");
                }
            }
            @Override
            public void failed(Throwable exc, Void attachment) {
                nodeSendFuture.completeExceptionally(new ZabbixProcessingException("Failed to read payload from " + node, exc));
                closeChannel(channel, "Payload read failed");
            }
        });
    }

    private void closeChannel(AsynchronousSocketChannel channel, String reason) {
        if (channel != null && channel.isOpen()) {
            try {
                logger.debug("Closing channel. Reason: {}", reason);
                channel.close();
            } catch (IOException e) {
                logger.warn("Error closing channel: {}", e.getMessage(), e);
            }
        }
    }

    public CompletableFuture<TrapperResponse> sendValue(String host, String key, String value) {
        ItemValue item = new ItemValue(host, key, value);
        return send(Collections.singletonList(item));
    }

    public CompletableFuture<TrapperResponse> sendValue(String host, String key, String value, Long clock, Integer ns) {
        ItemValue item = new ItemValue(host, key, value, clock, ns);
        return send(Collections.singletonList(item));
    }

    public static final class Builder {
        private List<Cluster> clusters = new ArrayList<>();
        private Integer timeout;
        private String sourceIp;
        private Integer chunkSize;
        private Boolean compressionEnabled;
        private String agentConfigPath;
        private Map<String, String> tlsConfig = new HashMap<>();

        private boolean serverConfigFromBuilder = false;
        private boolean sourceIpFromBuilder = false;

        private Builder() {}

        public Builder server(String host, int port) {
            if (host == null || host.trim().isEmpty()) throw new IllegalArgumentException("Host cannot be null or empty.");
            if (port <= 0 || port > 65535) throw new IllegalArgumentException("Port out of range.");
            this.clusters.add(new Cluster(Collections.singletonList(host + ":" + port)));
            this.serverConfigFromBuilder = true;
            return this;
        }

        public Builder server(String host) {
            return server(host, DEFAULT_ZABBIX_SENDER_PORT);
        }

        public Builder cluster(Cluster cluster) {
            if (cluster == null) throw new IllegalArgumentException("Cluster cannot be null.");
            this.clusters.add(cluster);
            this.serverConfigFromBuilder = true;
            return this;
        }
        
        public Builder clusters(List<Cluster> clusters) {
            if (clusters == null) throw new IllegalArgumentException("Clusters list cannot be null.");
            this.clusters = new ArrayList<>(clusters);
            this.serverConfigFromBuilder = true;
            return this;
        }

        public Builder timeout(int timeout) {
            if (timeout <= 0) throw new IllegalArgumentException("Timeout must be positive.");
            this.timeout = timeout;
            return this;
        }

        public Builder sourceIp(String sourceIp) {
            if (sourceIp != null && sourceIp.trim().isEmpty()) throw new IllegalArgumentException("Source IP cannot be an empty string. Pass null if not needed.");
            this.sourceIp = sourceIp;
            this.sourceIpFromBuilder = true;
            return this;
        }

        public Builder chunkSize(int chunkSize) {
            if (chunkSize <= 0) throw new IllegalArgumentException("Chunk size must be positive.");
            this.chunkSize = chunkSize;
            return this;
        }

        public Builder enableCompression(boolean enabled) {
            this.compressionEnabled = enabled;
            return this;
        }
        
        public Builder tlsConfig(String key, String value) {
            if (key == null || key.trim().isEmpty()) throw new IllegalArgumentException("TLS config key cannot be null or empty.");
            if (value == null) throw new IllegalArgumentException("TLS config value cannot be null.");
            this.tlsConfig.put(key, value);
            return this;
        }

        public Builder agentConfigPath(String agentConfigPath) {
            if (agentConfigPath == null || agentConfigPath.trim().isEmpty()) {
                this.agentConfigPath = null;
            } else {
                this.agentConfigPath = agentConfigPath.trim();
            }
            return this;
        }

        public AsyncSender build() throws ZabbixProcessingException {
            if (agentConfigPath != null) {
                loadConfigurationFromAgent();
            }

            if (this.timeout == null) this.timeout = DEFAULT_TIMEOUT_MS;
            if (this.chunkSize == null) this.chunkSize = DEFAULT_CHUNK_SIZE;
            if (this.compressionEnabled == null) this.compressionEnabled = false;

            if (this.clusters.isEmpty()) {
                throw new IllegalArgumentException("No Zabbix servers/proxies defined.");
            }
            return new AsyncSender(this);
        }

        private void loadConfigurationFromAgent() throws ZabbixProcessingException {
            logger.info("AsyncSender: Loading configuration from agent config file: {}", agentConfigPath);
            Sender.SenderConfigData configData = Sender.parseAgentConfiguration(agentConfigPath);

            if (!serverConfigFromBuilder && !configData.getParsedClusters().isEmpty()) {
                logger.debug("AsyncSender: Using ServerActive/Server from config file: {}", configData.getParsedClusters());
                this.clusters = new ArrayList<>(configData.getParsedClusters());
            } else if (serverConfigFromBuilder && !configData.getParsedClusters().isEmpty()){
                 logger.debug("AsyncSender: Server/cluster configuration was set explicitly on builder, ignoring ServerActive/Server from config file.");
            }

            if (!sourceIpFromBuilder && configData.getParsedSourceIp() != null) {
                logger.debug("AsyncSender: Using SourceIP from config file: {}", configData.getParsedSourceIp());
                this.sourceIp = configData.getParsedSourceIp();
            } else if (sourceIpFromBuilder && configData.getParsedSourceIp() != null) {
                logger.debug("AsyncSender: SourceIP was set explicitly on builder, ignoring SourceIP from config file.");
            }
            
            for (Map.Entry<String, String> entry : configData.getParsedTlsConfig().entrySet()) {
                this.tlsConfig.putIfAbsent(entry.getKey(), entry.getValue());
            }
        }
    }
}
