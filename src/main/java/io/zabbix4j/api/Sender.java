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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Sends data to Zabbix Server or Zabbix Proxy using the Zabbix Sender protocol.
 * This class supports sending data in chunks, using Zabbix agent configuration files
 * for server/proxy details, and optional TLS encryption via a socket wrapper.
 * <p>
 * Use the {@link Builder} to construct instances of this class.
 *
 * @author CSJ
 */
public final class Sender {

    private static final Logger logger = LoggerFactory.getLogger(Sender.class);

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
    private final BiFunction<Socket, Map<String, String>, Socket> socketWrapper;
    private final boolean compressionEnabled;
    private final Map<String, String> tlsConfig;

    private Sender(Builder builder) {
        this.clusters = Collections.unmodifiableList(new ArrayList<>(builder.clusters));
        this.timeout = builder.timeout;
        this.sourceIp = builder.sourceIp;
        this.chunkSize = builder.chunkSize;
        this.socketWrapper = builder.socketWrapper;
        this.compressionEnabled = builder.compressionEnabled;
        this.tlsConfig = Collections.unmodifiableMap(new HashMap<>(builder.tlsConfig));

        logger.debug("Sender initialized. Clusters: {}, Timeout: {}ms, SourceIP: {}, ChunkSize: {}, Compression: {}, TLS Config Keys: {}",
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
    public BiFunction<Socket, Map<String, String>, Socket> getSocketWrapper() { return socketWrapper; }
    public boolean isCompressionEnabled() { return compressionEnabled; }
    public Map<String, String> getTlsConfig() { return tlsConfig; }

    public TrapperResponse send(List<ItemValue> items) throws ZabbixProcessingException {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("Items list cannot be null or empty.");
        }
        for (ItemValue item : items) {
            if (item == null) {
                throw new IllegalArgumentException("ItemValue within the list cannot be null.");
            }
        }

        TrapperResponse aggregatedResponse = new TrapperResponse();
        int totalItems = items.size();

        for (int i = 0; i < totalItems; i += this.chunkSize) {
            List<ItemValue> chunk = items.subList(i, Math.min(totalItems, i + this.chunkSize));
            logger.debug("Processing chunk {}/{} ({} items)", (i / this.chunkSize) + 1, (totalItems + this.chunkSize - 1) / this.chunkSize, chunk.size());

            boolean chunkSentSuccessfullyToAnyCluster = false;
            TrapperResponse chunkResponse = new TrapperResponse(); 

            for (Cluster cluster : this.clusters) {
                for (Node node : cluster.getNodes()) {
                    logger.info("Attempting to send chunk to node: {}:{}", node.getAddress(), node.getPort());
                    Socket clientSocket = null;
                    try {
                        clientSocket = new Socket();
                        clientSocket.setSoTimeout(this.timeout);

                        if (this.sourceIp != null) {
                            logger.debug("Binding to source IP: {}", this.sourceIp);
                            SocketAddress bindAddress = new InetSocketAddress(this.sourceIp, 0);
                            clientSocket.bind(bindAddress);
                        }

                        SocketAddress targetAddress = new InetSocketAddress(node.getAddress(), node.getPort());
                        clientSocket.connect(targetAddress, this.timeout);

                        if (this.socketWrapper != null) {
                            logger.debug("Applying socket wrapper to {}:{}", node.getAddress(), node.getPort());
                            clientSocket = this.socketWrapper.apply(clientSocket, this.tlsConfig);
                        }

                        List<Map<String, Object>> itemDataList = new ArrayList<>();
                        for (ItemValue itemValue : chunk) {
                            itemDataList.add(itemValue.toMap());
                        }
                        Map<String, Object> requestPayloadMap = new HashMap<>();
                        requestPayloadMap.put("request", "sender data");
                        requestPayloadMap.put("data", new JSONArray(itemDataList));
                        
                        String jsonPayloadString = new JSONObject(requestPayloadMap).toString();
                        logger.trace("Sending JSON payload: {}", jsonPayloadString);

                        byte[] requestPacket = ZabbixProtocol.createPacket(jsonPayloadString, this.compressionEnabled);

                        try (OutputStream out = clientSocket.getOutputStream();
                             InputStream in = clientSocket.getInputStream()) {

                            out.write(requestPacket);
                            out.flush();
                            logger.debug("Chunk sent to {}:{}, awaiting response.", node.getAddress(), node.getPort());

                            String rawResponse = ZabbixProtocol.parsePacket(in);
                            logger.debug("Raw response from {}:{}: {}", node.getAddress(), node.getPort(), rawResponse);

                            JSONObject jsonResponse = new JSONObject(rawResponse);
                            String responseStatus = jsonResponse.optString("response", "failed");

                            if ("success".equalsIgnoreCase(responseStatus)) {
                                String info = jsonResponse.optString("info");
                                chunkResponse.parseAndAdd(info); 
                                chunkSentSuccessfullyToAnyCluster = true;
                                logger.info("Chunk successfully sent to {}:{}. Info: {}", node.getAddress(), node.getPort(), info);
                                break; 
                            } else {
                                String info = jsonResponse.optString("info", "No specific error info from Zabbix.");
                                if (jsonResponse.has("redirect")) {
                                    JSONObject redirect = jsonResponse.getJSONObject("redirect");
                                    logger.warn("Received redirect from {}:{} to {}. Redirection not automatically handled by this Sender version. Info: {}", node.getAddress(), node.getPort(), redirect.optString("address"), info);
                                } else {
                                    logger.error("Failed to send chunk to {}:{}. Response: {}, Info: {}", node.getAddress(), node.getPort(), responseStatus, info);
                                }
                            }
                        } 
                    } catch (SocketTimeoutException e) {
                        logger.warn("Socket timeout connecting or sending to {}:{}: {}", node.getAddress(), node.getPort(), e.getMessage());
                    } catch (ConnectException e) {
                        logger.warn("Connection refused by {}:{}: {}", node.getAddress(), node.getPort(), e.getMessage());
                    } catch (UnknownHostException e) {
                        logger.warn("Unknown host: {}: {}", node.getAddress(), e.getMessage());
                    } catch (IOException e) {
                        logger.warn("IOException with {}:{}: {}", node.getAddress(), node.getPort(), e.getMessage(), e);
                    } catch (JSONException e) {
                        logger.error("Error parsing JSON response from {}:{}: {}", node.getAddress(), node.getPort(), e.getMessage(), e);
                    } catch (ZabbixProcessingException e) { 
                        logger.warn("Zabbix processing error with {}:{}: {}", node.getAddress(), node.getPort(), e.getMessage(), e);
                    } finally {
                        if (clientSocket != null && !clientSocket.isClosed()) {
                            try {
                                clientSocket.close();
                            } catch (IOException e) {
                                logger.warn("Error closing socket to {}:{}: {}", node.getAddress(), node.getPort(), e.getMessage());
                            }
                        }
                    }
                    if (chunkSentSuccessfullyToAnyCluster) break; 
                } 
                if (chunkSentSuccessfullyToAnyCluster) break; 
            } 

            if (chunkSentSuccessfullyToAnyCluster) {
                aggregatedResponse.parseAndAdd(String.format("processed: %d; failed: %d; total: %d; seconds spent: %f",
                        chunkResponse.getProcessed(), chunkResponse.getFailed(), chunkResponse.getTotal(), chunkResponse.getTimeSpent()));
            } else {
                logger.error("Chunk {}/{} ({} items) failed to send to any configured server/proxy.",
                        (i / this.chunkSize) + 1, (totalItems + this.chunkSize - 1) / this.chunkSize, chunk.size());
                aggregatedResponse.parseAndAdd(String.format("processed: 0; failed: %d; total: %d; seconds spent: 0.0",
                        chunk.size(), chunk.size()));
            }
        } 
        return aggregatedResponse;
    }

    public TrapperResponse sendValue(String host, String key, String value) throws ZabbixProcessingException {
        ItemValue item = new ItemValue(host, key, value);
        return send(Collections.singletonList(item));
    }

    public TrapperResponse sendValue(String host, String key, String value, Long clock, Integer ns) throws ZabbixProcessingException {
        ItemValue item = new ItemValue(host, key, value, clock, ns);
        return send(Collections.singletonList(item));
    }

    /**
     * Holds configuration data parsed from a Zabbix agent configuration file.
     * Package-private to be accessible by AsyncSender.Builder.
     */
    static class SenderConfigData { // Changed from private static
        private final List<Cluster> parsedClusters = new ArrayList<>();
        private String parsedSourceIp = null;
        private final Map<String, String> parsedTlsConfig = new HashMap<>();

        public List<Cluster> getParsedClusters() {
            return Collections.unmodifiableList(parsedClusters);
        }

        public String getParsedSourceIp() {
            return parsedSourceIp;
        }

        public Map<String, String> getParsedTlsConfig() {
            return Collections.unmodifiableMap(parsedTlsConfig);
        }
    }

    public static final class Builder {
        private List<Cluster> clusters = new ArrayList<>();
        private Integer timeout; 
        private String sourceIp;
        private Integer chunkSize; 
        private BiFunction<Socket, Map<String, String>, Socket> socketWrapper;
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

        /**
         * Sets a custom socket wrapper function. This function will be applied to the
         * connected socket before sending data, allowing for SSL/TLS or other custom
         * socket manipulations.
         * <p>
         * The BiFunction takes the connected {@link Socket} and the current {@code tlsConfig} map
         * as input and should return a {@code Socket} (which can be the original wrapped
         * socket or a new one). If a new socket instance is returned, the wrapper is
         * responsible for managing the original socket if necessary. The Sender will manage
         * (close) the socket instance it ultimately uses.
         *
         * @param socketWrapper The function to wrap the socket.
         * @return This builder instance for chaining.
         */
        public Builder socketWrapper(BiFunction<Socket, Map<String, String>, Socket> socketWrapper) {
            this.socketWrapper = socketWrapper;
            return this;
        }

        public Builder enableCompression(boolean enabled) {
            this.compressionEnabled = enabled;
            return this;
        }
        
        public Builder tlsConfig(String key, String value) {
            if (key == null || key.trim().isEmpty()) throw new IllegalArgumentException("TLS config key cannot be null or empty.");
            if (value == null) throw new IllegalArgumentException("TLS config value cannot be null. To remove a key, build and modify map or handle in wrapper.");
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

        public Sender build() throws ZabbixProcessingException {
            if (agentConfigPath != null) {
                loadConfigurationFromAgent();
            }

            if (this.timeout == null) this.timeout = DEFAULT_TIMEOUT_MS;
            if (this.chunkSize == null) this.chunkSize = DEFAULT_CHUNK_SIZE;
            if (this.compressionEnabled == null) this.compressionEnabled = false;

            if (this.clusters.isEmpty()) {
                throw new IllegalArgumentException("No Zabbix servers/proxies defined. Configure via server(), cluster(), or agentConfigPath().");
            }
            return new Sender(this);
        }

        private void loadConfigurationFromAgent() throws ZabbixProcessingException {
            logger.info("Loading sender configuration from agent config file: {}", agentConfigPath);
            SenderConfigData configData = parseAgentConfiguration(agentConfigPath);

            for (Map.Entry<String, String> entry : configData.getParsedTlsConfig().entrySet()) { // Use getter
                this.tlsConfig.putIfAbsent(entry.getKey(), entry.getValue());
            }

            if (!serverConfigFromBuilder && !configData.getParsedClusters().isEmpty()) { // Use getter
                logger.debug("Using ServerActive/Server from config file: {}", configData.getParsedClusters());
                this.clusters = new ArrayList<>(configData.getParsedClusters()); // Ensure mutable copy for builder
            } else if (serverConfigFromBuilder && !configData.getParsedClusters().isEmpty()){
                 logger.debug("Server/cluster configuration was set explicitly on builder, ignoring ServerActive/Server from config file.");
            }

            if (!sourceIpFromBuilder && configData.getParsedSourceIp() != null) { // Use getter
                logger.debug("Using SourceIP from config file: {}", configData.getParsedSourceIp());
                this.sourceIp = configData.getParsedSourceIp();
            } else if (sourceIpFromBuilder && configData.getParsedSourceIp() != null) {
                logger.debug("SourceIP was set explicitly on builder, ignoring SourceIP from config file.");
            }
        }
    }

    static SenderConfigData parseAgentConfiguration(String configPath) throws ZabbixProcessingException {
        SenderConfigData configData = new SenderConfigData();
        Pattern keyValuePattern = Pattern.compile("^\\s*([a-zA-Z0-9_]+)\\s*=\\s*(.*)$");
        Pattern serverPattern = Pattern.compile("([^,;]+(?:\\s*;\\s*[^,;]+)*)");

        try (BufferedReader reader = Files.newBufferedReader(Paths.get(configPath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                Matcher matcher = keyValuePattern.matcher(line);
                if (!matcher.matches()) {
                    logger.warn("Skipping malformed line in config file {}: {}", configPath, line);
                    continue;
                }
                String key = matcher.group(1);
                String value = matcher.group(2).trim();

                switch (key) {
                    case "ServerActive":
                    case "Server": 
                        if (value.isEmpty()) break;
                        Matcher serverMatcher = serverPattern.matcher(value);
                        while(serverMatcher.find()){
                            String clusterString = serverMatcher.group(1);
                            List<String> nodeStrings = Arrays.stream(clusterString.split(";"))
                                .map(String::trim)
                                .filter(s -> !s.isEmpty())
                                .collect(Collectors.toList());
                            if(!nodeStrings.isEmpty()){
                                try {
                                    configData.parsedClusters.add(new Cluster(nodeStrings));
                                } catch (IllegalArgumentException e) {
                                    logger.warn("Skipping invalid cluster definition '{}' from {}: {}", clusterString, key, e.getMessage());
                                }
                            }
                        }
                        break;
                    case "SourceIP":
                        configData.parsedSourceIp = value.isEmpty() ? null : value;
                        break;
                    case "TLSConnect":
                    case "TLSCAFile":
                    case "TLSCertFile":
                    case "TLSKeyFile":
                    case "TLSCipherAll":
                    case "TLSCipherCert":
                    case "TLSCipherPSK":
                    case "TLSPSKIdentity":
                    case "TLSPSKFile":
                        if (!value.isEmpty()) {
                            configData.parsedTlsConfig.put(key, value);
                        }
                        break;
                    default:
                        break;
                }
            }
        } catch (IOException e) {
            logger.error("Failed to read or parse Zabbix agent configuration file {}: {}", configPath, e.getMessage(), e);
            throw new ZabbixProcessingException("Failed to read or parse Zabbix agent configuration file: " + configPath, e);
        }
        return configData;
    }
}
