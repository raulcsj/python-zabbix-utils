package io.zabbix4j.api.sync;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zabbix4j.api.dto.ClusterNode;
import io.zabbix4j.api.dto.ItemValue;
import io.zabbix4j.api.dto.Node;
import io.zabbix4j.api.dto.TrapperResponse;
import io.zabbix4j.api.exception.CommunicationException;
import io.zabbix4j.api.exception.ProcessingException;
import io.zabbix4j.api.protocol.ZabbixProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Synchronous client for sending data to Zabbix server/proxy using the Zabbix Sender protocol.
 * This class allows sending {@link ItemValue} objects in chunks to one or more Zabbix clusters.
 * <p>
 * Use the {@link Builder} to construct instances of this class.
 * </p>
 * Example:
 * <pre>{@code
 * ZabbixSenderSync sender = new ZabbixSenderSync.Builder()
 * .server("zabbix.example.com", 10051)
 * .timeout(5) // seconds
 * .build();
 *
 * List<ItemValue> items = List.of(
 * ItemValue.builder().host("Host1").key("item.key1").value("123").build(),
 * ItemValue.builder().host("Host1").key("item.key2").value("abc").build()
 * );
 *
 * try {
 * TrapperResponse response = sender.send(items);
 * System.out.println("Items processed: " + response.getProcessed());
 * } catch (ProcessingException | CommunicationException e) { // Catch specific or general ZabbixApiException
 * System.err.println("Failed to send data: " + e.getMessage());
 * }
 * }</pre>
 *
 * @author ShortRoundDev
 */
public final class ZabbixSenderSync {

    private static final Logger logger = LoggerFactory.getLogger(ZabbixSenderSync.class);

    private final List<ClusterNode> clusters;
    private final int timeoutMillis;
    private final boolean useIpv6;
    private final InetAddress sourceIpAddress;
    private final int chunkSize;
    private final boolean useCompression;
    private final ObjectMapper objectMapper;

    private ZabbixSenderSync(Builder builder) {
        if (builder.clusters.isEmpty()) {
            throw new IllegalArgumentException("At least one server or cluster must be configured.");
        }
        this.clusters = Collections.unmodifiableList(new ArrayList<>(builder.clusters));
        this.timeoutMillis = builder.timeoutSeconds > 0 ? builder.timeoutSeconds * 1000 : 0;
        this.useIpv6 = builder.useIpv6;
        this.sourceIpAddress = builder.sourceIpAddress;
        this.chunkSize = builder.chunkSize > 0 ? builder.chunkSize : 250;
        this.useCompression = builder.useCompression;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Sends a list of item values to the configured Zabbix servers/proxies.
     * The items are split into chunks and sent sequentially. Each chunk is attempted on all configured clusters.
     *
     * @param items The list of {@link ItemValue}s to send.
     * @return An aggregated {@link TrapperResponse} summarizing the results from all chunks and successful cluster communications.
     * @throws IllegalArgumentException if items is null or empty.
     * @throws ProcessingException      if data processing fails or if all chunks fail to be sent to any cluster.
     * @throws CommunicationException   if underlying communication errors occur and are not handled by failover.
     */
    public TrapperResponse send(List<ItemValue> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("Items list cannot be null or empty.");
        }
        for (ItemValue item : items) {
            Objects.requireNonNull(item, "ItemValue in list cannot be null.");
        }

        List<List<ItemValue>> itemChunks = new ArrayList<>();
        for (int i = 0; i < items.size(); i += chunkSize) {
            itemChunks.add(items.subList(i, Math.min(items.size(), i + chunkSize)));
        }

        logger.info("Sending {} item(s) in {} chunk(s) of size up to {}. Target clusters: {}",
                items.size(), itemChunks.size(), chunkSize, this.clusters.size());

        TrapperResponse aggregatedResponse = new TrapperResponse(0, 0, 0, 0.0, null);
        Map<Integer, Map<String, TrapperResponse>> detailedResponsesByChunk = new HashMap<>();
        boolean anyChunkSentSuccessfullyToAnyCluster = false;

        for (int i = 0; i < itemChunks.size(); i++) {
            List<ItemValue> chunk = itemChunks.get(i);
            int chunkNumber = i + 1;
            logger.debug("Processing chunk {} of {}. Items in chunk: {}", chunkNumber, itemChunks.size(), chunk.size());

            Map<String, TrapperResponse> chunkResponsesFromClusters = sendChunkToAllClusters(chunk, chunkNumber);

            if (!chunkResponsesFromClusters.isEmpty()) {
                anyChunkSentSuccessfullyToAnyCluster = true;
                detailedResponsesByChunk.put(chunkNumber, chunkResponsesFromClusters);
                for (TrapperResponse resp : chunkResponsesFromClusters.values()) {
                    aggregatedResponse = aggregatedResponse.add(resp);
                }
                logger.info("Chunk {} processed. Responses from {} cluster(s). Aggregated: P:{}, F:{}, T:{}",
                        chunkNumber, chunkResponsesFromClusters.size(),
                        aggregatedResponse.getProcessed(), aggregatedResponse.getFailed(), aggregatedResponse.getTotal());
            } else {
                logger.error("Chunk {} failed to be sent to ANY configured cluster.", chunkNumber);
            }
        }

        if (!anyChunkSentSuccessfullyToAnyCluster && !items.isEmpty()) {
            throw new ProcessingException("Failed to send any data to any configured Zabbix cluster. All chunks/clusters failed.");
        }

        logger.info("Finished sending all chunks. Final aggregated response: Processed: {}, Failed: {}, Total: {}, Time Spent: {}s",
                aggregatedResponse.getProcessed(), aggregatedResponse.getFailed(), aggregatedResponse.getTotal(), aggregatedResponse.getTimeSpentSeconds());
        return aggregatedResponse;
    }

    private Map<String, TrapperResponse> sendChunkToAllClusters(List<ItemValue> chunkItems, int chunkNumber) {
        Map<String, TrapperResponse> successfulClusterResponses = new HashMap<>();

        for (ClusterNode cluster : this.clusters) {
            String clusterId = cluster.toString();
            try {
                List<Node> nodesInClusterCopy = new ArrayList<>(cluster.getNodes());
                TrapperResponse response = sendChunkToSingleClusterInternal(nodesInClusterCopy, clusterId, chunkItems, chunkNumber);
                successfulClusterResponses.put(clusterId, response);
                logger.info("Chunk {} successfully sent to cluster '{}' via node {}.",
                        chunkNumber, clusterId, nodesInClusterCopy.get(0));
            } catch (ProcessingException | CommunicationException e) {
                logger.warn("Failed to send chunk {} to cluster '{}': {}. Trying next cluster if available.",
                        chunkNumber, clusterId, e.getMessage());
            }
        }
        return successfulClusterResponses;
    }

    private TrapperResponse sendChunkToSingleClusterInternal(List<Node> nodesInClusterConfig, String clusterNameForLog, List<ItemValue> chunkItems, int chunkNumberForLog) {
        Map<String, Object> payloadMap = new HashMap<>();
        payloadMap.put("request", "sender data");
        payloadMap.put("data", chunkItems);

        String jsonDataPayload;
        try {
            jsonDataPayload = objectMapper.writeValueAsString(payloadMap);
        } catch (JsonProcessingException e) {
            throw new ProcessingException("Failed to serialize chunk " + chunkNumberForLog + " to JSON for cluster '" + clusterNameForLog + "': " + e.getMessage(), e);
        }

        byte[] packet = ZabbixProtocol.createPacket(jsonDataPayload, this.useCompression);
        List<Exception> nodeExceptions = new ArrayList<>();

        for (int i = 0; i < nodesInClusterConfig.size(); i++) {
            Node node = nodesInClusterConfig.get(i);
            logger.debug("Attempting to send chunk {} to node {} in cluster '{}'.", chunkNumberForLog, node, clusterNameForLog);

            try (Socket socket = new Socket()) {
                if (this.sourceIpAddress != null) {
                    socket.bind(new InetSocketAddress(this.sourceIpAddress, 0));
                }
                socket.connect(new InetSocketAddress(node.getAddress(), node.getPort()), this.timeoutMillis > 0 ? this.timeoutMillis : 0);
                if (this.timeoutMillis > 0) {
                    socket.setSoTimeout(this.timeoutMillis);
                }

                try (OutputStream out = socket.getOutputStream();
                     InputStream in = socket.getInputStream()) {

                    out.write(packet);
                    out.flush();

                    String responseJson = ZabbixProtocol.parseResponse(in);
                    logger.debug("Received response for chunk {} from node {}: {}", chunkNumberForLog, node, responseJson.substring(0, Math.min(responseJson.length(), 200)));

                    com.fasterxml.jackson.databind.JsonNode responseNode = objectMapper.readTree(responseJson);
                    if (!responseNode.has("response") || !"success".equalsIgnoreCase(responseNode.get("response").asText())) {
                        String errorMsg = "Zabbix sender protocol response indicates failure from node " + node;
                        if (responseNode.has("info")) {
                            errorMsg += ": " + responseNode.get("info").asText();
                        }
                        throw new ProcessingException(errorMsg);
                    }
                    if (!responseNode.has("info")) {
                         throw new ProcessingException("Zabbix sender protocol response from node " + node + " missing 'info' field.");
                    }

                    TrapperResponse trapperResponse = TrapperResponse.parseInfoString(responseNode.get("info").asText(), chunkNumberForLog);

                    if (i > 0) {
                        Collections.swap(nodesInClusterConfig, 0, i);
                        logger.info("Node {} in cluster '{}' successfully processed chunk {}. Promoted for subsequent attempts within this call for this cluster.", node, clusterNameForLog, chunkNumberForLog);
                    }
                    return trapperResponse;
                }
            } catch (UnknownHostException e) {
                logger.warn("Node {} in cluster '{}' is unknown: {}. Trying next node.", node, clusterNameForLog, e.getMessage());
                nodeExceptions.add(new CommunicationException("Unknown host " + node.getAddress() + " for cluster '" + clusterNameForLog + "': " + e.getMessage(), e));
            } catch (ConnectException e) {
                logger.warn("Failed to connect to node {} in cluster '{}': {}. Trying next node.", node, clusterNameForLog, e.getMessage());
                nodeExceptions.add(new CommunicationException("Connection refused by " + node + " for cluster '" + clusterNameForLog + "': " + e.getMessage(), e));
            } catch (SocketTimeoutException e) {
                logger.warn("Timeout connecting or reading from node {} in cluster '{}': {}. Trying next node.", node, clusterNameForLog, e.getMessage());
                nodeExceptions.add(new CommunicationException("Timeout with " + node + " for cluster '" + clusterNameForLog + "': " + e.getMessage(), e));
            } catch (IOException e) {
                logger.warn("IOException with node {} in cluster '{}': {}. Trying next node.", node, clusterNameForLog, e.getMessage(), e);
                nodeExceptions.add(new CommunicationException("IO error with " + node + " for cluster '" + clusterNameForLog + "': " + e.getMessage(), e));
            } catch (ProcessingException e) {
                 logger.warn("Error processing response from node {} in cluster '{}': {}. Trying next node.", node, clusterNameForLog, e.getMessage());
                nodeExceptions.add(e);
            }
        }

        String combinedNodeErrors = nodeExceptions.stream()
                .map(Throwable::getMessage)
                .collect(Collectors.joining("; "));
        throw new ProcessingException(String.format(
                "Failed to send chunk %d to any node in cluster '%s'. Errors: [%s]",
                chunkNumberForLog, clusterNameForLog, combinedNodeErrors
        ));
    }

    public TrapperResponse sendValue(String host, String key, String value, Long clock, Integer ns) {
        ItemValue.Builder itemBuilder = ItemValue.builder().host(host).key(key).value(value);
        if (clock != null) {
            itemBuilder.clock(clock);
        }
        if (ns != null) {
            itemBuilder.ns(ns);
        }
        return send(Collections.singletonList(itemBuilder.build()));
    }

    public static class Builder {
        private List<ClusterNode> clusters = new ArrayList<>();
        private int timeoutSeconds = 5;
        private boolean useIpv6 = false;
        private InetAddress sourceIpAddress;
        private int chunkSize = 250;
        private boolean useCompression = false;

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

        public Builder useIpv6(boolean useIpv6) {
            this.useIpv6 = useIpv6;
            return this;
        }

        public Builder sourceIp(String ip) {
            try {
                this.sourceIpAddress = InetAddress.getByName(ip);
            } catch (UnknownHostException e) {
                throw new ProcessingException("Invalid source IP address: " + ip, e);
            }
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
        
        public Builder useCompression() { // Overload for true
            this.useCompression = true;
            return this;
        }

        public ZabbixSenderSync build() {
            if (clusters.isEmpty()) {
                throw new IllegalArgumentException("At least one Zabbix server or cluster must be configured for ZabbixSenderSync.");
            }
            return new ZabbixSenderSync(this);
        }
    }
}
