package io.zabbix4j.api.sync;

import io.zabbix4j.api.dto.AgentResponse;
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
import java.util.Objects;

/**
 * Synchronous client for retrieving item values from a Zabbix Agent using the Zabbix agent protocol.
 * <p>
 * Use the {@link Builder} to construct instances of this class.
 * </p>
 * Example:
 * <pre>{@code
 * ZabbixGetterSync getter = new ZabbixGetterSync.Builder()
 * .host("zabbix-agent.example.com")
 * .port(10050)
 * .timeout(5) // seconds
 * .build();
 *
 * try {
 * AgentResponse response = getter.get("agent.ping");
 * if (!response.hasError()) {
 * System.out.println("Agent ping response: " + response.getValue());
 * } else {
 * System.err.println("Agent error: " + response.getError());
 * }
 * } catch (CommunicationException | ProcessingException e) {
 * System.err.println("Failed to get item value: " + e.getMessage());
 * }
 * }</pre>
 *
 * @author ShortRoundDev
 */
public final class ZabbixGetterSync {

    private static final Logger logger = LoggerFactory.getLogger(ZabbixGetterSync.class);

    private final String agentHost;
    private final int agentPort;
    private final int timeoutMillis;
    private final boolean useIpv6; // Conceptual, as Java's Socket handles resolution.
    private final InetAddress sourceIpAddress;

    private ZabbixGetterSync(Builder builder) {
        this.agentHost = Objects.requireNonNull(builder.host, "Agent host cannot be null.");
        this.agentPort = builder.port;
        this.timeoutMillis = builder.timeoutSeconds > 0 ? builder.timeoutSeconds * 1000 : 0; // 0 for infinite per Socket spec
        this.useIpv6 = builder.useIpv6;
        this.sourceIpAddress = builder.sourceIpAddress;
    }

    /**
     * Retrieves a single item value from the configured Zabbix Agent.
     *
     * @param itemKey The item key to retrieve (e.g., "agent.ping", "system.cpu.load").
     * @return An {@link AgentResponse} containing the value or error information.
     * @throws IllegalArgumentException if itemKey is null or empty.
     * @throws CommunicationException   if there are network issues (connection, timeout, I/O during send/receive).
     * @throws ProcessingException      if there are issues with the Zabbix protocol (packet creation/parsing)
     *                                  or if the item key is invalid according to the protocol.
     */
    public AgentResponse get(String itemKey) {
        if (itemKey == null || itemKey.trim().isEmpty()) {
            throw new IllegalArgumentException("Item key cannot be null or empty.");
        }
        // Zabbix agent protocol requires item key to end with \n, but ZabbixProtocol.createPacket might handle this.
        // The C code (zbx_tcp_recv_to_str) expects data without newline.
        // The Python code sends item_key.encode() directly.
        // Let's assume ZabbixProtocol.createPacket takes the raw key.
        // If Zabbix agent needs a newline in the payload string itself, it would be:
        // byte[] packet = ZabbixProtocol.createPacket(itemKey + "\n", false);
        // However, common implementations show the newline is not part of the data length in the header.
        // The Zabbix C sender sends "itemkey\0" for active checks, but for agent queries,
        // it's just the key. Let's stick to just the key for `createPacket`.

        logger.debug("Requesting item key '{}' from agent {}:{}", itemKey, agentHost, agentPort);

        byte[] requestPacket;
        try {
            // Compression is typically not used for agent requests.
            requestPacket = ZabbixProtocol.createPacket(itemKey, false);
        } catch (ProcessingException e) {
            // This could happen if itemKey is too long for protocol limits handled by createPacket
            throw new ProcessingException("Failed to create Zabbix protocol packet for item key '" + itemKey + "': " + e.getMessage(), e);
        }

        try (Socket socket = new Socket()) {
            if (this.sourceIpAddress != null) {
                socket.bind(new InetSocketAddress(this.sourceIpAddress, 0)); // 0 for ephemeral port
            }

            // Set connect timeout before connecting
            // socket.connect() uses its own timeout parameter. For consistency, we use it.
            socket.connect(new InetSocketAddress(this.agentHost, this.agentPort), this.timeoutMillis > 0 ? this.timeoutMillis : 0);

            // Set read timeout after connecting
            if (this.timeoutMillis > 0) {
                socket.setSoTimeout(this.timeoutMillis);
            }

            try (OutputStream out = socket.getOutputStream();
                 InputStream in = socket.getInputStream()) {

                out.write(requestPacket);
                out.flush();

                String rawResponse = ZabbixProtocol.parseResponse(in);
                logger.debug("Received raw response for item key '{}': {}", itemKey, rawResponse.substring(0, Math.min(rawResponse.length(), 200)));
                return new AgentResponse(rawResponse);

            }
        } catch (UnknownHostException e) {
            logger.error("Agent host '{}' is unknown: {}", agentHost, e.getMessage());
            throw new CommunicationException("Unknown agent host " + agentHost + ": " + e.getMessage(), e);
        } catch (ConnectException e) {
            logger.error("Connection refused by agent {}:{}: {}", agentHost, agentPort, e.getMessage());
            throw new CommunicationException("Connection refused by agent " + agentHost + ":" + agentPort + ": " + e.getMessage(), e);
        } catch (SocketTimeoutException e) {
            logger.error("Timeout connecting or reading from agent {}:{}: {}", agentHost, agentPort, e.getMessage());
            throw new CommunicationException("Timeout with agent " + agentHost + ":" + agentPort + ": " + e.getMessage(), e);
        } catch (IOException e) { // Covers other I/O errors during send/receive
            logger.error("IOException with agent {}:{}: {}", agentHost, agentPort, e.getMessage(), e);
            throw new CommunicationException("IO error with agent " + agentHost + ":" + agentPort + ": " + e.getMessage(), e);
        }
        // ProcessingException from ZabbixProtocol.parseResponse is already a ZabbixApiException and will propagate.
    }


    /**
     * Builder for {@link ZabbixGetterSync}.
     */
    public static class Builder {
        private String host;
        private int port = 10050; // Default Zabbix agent port
        private int timeoutSeconds = 5; // Default timeout
        private boolean useIpv6 = false;
        private InetAddress sourceIpAddress;

        /**
         * Sets the Zabbix Agent hostname or IP address. (Required)
         * @param host The agent's hostname or IP.
         * @return this builder
         */
        public Builder host(String host) {
            this.host = host;
            return this;
        }

        /**
         * Sets the Zabbix Agent port.
         * @param port The agent's port. Default is 10050.
         * @return this builder
         */
        public Builder port(int port) {
            if (port <= 0 || port > 65535) {
                throw new IllegalArgumentException("Port must be between 1 and 65535.");
            }
            this.port = port;
            return this;
        }

        /**
         * Sets the connection and read timeout for socket operations.
         * @param seconds Timeout in seconds. If <= 0, OS default or infinite might apply (Java default is 0 = infinite). Default is 5 seconds.
         * @return this builder
         */
        public Builder timeout(int seconds) {
            this.timeoutSeconds = seconds;
            return this;
        }

        /**
         * Hints that IPv6 should be used. Java's {@link Socket} typically auto-detects address type.
         * This setting is mostly for conceptual alignment or specific edge cases.
         * @param useIpv6 true to prefer IPv6 if applicable. Default is false.
         * @return this builder
         */
        public Builder useIpv6(boolean useIpv6) {
            this.useIpv6 = useIpv6;
            return this;
        }

        /**
         * Sets a specific source IP address to bind to for outgoing connections.
         * @param ip The source IP address string.
         * @return this builder
         * @throws ProcessingException if the IP address is invalid (wrapped in RuntimeException for builder convenience).
         */
        public Builder sourceIp(String ip) {
            try {
                this.sourceIpAddress = InetAddress.getByName(ip);
            } catch (UnknownHostException e) {
                // Builders typically throw IllegalArgumentException or similar runtime exceptions for bad config
                throw new IllegalArgumentException("Invalid source IP address: " + ip, e);
            }
            return this;
        }

        /**
         * Builds the {@link ZabbixGetterSync} instance.
         * @return A new ZabbixGetterSync instance.
         * @throws NullPointerException if host is not set.
         */
        public ZabbixGetterSync build() {
            Objects.requireNonNull(host, "Agent host must be provided for ZabbixGetterSync.");
            if (host.trim().isEmpty()) {
                 throw new IllegalArgumentException("Agent host cannot be empty.");
            }
            return new ZabbixGetterSync(this);
        }
    }
}
