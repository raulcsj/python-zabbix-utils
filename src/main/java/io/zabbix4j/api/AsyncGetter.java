package io.zabbix4j.api;

import io.zabbix4j.api.exception.ZabbixProcessingException;
import io.zabbix4j.api.protocol.ZabbixProtocol;
import io.zabbix4j.api.types.AgentResponse;
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
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Provides asynchronous communication with a Zabbix Agent to retrieve item values
 * using Java NIO's {@link AsynchronousSocketChannel}.
 * This class handles asynchronous socket connections, packet creation, sending, and response parsing.
 * <p>
 * Instances of this class are thread-safe. Each call to {@code get} operates on a new channel.
 *
 * @author CSJ
 */
public final class AsyncGetter {

    private static final Logger logger = LoggerFactory.getLogger(AsyncGetter.class);

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

    /**
     * Constructs a new {@code AsyncGetter} with specified connection parameters.
     *
     * @param host     The hostname or IP address of the Zabbix Agent.
     * @param port     The port number of the Zabbix Agent.
     * @param timeout  The timeout in milliseconds for socket connection, read and write operations.
     * @param sourceIp The local IP address to bind to for outgoing connections. Can be {@code null}.
     * @throws IllegalArgumentException if {@code host} is null or empty, {@code port} is out of valid range (1-65535),
     *                                  or {@code timeout} is not positive.
     */
    public AsyncGetter(String host, int port, int timeout, String sourceIp) {
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
    }

    /**
     * Constructs a new {@code AsyncGetter} without a specific source IP.
     *
     * @param host    The hostname or IP address of the Zabbix Agent.
     * @param port    The port number of the Zabbix Agent.
     * @param timeout The timeout in milliseconds for socket operations.
     */
    public AsyncGetter(String host, int port, int timeout) {
        this(host, port, timeout, null);
    }

    /**
     * Constructs a new {@code AsyncGetter} with a default timeout (10000ms) and no source IP.
     *
     * @param host The hostname or IP address of the Zabbix Agent.
     * @param port The port number of the Zabbix Agent.
     */
    public AsyncGetter(String host, int port) {
        this(host, port, DEFAULT_TIMEOUT_MS, null);
    }

    /**
     * Constructs a new {@code AsyncGetter} with default port (10050) and default timeout (10000ms), and no source IP.
     *
     * @param host The hostname or IP address of the Zabbix Agent.
     */
    public AsyncGetter(String host) {
        this(host, DEFAULT_ZABBIX_AGENT_PORT, DEFAULT_TIMEOUT_MS, null);
    }

    /**
     * Asynchronously retrieves the value of a specific item key from the Zabbix Agent.
     *
     * @param key The item key to query (e.g., "agent.ping", "system.cpu.load[,avg1]"). Must not be null or empty.
     * @return A {@link CompletableFuture} which will be completed with an {@link AgentResponse}
     *         containing the agent's response, or completed exceptionally if an error occurs.
     * @throws IllegalArgumentException if {@code key} is null or empty.
     */
    public CompletableFuture<AgentResponse> get(String key) {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("Item key cannot be null or empty.");
        }
        logger.debug("Async getting key '{}' from agent {}:{}", key, host, port);

        final CompletableFuture<AgentResponse> future = new CompletableFuture<>();
        AsynchronousSocketChannel channel = null;

        try {
            channel = AsynchronousSocketChannel.open();

            if (sourceIp != null) {
                logger.debug("Binding to source IP: {}", sourceIp);
                SocketAddress bindAddress = new InetSocketAddress(sourceIp, 0);
                channel.bind(bindAddress);
            }

            SocketAddress serverAddress = new InetSocketAddress(host, port);
            byte[] requestPacket = ZabbixProtocol.createPacket(key, false); // No compression for agent

            // Schedule a timeout for the entire operation
            CompletableFuture<Void> timeoutFuture = new CompletableFuture<>();
            ScheduledExecutor.SCHEDULER.schedule(() -> {
                if (!future.isDone()) {
                    timeoutFuture.completeExceptionally(new ZabbixProcessingException("Overall operation timeout after " + timeout + "ms for key: " + key));
                }
            }, timeout, TimeUnit.MILLISECONDS);
            
            future.exceptionally(ex -> { // Ensure channel closure on premature future completion
                closeChannel(channel, "Future completed exceptionally before full operation.");
                return null; 
            });
            timeoutFuture.exceptionally(ex -> { // If timeoutFuture completes exceptionally
                future.completeExceptionally(ex);
                closeChannel(channel, "Operation timed out.");
                return null;
            });


            channel.connect(serverAddress, null, new CompletionHandler<Void, Void>() {
                @Override
                public void completed(Void result, Void attachment) {
                    logger.debug("Connected to {}:{}. Writing packet...", host, port);
                    ByteBuffer writeBuffer = ByteBuffer.wrap(requestPacket);
                    channel.write(writeBuffer, timeout, TimeUnit.MILLISECONDS, null, new CompletionHandler<Integer, Void>() {
                        @Override
                        public void completed(Integer bytesWritten, Void attachment) {
                            if (bytesWritten < requestPacket.length) {
                                String errorMsg = "Incomplete write to agent. Wrote " + bytesWritten + "/" + requestPacket.length + " bytes.";
                                logger.error(errorMsg);
                                future.completeExceptionally(new ZabbixProcessingException(errorMsg));
                                closeChannel(channel, "Incomplete write.");
                                return;
                            }
                            logger.debug("Packet written. Reading header...");
                            readHeader(channel, future);
                        }

                        @Override
                        public void failed(Throwable exc, Void attachment) {
                            logger.error("Failed to write to agent {}:{}: {}", host, port, exc.getMessage(), exc);
                            future.completeExceptionally(new ZabbixProcessingException("Failed to write to agent", exc));
                            closeChannel(channel, "Write failed.");
                        }
                    });
                }

                @Override
                public void failed(Throwable exc, Void attachment) {
                    logger.error("Failed to connect to agent {}:{}: {}", host, port, exc.getMessage(), exc);
                    future.completeExceptionally(new ZabbixProcessingException("Failed to connect to agent", exc));
                    closeChannel(channel, "Connect failed.");
                }
            });

        } catch (IOException e) {
            logger.error("Error opening or binding AsynchronousSocketChannel: {}", e.getMessage(), e);
            future.completeExceptionally(new ZabbixProcessingException("Error setting up connection", e));
            closeChannel(channel, "Setup failed.");
        }
        return future;
    }

    private void readHeader(AsynchronousSocketChannel channel, CompletableFuture<AgentResponse> future) {
        ByteBuffer headerReadBuffer = ByteBuffer.allocate(ZabbixProtocol.HEADER_SIZE);
        channel.read(headerReadBuffer, timeout, TimeUnit.MILLISECONDS, null, new CompletionHandler<Integer, Void>() {
            @Override
            public void completed(Integer bytesRead, Void attachment) {
                if (bytesRead < ZabbixProtocol.HEADER_SIZE) {
                    String errorMsg = "Incomplete header read from agent. Read " + bytesRead + "/" + ZabbixProtocol.HEADER_SIZE + " bytes.";
                    logger.error(errorMsg);
                    future.completeExceptionally(new ZabbixProcessingException(errorMsg));
                    closeChannel(channel, "Incomplete header read.");
                    return;
                }
                headerReadBuffer.flip();
                logger.debug("Header read. Parsing...");

                byte[] headerBytes = new byte[ZabbixProtocol.HEADER_SIZE];
                headerReadBuffer.get(headerBytes);

                if (!Arrays.equals(Arrays.copyOfRange(headerBytes, 0, ZabbixProtocol.ZABBIX_HEADER_BYTES.length), ZabbixProtocol.ZABBIX_HEADER_BYTES)) {
                    future.completeExceptionally(new ZabbixProcessingException("Invalid Zabbix protocol header: Magic number 'ZBXD' not found."));
                    closeChannel(channel, "Invalid header magic.");
                    return;
                }

                ByteBuffer parsedHeader = ByteBuffer.wrap(headerBytes);
                parsedHeader.order(ByteOrder.LITTLE_ENDIAN);
                parsedHeader.position(ZabbixProtocol.ZABBIX_HEADER_BYTES.length); // Skip "ZBXD"

                byte flags = parsedHeader.get();
                int dataLength = parsedHeader.getInt();
                // int reservedLength = parsedHeader.getInt(); // For agent response, reserved is usually 0

                if ((flags & ZabbixProtocol.FLAGS_PROTOCOL_VERSION) == 0) {
                    future.completeExceptionally(new ZabbixProcessingException("Invalid Zabbix protocol flags: version bit (0x01) not set. Flags: " + String.format("0x%02X", flags)));
                    closeChannel(channel, "Invalid protocol version.");
                    return;
                }
                if ((flags & ZabbixProtocol.FLAGS_LARGE_PACKET) != 0) {
                    future.completeExceptionally(new ZabbixProcessingException("Large packet mode (flag 0x04) is not supported. Flags: " + String.format("0x%02X", flags)));
                    closeChannel(channel, "Large packet not supported.");
                    return;
                }
                if (dataLength < 0) {
                    future.completeExceptionally(new ZabbixProcessingException("Invalid data length in Zabbix header: " + dataLength));
                    closeChannel(channel, "Invalid data length.");
                    return;
                }
                 if (dataLength == 0) { // Handle empty payload case
                    logger.debug("Empty payload received.");
                    future.complete(new AgentResponse(""));
                    closeChannel(channel, "Empty payload processed.");
                    return;
                }


                logger.debug("Header parsed. Data length: {}. Reading payload...", dataLength);
                readPayload(channel, future, dataLength, (flags & ZabbixProtocol.FLAGS_COMPRESSION) != 0);
            }

            @Override
            public void failed(Throwable exc, Void attachment) {
                logger.error("Failed to read header from agent {}:{}: {}", host, port, exc.getMessage(), exc);
                future.completeExceptionally(new ZabbixProcessingException("Failed to read header", exc));
                closeChannel(channel, "Header read failed.");
            }
        });
    }

    private void readPayload(AsynchronousSocketChannel channel, CompletableFuture<AgentResponse> future, int dataLength, boolean isCompressed) {
        ByteBuffer payloadBuffer = ByteBuffer.allocate(dataLength);
        channel.read(payloadBuffer, timeout, TimeUnit.MILLISECONDS, null, new CompletionHandler<Integer, Void>() {
            @Override
            public void completed(Integer bytesRead, Void attachment) {
                if (bytesRead < dataLength) {
                    // Simplified: assume full read or fail. Real-world might need loop for partial reads.
                    String errorMsg = "Incomplete payload read from agent. Read " + bytesRead + "/" + dataLength + " bytes.";
                    logger.error(errorMsg);
                    future.completeExceptionally(new ZabbixProcessingException(errorMsg));
                    closeChannel(channel, "Incomplete payload read.");
                    return;
                }
                payloadBuffer.flip();
                logger.debug("Payload read. Decompressing if needed (compressed={})...", isCompressed);

                byte[] receivedData = new byte[dataLength];
                payloadBuffer.get(receivedData);
                byte[] finalPayloadBytes = receivedData;

                if (isCompressed) {
                    Inflater inflater = new Inflater();
                    inflater.setInput(receivedData);
                    try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                        byte[] buffer = new byte[1024];
                        while (!inflater.finished()) {
                            int count = inflater.inflate(buffer);
                            if (count == 0 && inflater.needsInput() && !inflater.finished()) {
                                // Should not happen if dataLength was correct for compressed data
                                throw new ZabbixProcessingException("Inflater needs input, but all compressed data provided.");
                            }
                            if (count == 0 && !inflater.finished()) { // No progress but not finished
                                throw new ZabbixProcessingException("Error during decompression: inflater stuck.");
                            }
                            if (count > 0) baos.write(buffer, 0, count);
                        }
                        finalPayloadBytes = baos.toByteArray();
                    } catch (DataFormatException e) {
                        logger.error("Failed to decompress payload: {}", e.getMessage(), e);
                        future.completeExceptionally(new ZabbixProcessingException("Payload decompression failed", e));
                        closeChannel(channel, "Decompression failed.");
                        return;
                    } catch (IOException e) { // For ByteArrayOutputStream
                        logger.error("ByteArrayOutputStream error during decompression: {}", e.getMessage(), e);
                        future.completeExceptionally(new ZabbixProcessingException("Internal error during decompression", e));
                        closeChannel(channel, "Decompression stream failed.");
                        return;
                    } finally {
                        inflater.end();
                    }
                }

                String responseString = new String(finalPayloadBytes, StandardCharsets.UTF_8);
                logger.debug("Payload processed. Response string: {}", responseString);
                future.complete(new AgentResponse(responseString));
                closeChannel(channel, "Successfully processed.");
            }

            @Override
            public void failed(Throwable exc, Void attachment) {
                logger.error("Failed to read payload from agent {}:{}: {}", host, port, exc.getMessage(), exc);
                future.completeExceptionally(new ZabbixProcessingException("Failed to read payload", exc));
                closeChannel(channel, "Payload read failed.");
            }
        });
    }

    private void closeChannel(AsynchronousSocketChannel channel, String reason) {
        if (channel != null && channel.isOpen()) {
            try {
                logger.debug("Closing channel to {}:{}. Reason: {}", host, port, reason);
                channel.close();
            } catch (IOException e) {
                logger.warn("Error closing channel to {}:{}: {}", host, port, e.getMessage(), e);
            }
        }
    }
    
    // Inner class for scheduling timeouts, as CompletableFuture.orTimeout requires Java 9+
    // and this project is Java 11 but direct usage of Delayer in CF is cleaner.
    // For this specific environment, we can use CompletableFuture.completeOnTimeout (Java 9+)
    // or new CompletableFuture<>().get(timeout, unit) in a separate thread.
    // Simpler: use a ScheduledExecutorService.
    private static class ScheduledExecutor {
        private static final java.util.concurrent.ScheduledExecutorService SCHEDULER =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "AsyncGetter-TimeoutScheduler");
                t.setDaemon(true);
                return t;
            });
    }
}
