package io.zabbix4j.getter;

import io.zabbix4j.api.common.ZabbixProtocol;
import io.zabbix4j.api.exceptions.ProcessingException;
import io.zabbix4j.api.types.AgentResponse;
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

/**
 * Retrieves item values from a Zabbix Agent using the Zabbix Getter protocol.
 * <p>
 * This class allows fetching specific item values by connecting to a Zabbix Agent.
 * Configuration can be done via the {@link ZabbixGetterBuilder}.
 * Instances are thread-safe for concurrent use as each {@code get} call uses a new socket.
 * </p>
 *
 * @author CSJ
 */
public class ZabbixGetter {

    private static final Logger logger = LoggerFactory.getLogger(ZabbixGetter.class);

    private final String agentHost;
    private final int agentPort;
    private final int timeoutMs;
    private final boolean useIpv6; // Currently informational
    private final String sourceIp;
    private final SocketFactory socketFactory; // For testing

    // TODO: Add fields for TLS/socket wrapping (socket_wrapper) if needed in future.

    /**
     * Functional interface for creating sockets, allowing for mock injection in tests.
     */
    @FunctionalInterface
    interface SocketFactory {
        Socket createSocket() throws IOException;
    }

    /**
     * Private constructor. Use {@link ZabbixGetterBuilder} to create instances.
     * This is the main constructor called by the builder.
     *
     * @param builder The builder instance with configuration.
     */
    private ZabbixGetter(ZabbixGetterBuilder builder) {
        this(builder, Socket::new); // Default socket factory
    }

    /**
     * Package-private constructor for testing, allowing SocketFactory injection.
     * @param builder Builder instance.
     * @param socketFactory Factory to create sockets.
     */
    ZabbixGetter(ZabbixGetterBuilder builder, SocketFactory socketFactory) {
        this.agentHost = builder.agentHost;
        this.agentPort = builder.agentPort;
        this.timeoutMs = builder.timeoutSeconds * 1000;
        this.useIpv6 = builder.useIpv6;
        this.sourceIp = builder.sourceIp;

        logger.info("ZabbixGetter initialized. Agent: {}:{}, Timeout: {}ms, Source IP: {}, IPv6: {}",
                this.agentHost, this.agentPort, this.timeoutMs,
                this.sourceIp != null ? this.sourceIp : "OS default", this.useIpv6);
    }

    /**
     * Retrieves the value of a specific item key from the configured Zabbix Agent.
     *
     * @param itemKey The Zabbix item key to query (e.g., "agent.ping", "system.cpu.load[all,avg1]").
     * @return An {@link AgentResponse} object containing the parsed response from the agent.
     * @throws ProcessingException      if there is an error during packet creation, sending,
     *                                  receiving, parsing the response, or if a connection/timeout error occurs.
     * @throws IOException              for other underlying I/O errors not covered by ProcessingException (should be rare).
     * @throws IllegalArgumentException if {@code itemKey} is null or empty.
     */
    public AgentResponse get(String itemKey) throws ProcessingException, IOException {
        if (itemKey == null || itemKey.trim().isEmpty()) {
            throw new IllegalArgumentException("Item key cannot be null or empty.");
        }
        String trimmedItemKey = itemKey.trim();
        logger.debug("Requesting item key '{}' from agent {}:{}", trimmedItemKey, agentHost, agentPort);

        byte[] packet;
        try {
            // Getter protocol does not use compression.
            packet = ZabbixProtocol.createPacket(trimmedItemKey, false);
        } catch (ProcessingException e) { // createPacket can throw ProcessingException for empty payload, already handled
            logger.error("Failed to create Zabbix protocol packet for key '{}'", trimmedItemKey, e);
            throw e; // Re-throw as it's a processing error
        } catch (IOException e) { // createPacket can throw IOException if compression has issues (not applicable here)
            // This path should ideally not be hit for createPacket with compression=false
            logger.error("Unexpected IOException during packet creation for key '{}'", trimmedItemKey, e);
            throw new ProcessingException("Failed to create Zabbix protocol packet: " + e.getMessage(), e);
        }

        // Use try-with-resources for the socket, created by the factory
        try (Socket socket = this.socketFactory.createSocket()) {
            if (this.sourceIp != null && !this.sourceIp.trim().isEmpty()) {
                try {
                    // Ensure socket is not already bound if provided by a mock factory in a bound state
                    if (!socket.isBound()) {
                       socket.bind(new InetSocketAddress(InetAddress.getByName(this.sourceIp), 0));
                    }
                    logger.debug("Socket bound to source IP: {}", this.sourceIp);
                } catch (IOException e) {
                    logger.warn("Failed to bind socket to source IP {}: {}. Proceeding without source IP binding.", this.sourceIp, e.getMessage(), e);
                    // Fallback: socket will use OS default source IP if bind fails.
                }
            }

            InetSocketAddress address = new InetSocketAddress(this.agentHost, this.agentPort);
            logger.debug("Connecting to Zabbix agent at {} for key '{}' (Timeout: {}ms)", address, trimmedItemKey, this.timeoutMs);

            socket.connect(address, this.timeoutMs);
            socket.setSoTimeout(this.timeoutMs); // For read operations

            logger.debug("Connected to Zabbix agent at {}:{} for key '{}'", agentHost, agentPort, trimmedItemKey);

            try (OutputStream outputStream = socket.getOutputStream();
                 InputStream inputStream = socket.getInputStream()) {

                outputStream.write(packet);
                outputStream.flush();
                logger.debug("Packet sent to agent for key '{}'.", trimmedItemKey);

                String rawAgentResponse = ZabbixProtocol.parseSynchronousPacket(inputStream);
                logger.debug("Raw response from agent for key '{}': {}", trimmedItemKey, rawAgentResponse);

                return new AgentResponse(rawAgentResponse);
            }

        } catch (SocketTimeoutException e) {
            String errorMsg = String.format("Timeout connecting to or reading from Zabbix agent %s:%d for key '%s'. Timeout: %dms",
                    agentHost, agentPort, trimmedItemKey, timeoutMs);
            logger.warn(errorMsg, e);
            throw new ProcessingException(errorMsg, e);
        } catch (ConnectException e) {
            String errorMsg = String.format("Connection refused by Zabbix agent %s:%d for key '%s'. Ensure agent is running and reachable.",
                    agentHost, agentPort, trimmedItemKey);
            logger.warn(errorMsg, e);
            throw new ProcessingException(errorMsg, e);
        } catch (IOException e) {
            String errorMsg = String.format("IOException during communication with Zabbix agent %s:%d for key '%s': %s",
                    agentHost, agentPort, trimmedItemKey, e.getMessage());
            logger.warn(errorMsg, e);
            // As per requirement, wrap other IOExceptions in ProcessingException
            throw new ProcessingException(errorMsg, e);
        }
        // ProcessingException from ZabbixProtocol.parseSynchronousPacket will propagate directly.
    }


    /**
     * Builder class for {@link ZabbixGetter}.
     * Provides a fluent API for constructing {@code ZabbixGetter} instances with custom configurations.
     */
    public static class ZabbixGetterBuilder {
        private String agentHost = "127.0.0.1";
        private int agentPort = 10050;
        private int timeoutSeconds = 10;
        private boolean useIpv6 = false;
        private String sourceIp = null;

        /**
         * Sets the Zabbix Agent hostname or IP address.
         *
         * @param agentHost The agent's address. Defaults to "127.0.0.1".
         * @return This builder instance for chaining.
         */
        public ZabbixGetterBuilder host(String agentHost) {
            if (agentHost == null || agentHost.trim().isEmpty()) {
                throw new IllegalArgumentException("Agent host cannot be null or empty.");
            }
            this.agentHost = agentHost.trim();
            return this;
        }

        /**
         * Sets the Zabbix Agent port.
         *
         * @param agentPort The agent's port. Defaults to 10050.
         * @return This builder instance for chaining.
         * @throws IllegalArgumentException if port is out of valid range (1-65535).
         */
        public ZabbixGetterBuilder port(int agentPort) {
            if (agentPort < 1 || agentPort > 65535) {
                throw new IllegalArgumentException("Agent port must be between 1 and 65535. Received: " + agentPort);
            }
            this.agentPort = agentPort;
            return this;
        }

        /**
         * Sets the timeout for socket operations (connect and read).
         *
         * @param timeoutSeconds The timeout duration in seconds. Defaults to 10.
         * @return This builder instance for chaining.
         * @throws IllegalArgumentException if timeout is not positive.
         */
        public ZabbixGetterBuilder timeoutSeconds(int timeoutSeconds) {
            if (timeoutSeconds <= 0) {
                throw new IllegalArgumentException("Timeout must be positive.");
            }
            this.timeoutSeconds = timeoutSeconds;
            return this;
        }

        /**
         * Sets whether to prefer IPv6 for connections.
         * Note: Actual IPv6 usage depends on JVM and OS network configuration,
         * as {@link InetSocketAddress} handles resolution.
         *
         * @param useIpv6 True to indicate preference for IPv6, false otherwise. Defaults to false.
         * @return This builder instance for chaining.
         */
        public ZabbixGetterBuilder useIpv6(boolean useIpv6) {
            this.useIpv6 = useIpv6;
            return this;
        }

        /**
         * Sets the source IP address for outgoing connections.
         * Note: Actual binding to source IP depends on JVM and OS capabilities and permissions.
         *
         * @param sourceIp The source IP address. Defaults to null (OS chooses).
         * @return This builder instance for chaining.
         */
        public ZabbixGetterBuilder sourceIp(String sourceIp) {
            this.sourceIp = (sourceIp != null && !sourceIp.trim().isEmpty()) ? sourceIp.trim() : null;
            return this;
        }

        /**
         * Builds the {@link ZabbixGetter} instance with the configured settings.
         *
         * @return A new {@code ZabbixGetter} instance.
         * @throws IllegalArgumentException if the configuration is invalid (e.g., due to earlier validation in setters).
         */
        public ZabbixGetter build() {
            // Validations are mostly handled by setters.
            // Re-check critical ones here if setters could be bypassed or state becomes invalid.
            if (agentHost == null || agentHost.trim().isEmpty()) { // Should be caught by host() but good for direct build()
                throw new IllegalArgumentException("Agent host cannot be null or empty.");
            }
            if (timeoutSeconds <= 0) { // Should be caught by timeoutSeconds()
                throw new IllegalArgumentException("Timeout must be positive.");
            }
            return new ZabbixGetter(this);
        }
    }
}
