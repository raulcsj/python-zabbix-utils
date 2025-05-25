package io.zabbix4j.sender;

import com.google.gson.Gson;
import io.zabbix4j.api.types.Cluster;
import io.zabbix4j.api.types.ItemValue; // Will be used in send() method, import now
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.reflect.TypeToken;
import io.zabbix4j.api.common.ZabbixProtocol;
import io.zabbix4j.api.exceptions.ProcessingException;
import io.zabbix4j.api.types.Node;
import io.zabbix4j.api.types.TrapperResponse;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Sends data to Zabbix server or proxy using the Zabbix Sender protocol.
 * <p>
 * This class allows sending item values to Zabbix. It can be configured
 * to send data to a single server or a list of server clusters.
 * Configuration can be done via the {@link ZabbixSenderBuilder}.
 * </p>
 *
 * @author CSJ
 */
public class ZabbixSender {

    private static final Logger logger = LoggerFactory.getLogger(ZabbixSender.class);

    private final List<Cluster> clusters;
    private final int timeoutMs;
    private final boolean useIpv6; // Not directly used by Socket, but stored for consistency
    private final String sourceIp;  // Not directly used by Socket, but stored
    private final int chunkSize;
    private final boolean useCompression;
    private final Gson gson = new Gson();
    private final SocketFactory socketFactory; // For testing

    // TODO: Add fields for TLS/socket wrapping if needed later, like socket_wrapper and tls from Python.

    /**
     * Functional interface for creating sockets, allowing for mock injection in tests.
     */
    @FunctionalInterface
    interface SocketFactory {
        Socket createSocket() throws IOException;
    }


    /**
     * Internal record to pair a successfully contacted Node with its TrapperResponse.
     */
    private static class NodeResponsePair {
        final Node node;
        final TrapperResponse response;

        NodeResponsePair(Node node, TrapperResponse response) {
            this.node = node;
            this.response = response;
        }
    }

    /**
     * Private constructor. Use {@link ZabbixSenderBuilder} to create instances.
     *
     * @param builder The builder instance with configuration.
     */
    private ZabbixSender(ZabbixSenderBuilder builder) {
        this(builder, Socket::new); // Default socket factory
    }

    /**
     * Package-private constructor for testing, allowing SocketFactory injection.
     * @param builder Builder instance.
     * @param socketFactory Factory to create sockets.
     */
    ZabbixSender(ZabbixSenderBuilder builder, SocketFactory socketFactory) {
        this.timeoutMs = builder.timeoutSeconds * 1000;
        this.useIpv6 = builder.useIpv6;
        this.sourceIp = builder.sourceIp;
        this.chunkSize = builder.chunkSize;
        this.useCompression = builder.useCompression;
        this.socketFactory = socketFactory;

        List<Cluster> builtClusters = new ArrayList<>();
        if (!builder.rawClusters.isEmpty()) {
            for (List<String> nodeAddresses : builder.rawClusters) {
                try {
                    builtClusters.add(new Cluster(nodeAddresses));
                } catch (IllegalArgumentException e) {
                    // Wrap exception to provide more context from builder
                    throw new IllegalArgumentException("Failed to create cluster from node addresses: " + nodeAddresses + ". " + e.getMessage(), e);
                }
            }
        } else if (builder.serverAddress != null) {
            // If serverAddress is set (and rawClusters is empty), it implies a single cluster with one node
            builtClusters.add(new Cluster(Collections.singletonList(builder.serverAddress + ":" + builder.serverPort)));
        } else {
            // Default to 127.0.0.1:10051 if nothing else is configured
            logger.info("No server or cluster configured, defaulting to 127.0.0.1:10051");
            builtClusters.add(new Cluster(Collections.singletonList("127.0.0.1:10051")));
        }
        this.clusters = Collections.unmodifiableList(builtClusters);

        logger.info("ZabbixSender initialized. Clusters: {}, Timeout: {}ms, Compression: {}, ChunkSize: {}",
                this.clusters.stream().map(Cluster::toString).collect(Collectors.joining(", ")),
                this.timeoutMs, this.useCompression, this.chunkSize);
    }

    /**
     * Placeholder method for loading configuration from a Zabbix agent configuration file.
     * This method is not yet implemented.
     *
     * @param builder     The builder instance to populate.
     * @param configPath The path to the Zabbix agent configuration file.
     */
    private static void loadFromAgentConfig(ZabbixSenderBuilder builder, String configPath) {
        logger.warn("Loading configuration from Zabbix agent config file '{}' is not yet implemented. " +
                "Please configure the sender via the builder methods directly. " +
                "This path will be ignored for now.", configPath);
        // In a real implementation, this would parse the config file and call builder methods:
        // e.g., builder.server(parsedActiveServer, parsedPort);
        // builder.timeoutSeconds(parsedTimeout);
        // builder.compression(parsedCompression); // if available in agent config
        // builder.sourceIp(parsedSourceIP);
        // etc.
    }


    /**
     * Builder class for {@link ZabbixSender}.
     * Provides a fluent API for constructing {@code ZabbixSender} instances with custom configurations.
     */
    public static class ZabbixSenderBuilder {
        private String serverAddress = null; // Default to null, will use 127.0.0.1 if nothing else is set
        private int serverPort = 10051;
        private final List<List<String>> rawClusters = new ArrayList<>();
        private int timeoutSeconds = 10;
        private boolean useIpv6 = false;
        private String sourceIp = null;
        private int chunkSize = 250;
        private boolean useCompression = false;
        private String agentConfigPath = null;

        /**
         * Sets the Zabbix server address and port for a single-server setup.
         * This is a convenience method that creates a single cluster with one node.
         * Calling this will clear any previously added clusters via {@link #addCluster(List)}.
         *
         * @param address The Zabbix server address (e.g., "127.0.0.1", "zabbix.example.com").
         * @param port    The Zabbix server port (typically 10051).
         * @return This builder instance for chaining.
         * @throws IllegalArgumentException if the address is null or empty, or port is out of range.
         */
        public ZabbixSenderBuilder server(String address, int port) {
            if (address == null || address.trim().isEmpty()) {
                throw new IllegalArgumentException("Server address cannot be null or empty.");
            }
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("Server port must be between 1 and 65535. Received: " + port);
            }
            this.serverAddress = address.trim();
            this.serverPort = port;
            this.rawClusters.clear(); // Clear any multi-cluster setup
            logger.debug("Configured single server: {}:{}. Cleared any existing cluster configurations.", this.serverAddress, this.serverPort);
            return this;
        }

        /**
         * Adds a Zabbix server/proxy cluster to the configuration.
         * Each call to this method adds a new cluster.
         * If {@link #server(String, int)} was called previously, this will override that setting.
         *
         * @param nodeAddresses A list of node address strings for this cluster.
         *                      Each string can be "address" or "address:port".
         *                      If port is not specified, default port 10051 will be used by the Cluster class.
         * @return This builder instance for chaining.
         * @throws IllegalArgumentException if nodeAddresses is null or empty.
         */
        public ZabbixSenderBuilder addCluster(List<String> nodeAddresses) {
            if (nodeAddresses == null || nodeAddresses.isEmpty()) {
                throw new IllegalArgumentException("Node addresses list for a cluster cannot be null or empty.");
            }
            this.rawClusters.add(new ArrayList<>(nodeAddresses)); // Add a copy
            this.serverAddress = null; // Clear single server setup if any
            logger.debug("Added cluster with nodes: {}. Single server configuration (if any) is cleared.", nodeAddresses);
            return this;
        }

        /**
         * Sets the timeout for socket operations (connect and read).
         *
         * @param timeoutSeconds The timeout duration in seconds. Defaults to 10.
         * @return This builder instance for chaining.
         * @throws IllegalArgumentException if timeout is not positive.
         */
        public ZabbixSenderBuilder timeoutSeconds(int timeoutSeconds) {
            if (timeoutSeconds <= 0) {
                throw new IllegalArgumentException("Timeout must be positive.");
            }
            this.timeoutSeconds = timeoutSeconds;
            return this;
        }

        /**
         * Sets whether to use IPv6 for connections.
         * Note: Actual IPv6 usage depends on JVM and OS network configuration.
         *
         * @param useIpv6 True to prefer IPv6, false otherwise. Defaults to false.
         * @return This builder instance for chaining.
         */
        public ZabbixSenderBuilder useIpv6(boolean useIpv6) {
            this.useIpv6 = useIpv6;
            return this;
        }

        /**
         * Sets the source IP address for outgoing connections.
         * Note: Actual binding to source IP depends on JVM and OS capabilities.
         *
         * @param sourceIp The source IP address. Defaults to null (OS chooses).
         * @return This builder instance for chaining.
         */
        public ZabbixSenderBuilder sourceIp(String sourceIp) {
            this.sourceIp = sourceIp;
            return this;
        }

        /**
         * Sets the maximum number of item values to send in a single request (chunk).
         *
         * @param chunkSize The chunk size. Defaults to 250.
         * @return This builder instance for chaining.
         * @throws IllegalArgumentException if chunkSize is not positive.
         */
        public ZabbixSenderBuilder chunkSize(int chunkSize) {
            if (chunkSize <= 0) {
                throw new IllegalArgumentException("Chunk size must be positive.");
            }
            this.chunkSize = chunkSize;
            return this;
        }

        /**
         * Sets whether to use zlib compression for sending data.
         *
         * @param useCompression True to enable compression, false otherwise. Defaults to false.
         * @return This builder instance for chaining.
         */
        public ZabbixSenderBuilder compression(boolean useCompression) {
            this.useCompression = useCompression;
            return this;
        }

        /**
         * Sets the path to a Zabbix agent configuration file.
         * If set, the builder will attempt to load server, port, timeout, and other
         * relevant settings from this file. This functionality is currently a placeholder.
         *
         * @param agentConfigPath Path to the zabbix_agentd.conf file.
         * @return This builder instance for chaining.
         */
        public ZabbixSenderBuilder agentConfigPath(String agentConfigPath) {
            this.agentConfigPath = agentConfigPath;
            return this;
        }

        /**
         * Builds the {@link ZabbixSender} instance with the configured settings.
         *
         * @return A new {@code ZabbixSender} instance.
         * @throws IllegalArgumentException if the configuration is invalid (e.g., bad node string format,
         *                                  negative timeout after potential config load).
         */
        public ZabbixSender build() {
            if (this.agentConfigPath != null && !this.agentConfigPath.trim().isEmpty()) {
                loadFromAgentConfig(this, this.agentConfigPath.trim());
                // After loadFromAgentConfig, re-validate critical parameters like timeout
                if (this.timeoutSeconds <= 0) {
                    throw new IllegalArgumentException("Timeout must be positive, even after loading from agent config.");
                }
                if (this.chunkSize <= 0) {
                     throw new IllegalArgumentException("Chunk size must be positive, even after loading from agent config.");
                }
            }

            // Final check: if no server/cluster explicitly added, and agent config didn't set one,
            // the constructor will default to 127.0.0.1:10051.
            if (this.rawClusters.isEmpty() && this.serverAddress == null) {
                 logger.debug("No specific server or cluster configured in builder and agentConfigPath not used (or didn't configure server). " +
                              "ZabbixSender will default to 127.0.0.1:10051.");
            }

            return new ZabbixSender(this);
        }
    }

    // Getter methods (optional, but can be useful for inspection or testing)

    /**
     * Gets the list of configured Zabbix server clusters.
     * @return An unmodifiable list of {@link Cluster} objects.
     */
    public List<Cluster> getClusters() {
        return clusters; // Already unmodifiable from constructor
    }

    /**
     * Gets the configured socket timeout in milliseconds.
     * @return The timeout in milliseconds.
     */
    public int getTimeoutMs() {
        return timeoutMs;
    }

    /**
     * Gets whether IPv6 is preferred for connections.
     * @return True if IPv6 is preferred, false otherwise.
     */
    public boolean isUseIpv6() {
        return useIpv6;
    }

    /**
     * Gets the configured source IP address.
     * @return The source IP address, or null if not set.
     */
    public String getSourceIp() {
        return sourceIp;
    }

    /**
     * Gets the maximum number of item values per sender request.
     * @return The chunk size.
     */
    public int getChunkSize() {
        return chunkSize;
    }

    /**
     * Gets whether zlib compression is enabled for sending data.
     * @return True if compression is enabled, false otherwise.
     */
    public boolean isUseCompression() {
        return useCompression;
    }

    /**
     * Sends a list of item values to the configured Zabbix server(s)/cluster(s).
     *
     * @param items The list of {@link ItemValue} objects to send.
     * @return An aggregated {@link TrapperResponse} summarizing the results from all chunks and clusters.
     * @throws ProcessingException if there's an error in request creation or response processing.
     * @throws IOException         if a network error occurs during sending to all clusters.
     * @throws IllegalArgumentException if items list is null or empty.
     */
    public TrapperResponse send(List<ItemValue> items) throws ProcessingException, IOException {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("Items list cannot be null or empty.");
        }
        logger.info("Attempting to send {} item(s) to Zabbix.", items.size());

        TrapperResponse aggregatedResponse = new TrapperResponse(0); // Chunk number will be updated per chunk
        Map<Node, List<TrapperResponse>> detailsMap = new HashMap<>();
        int totalProcessed = 0;
        int totalFailed = 0;
        double totalTimeSpent = 0.0;

        for (int i = 0; i < items.size(); i += this.chunkSize) {
            List<ItemValue> chunkItems = items.subList(i, Math.min(items.size(), i + this.chunkSize));
            int currentChunkNumber = (i / this.chunkSize) + 1;
            logger.debug("Processing chunk #{} with {} items.", currentChunkNumber, chunkItems.size());

            Map<Node, TrapperResponse> chunkResponsesByNode = sendChunkToAllClusters(chunkItems, currentChunkNumber);

            for (Map.Entry<Node, TrapperResponse> entry : chunkResponsesByNode.entrySet()) {
                Node node = entry.getKey();
                TrapperResponse chunkNodeResponse = entry.getValue();

                totalProcessed += chunkNodeResponse.getProcessed();
                totalFailed += chunkNodeResponse.getFailed();
                totalTimeSpent += chunkNodeResponse.getTimeSpentSeconds();

                detailsMap.computeIfAbsent(node, k -> new ArrayList<>()).add(chunkNodeResponse);
            }
            // Note: The Python client's logic for summing up processed/failed/total/timeSpent
            // in the main TrapperResponse was a bit different if multiple clusters were involved.
            // Here, we are summing all successful interactions.
        }

        // Update aggregatedResponse with summed values.
        // This is a simplified aggregation. A more detailed one might need to
        // consider how "total" is defined if different chunks go to different clusters.
        // For now, reflecting the sum of all processing efforts.
        // The Python client's main TrapperResponse seemed to reflect the *last* successful chunk's info,
        // which might not be ideal for an overall summary. Here we sum.
        try {
            // Create a dummy response map to update the aggregatedResponse fields
            // This is a bit of a hack. TrapperResponse could benefit from direct setters for its totals.
            Map<String, Object> summaryInfo = new HashMap<>();
            summaryInfo.put("info", String.format("processed: %d; failed: %d; total: %d; seconds spent: %f",
                    totalProcessed, totalFailed, totalProcessed + totalFailed, totalTimeSpent));
            aggregatedResponse.addResponse(summaryInfo, items.size() / this.chunkSize + (items.size() % this.chunkSize > 0 ? 1 : 0) );
        } catch (IllegalArgumentException e) {
            // This might happen if the totals are 0 and addResponse expects non-zero totals based on its internal parsing.
            // This indicates a need to refine how aggregatedResponse is populated or make TrapperResponse more flexible.
            logger.warn("Could not update aggregatedResponse totals directly, may need TrapperResponse refinement: " + e.getMessage());
            // Manually set if addResponse fails due to parsing logic not fitting this aggregation
            // For now, this direct manipulation is not possible as fields are private and no direct setters for totals.
        }


        aggregatedResponse.setDetails(detailsMap);
        logger.info("Finished sending all items. Aggregated processed: {}, Aggregated failed: {}. Details available for {} nodes.",
                aggregatedResponse.getProcessed(), aggregatedResponse.getFailed(), detailsMap.size());
        return aggregatedResponse;
    }

    /**
     * Sends a single item value.
     *
     * @param host  Hostname of the item.
     * @param key   Item key.
     * @param value Item value.
     * @param clock Optional timestamp (Unix time).
     * @param ns    Optional nanoseconds for the timestamp.
     * @return {@link TrapperResponse} from the server.
     * @throws ProcessingException if there's an error in request creation or response processing.
     * @throws IOException         if a network error occurs.
     */
    public TrapperResponse sendValue(String host, String key, String value, Long clock, Integer ns) throws ProcessingException, IOException {
        ItemValue item = ItemValue.newBuilder()
                .host(host)
                .key(key)
                .value(value)
                .clock(clock)
                .ns(ns)
                .build();
        return send(Collections.singletonList(item));
    }

    /**
     * Sends a single item value without specific timestamp.
     *
     * @param host  Hostname of the item.
     * @param key   Item key.
     * @param value Item value.
     * @return {@link TrapperResponse} from the server.
     * @throws ProcessingException if there's an error in request creation or response processing.
     * @throws IOException         if a network error occurs.
     */
    public TrapperResponse sendValue(String host, String key, String value) throws ProcessingException, IOException {
        return sendValue(host, key, value, null, null);
    }

    /**
     * Creates the JSON request string for Zabbix Sender data.
     *
     * @param items List of {@link ItemValue} to include in the request.
     * @return JSON string payload.
     */
    private String createSenderRequest(List<ItemValue> items) {
        List<Map<String, Object>> dataList = items.stream()
                .map(ItemValue::toJsonMap)
                .collect(Collectors.toList());

        Map<String, Object> requestMap = new HashMap<>();
        requestMap.put("request", "sender data");
        requestMap.put("data", dataList);
        // Optional: Add clock for the whole batch if all items share the same timestamp
        // and ns is not used, or if server supports batch clock. Zabbix typically uses per-item clock.

        return gson.toJson(requestMap);
    }

    /**
     * Sends a single chunk of items to all configured clusters, trying one node per cluster.
     *
     * @param chunkItems        List of items in the current chunk.
     * @param currentChunkNumber The number of the current chunk (for logging/response).
     * @return A map of Node to its TrapperResponse for successfully contacted nodes.
     * @throws ProcessingException if request creation fails.
     * @throws IOException         if sending to all clusters fails fundamentally (e.g. no clusters defined).
     */
    private Map<Node, TrapperResponse> sendChunkToAllClusters(List<ItemValue> chunkItems, int currentChunkNumber) throws ProcessingException, IOException {
        String requestJson = createSenderRequest(chunkItems);
        byte[] packet = ZabbixProtocol.createPacket(requestJson, this.useCompression);

        Map<Node, TrapperResponse> responsesByNode = new HashMap<>();
        boolean atLeastOneClusterAttempted = false;

        for (Cluster cluster : this.clusters) {
            atLeastOneClusterAttempted = true;
            try {
                NodeResponsePair activeNodeResponse = sendToCluster(cluster, packet, currentChunkNumber);
                if (activeNodeResponse != null) { // Should not be null if sendToCluster succeeds as it throws otherwise
                    responsesByNode.put(activeNodeResponse.node, activeNodeResponse.response);
                    // If we want to stop after first successful cluster, we can break here.
                    // Python client sends to all (active server in each HA group).
                    // Current logic: try one node from each cluster.
                }
            } catch (IOException e) {
                // This exception means this specific cluster failed. Log and continue to next cluster.
                logger.warn("Failed to send chunk #{} to cluster {} due to: {}. Trying next cluster if available.",
                        currentChunkNumber, cluster.getNodes(), e.getMessage());
            }
        }

        if (!atLeastOneClusterAttempted) {
            throw new IOException("No Zabbix server/clusters configured to send data to.");
        }
        if (responsesByNode.isEmpty() && atLeastOneClusterAttempted) {
            // This means all clusters failed for this chunk.
            // The send() method will aggregate these empty results, effectively showing 0 processed for this chunk.
            logger.error("Chunk #{} could not be sent to any configured Zabbix server/cluster.", currentChunkNumber);
        }

        return responsesByNode;
    }

    /**
     * Sends a pre-formed packet to one node in the given cluster.
     * Tries nodes in the cluster sequentially until one succeeds.
     *
     * @param cluster            The cluster to send data to.
     * @param packet             The byte packet to send.
     * @param currentChunkNumber The current chunk number for response construction.
     * @return A NodeResponsePair if successful.
     * @throws IOException if all nodes in the cluster fail or other IO errors occur.
     */
    private NodeResponsePair sendToCluster(Cluster cluster, byte[] packet, int currentChunkNumber) throws IOException {
        if (cluster.getNodes() == null || cluster.getNodes().isEmpty()) {
            logger.warn("Cluster has no nodes defined. Skipping.");
            throw new IOException("Cluster definition is empty (no nodes to send to).");
        }

        List<Node> nodesToTry = cluster.getNodes(); // In future, this list could be reordered based on active status

        for (int nodeIndex = 0; nodeIndex < nodesToTry.size(); nodeIndex++) {
            Node node = nodesToTry.get(nodeIndex);
            Socket socket = null; // Initialized by socketFactory
            try {
                socket = this.socketFactory.createSocket(); // Use the factory

                if (this.sourceIp != null && !this.sourceIp.trim().isEmpty()) {
                    try {
                        // Socket should be unbound before calling bind.
                        // If socketFactory returns a connected socket (mock), this part might be tricky.
                        // For real sockets, it's fine.
                        if (!socket.isBound()) {
                           socket.bind(new InetSocketAddress(InetAddress.getByName(this.sourceIp), 0));
                           logger.debug("Socket bound to source IP: {}", this.sourceIp);
                        } else {
                            logger.debug("Socket from factory is already bound, skipping explicit bind to source IP.");
                        }
                    } catch (IOException e) {
                        logger.warn("Failed to bind socket to source IP {}: {}. Proceeding as is. Error: {}", this.sourceIp, e.getMessage(),e.toString());
                        // If bind fails, and the socket was created fresh, it might still be usable with OS default.
                        // If the mock socket factory provides a pre-bound socket, this might not be an issue.
                    }
                }

                InetSocketAddress socketAddress = new InetSocketAddress(node.getAddress(), node.getPort());
                logger.debug("Attempting to connect to Zabbix node {} (Timeout: {}ms) for chunk #{}", node, this.timeoutMs, currentChunkNumber);
                socket.connect(socketAddress, this.timeoutMs);
                socket.setSoTimeout(this.timeoutMs); // Read timeout

                logger.info("Successfully connected to Zabbix node {} for sending data (chunk #{}).", node, currentChunkNumber);

                try (OutputStream outputStream = socket.getOutputStream();
                     InputStream inputStream = socket.getInputStream()) {

                    outputStream.write(packet);
                    outputStream.flush();
                    logger.debug("Packet sent to node {}.", node);

                    String responseJsonString = ZabbixProtocol.parseSynchronousPacket(inputStream);
                    logger.debug("Received response from node {}: {}", node, responseJsonString);

                    Map<String, Object> responseMap = gson.fromJson(responseJsonString, new TypeToken<Map<String, Object>>() {}.getType());

                    Object responseObject = responseMap.get("response");
                    if (responseObject == null || !"success".equals(String.valueOf(responseObject).toLowerCase())) {
                        String errorLog = String.format("Received non-success response from Zabbix node %s: %s", node, responseJsonString);
                        logger.error(errorLog);
                        // Simplified redirect handling: log and treat as error for this node.
                        if (responseJsonString.toLowerCase().contains("redirect")) {
                             logger.warn("Response from node {} indicates a redirect. This version does not support automatic redirect handling. Details: {}", node, responseJsonString);
                             // Continue to next node by throwing an IOException for this attempt
                             throw new IOException("Redirect received from node " + node + ", not handled.");
                        }
                        throw new ProcessingException("Zabbix Sender response indicates failure: " + responseJsonString);
                    }

                    TrapperResponse trapperResponse = new TrapperResponse(currentChunkNumber);
                    trapperResponse.addResponse(responseMap, currentChunkNumber); // Populates from "info" field

                    if (nodeIndex > 0) {
                        // This means a fallback node in the cluster was successful.
                        // Python client would reorder. Here, just log.
                        logger.info("Successfully sent data to a fallback node {} in cluster after previous failures.", node);
                    }
                    return new NodeResponsePair(node, trapperResponse);
                }

            } catch (SocketTimeoutException e) {
                logger.warn("Socket timeout while communicating with Zabbix node {}: {}", node, e.getMessage());
                // Continue to next node
            } catch (IOException e) { // Includes ConnectException
                logger.warn("Failed to send/receive data with Zabbix node {}: {}", node, e.getMessage());
                // Continue to next node
            } catch (ProcessingException e) { // From ZabbixProtocol.parse or non-success response
                 logger.warn("Processing error with Zabbix node {}: {}", node, e.getMessage());
                 // This means we connected and got some data, but it was bad or an error response.
                 // Depending on policy, we might not want to try other nodes in the cluster if we get an explicit server error.
                 // For now, let's assume we try other nodes if one gives a processing error (e.g. malformed packet from server).
            }
            finally {
                if (socket != null && !socket.isClosed()) {
                    try {
                        socket.close();
                    } catch (IOException e) {
                        logger.warn("Error closing socket to node {}: {}", node, e.getMessage());
                    }
                }
            }
        }

        // If loop completes, all nodes in this cluster failed
        String errorMsg = "Failed to send data to all nodes in cluster: " + nodesToTry.stream().map(Node::toString).collect(Collectors.joining(", "));
        logger.error(errorMsg);
        throw new IOException(errorMsg);
    }
}
