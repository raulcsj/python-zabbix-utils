package io.zabbix4j.api;

import io.zabbix4j.api.exception.ZabbixProcessingException;
import io.zabbix4j.api.protocol.ZabbixProtocol;
import io.zabbix4j.api.types.AgentResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.function.Function;

/**
 * Provides synchronous communication with a Zabbix Agent to retrieve item values.
 * This class handles socket connections, packet creation, sending, and response parsing.
 * <p>
 * Instances of this class are thread-safe, assuming any provided {@code socketWrapper} is also thread-safe or stateless.
 *
 * @author CSJ
 */
public final class Getter {

    private static final Logger logger = LoggerFactory.getLogger(Getter.class);

    /**
     * Default Zabbix Agent port.
     */
    public static final int DEFAULT_ZABBIX_AGENT_PORT = 10050;
    /**
     * Default timeout for socket operations in milliseconds.
     */
    public static final int DEFAULT_TIMEOUT_MS = 10000;

    private final String host;
    private final int port;
    private final int timeout; // in milliseconds
    private final String sourceIp; // optional, can be null
    private final Function<Socket, Socket> socketWrapper; // optional, for SSL/TLS

    /**
     * Constructs a new {@code Getter} with specified connection parameters.
     *
     * @param host           The hostname or IP address of the Zabbix Agent.
     * @param port           The port number of the Zabbix Agent.
     * @param timeout        The timeout in milliseconds for socket connection and read operations.
     * @param sourceIp       The local IP address to bind to for outgoing connections. Can be {@code null}.
     * @param socketWrapper  A function to wrap the connected socket, e.g., for SSL/TLS. Can be {@code null}.
     *                       If the function returns a new socket instance, it is responsible for managing
     *                       the lifecycle (including closing) of the original socket if it's no longer needed.
     *                       The Getter will manage the lifecycle of the socket instance it ultimately uses.
     * @throws IllegalArgumentException if {@code host} is null or empty, {@code port} is out of valid range (1-65535),
     *                                  or {@code timeout} is not positive.
     */
    public Getter(String host, int port, int timeout, String sourceIp, Function<Socket, Socket> socketWrapper) {
        if (host == null || host.trim().isEmpty()) {
            throw new IllegalArgumentException("Host cannot be null or empty.");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("Port number must be between 1 and 65535. Got: " + port);
        }
        if (timeout <= 0) {
            throw new IllegalArgumentException("Timeout must be positive. Got: " + timeout);
        }
        this.host = host.trim();
        this.port = port;
        this.timeout = timeout;
        this.sourceIp = (sourceIp != null && !sourceIp.trim().isEmpty()) ? sourceIp.trim() : null;
        this.socketWrapper = socketWrapper;
    }

    public Getter(String host, int port, int timeout, String sourceIp) {
        this(host, port, timeout, sourceIp, null);
    }

    public Getter(String host, int port, int timeout) {
        this(host, port, timeout, null, null);
    }

    public Getter(String host, int port) {
        this(host, port, DEFAULT_TIMEOUT_MS, null, null);
    }

    public Getter(String host) {
        this(host, DEFAULT_ZABBIX_AGENT_PORT, DEFAULT_TIMEOUT_MS, null, null);
    }

    public AgentResponse get(String key) throws ZabbixProcessingException {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("Item key cannot be null or empty.");
        }
        logger.debug("Attempting to get key '{}' from agent {}:{}", key, host, port);

        Socket clientSocket = null; 
        try {
            clientSocket = new Socket(); 
            clientSocket.setSoTimeout(this.timeout); 

            if (this.sourceIp != null) {
                logger.debug("Binding to source IP: {}", this.sourceIp);
                try {
                    SocketAddress bindAddress = new InetSocketAddress(this.sourceIp, 0); 
                    clientSocket.bind(bindAddress);
                } catch (IOException e) {
                    logger.error("Failed to bind socket to source IP {}: {}", this.sourceIp, e.getMessage(), e);
                    throw new ZabbixProcessingException("Failed to bind socket to source IP " + this.sourceIp, e);
                }
            }

            SocketAddress targetAddress = new InetSocketAddress(this.host, this.port);
            logger.debug("Connecting to {} with timeout {}ms", targetAddress, this.timeout);
            clientSocket.connect(targetAddress, this.timeout); 

            if (this.socketWrapper != null) {
                logger.debug("Applying socket wrapper");
                try {
                    clientSocket = this.socketWrapper.apply(clientSocket);
                } catch (Exception e) { 
                    logger.error("Failed to apply socket wrapper: {}", e.getMessage(), e);
                    throw new ZabbixProcessingException("Failed to apply socket wrapper", e);
                }
            }

            logger.debug("Connected. Sending key: {}", key);
            byte[] requestPacket = ZabbixProtocol.createPacket(key, false); 

            try (OutputStream out = clientSocket.getOutputStream();
                 InputStream in = clientSocket.getInputStream()) { 

                out.write(requestPacket);
                out.flush();
                logger.debug("Request sent, awaiting response.");

                String rawResponse = ZabbixProtocol.parsePacket(in);
                logger.debug("Raw response received: {}", rawResponse);
                return new AgentResponse(rawResponse);
            }

        } catch (SocketTimeoutException e) {
            logger.error("Socket timeout while communicating with Zabbix Agent {}:{}: {}", host, port, e.getMessage(), e);
            throw new ZabbixProcessingException("Socket timeout communicating with Zabbix Agent " + host + ":" + port, e);
        } catch (ConnectException e) {
            logger.error("Connection refused by Zabbix Agent {}:{}: {}", host, port, e.getMessage(), e);
            throw new ZabbixProcessingException("Connection refused by Zabbix Agent " + host + ":" + port, e);
        } catch (UnknownHostException e) {
            logger.error("Unknown host for Zabbix Agent {}: {}", host, e.getMessage(), e);
            throw new ZabbixProcessingException("Unknown host for Zabbix Agent " + host, e);
        } catch (IOException e) {
            logger.error("IOException while communicating with Zabbix Agent {}:{}: {}", host, port, e.getMessage(), e);
            throw new ZabbixProcessingException("IOException communicating with Zabbix Agent " + host + ":" + port, e);
        } catch (ZabbixProcessingException e) { 
            logger.error("Zabbix processing error for key '{}' at {}:{}: {}", key, host, port, e.getMessage(), e);
            throw e;
        } finally {
            if (clientSocket != null && !clientSocket.isClosed()) {
                try {
                    logger.debug("Closing socket to {}:{}", host, port);
                    clientSocket.close();
                } catch (IOException e) {
                    logger.warn("Error closing socket to {}:{}: {}", host, port, e.getMessage(), e);
                }
            }
        }
    }
}
