package io.zabbix4j.api.async;

import io.zabbix4j.api.dto.AgentResponse;
import io.zabbix4j.api.exception.CommunicationException;
import io.zabbix4j.api.exception.ProcessingException;
import io.zabbix4j.api.protocol.ZabbixProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.AsynchronousChannelGroup;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Asynchronous client for retrieving item values from a Zabbix Agent using non-blocking I/O.
 * <p>
 * Use the {@link Builder} to construct instances of this class.
 * </p>
 * Example:
 * <pre>{@code
 * ZabbixAsyncGetter getter = new ZabbixAsyncGetter.Builder()
 * .host("zabbix-agent.example.com")
 * .port(10050)
 * .timeout(5) // seconds
 * .build();
 *
 * getter.get("agent.ping")
 * .thenAccept(response -> {
 * if (!response.hasError()) {
 * System.out.println("Agent ping response: " + response.getValue());
 * } else {
 * System.err.println("Agent error: " + response.getError());
 * }
 * })
 * .exceptionally(ex -> {
 * System.err.println("Failed to get item value: " + ex.getMessage());
 * return null;
 * }).join(); // Wait for completion in example
 *
 * // Remember to close the getter if it manages its own channel group
 * try { getter.close(); } catch (IOException e) { e.printStackTrace(); }
 * }</pre>
 *
 * @author ShortRoundDev
 */
public final class ZabbixAsyncGetter implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ZabbixAsyncGetter.class);

    private final String agentHost;
    private final int agentPort;
    private final int timeoutMillis;
    private final SocketAddress sourceIpAddress;
    private final AsynchronousChannelGroup channelGroup;
    private final boolean managedChannelGroup;

    private ZabbixAsyncGetter(Builder builder) {
        this.agentHost = Objects.requireNonNull(builder.host, "Agent host cannot be null.");
        this.agentPort = builder.port;
        this.timeoutMillis = builder.timeoutSeconds > 0 ? builder.timeoutSeconds * 1000 : 0;
        this.sourceIpAddress = builder.sourceIpAddress;

        if (builder.channelGroup != null) {
            this.channelGroup = builder.channelGroup;
            this.managedChannelGroup = false;
        } else {
            try {
                ExecutorService groupExecutor = Executors.newFixedThreadPool(Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
                this.channelGroup = AsynchronousChannelGroup.withThreadPool(groupExecutor);
                this.managedChannelGroup = true;
            } catch (IOException e) {
                throw new ProcessingException("Failed to create default AsynchronousChannelGroup for ZabbixAsyncGetter", e);
            }
        }
    }

    /**
     * Asynchronously retrieves a single item value from the configured Zabbix Agent.
     *
     * @param itemKey The item key to retrieve (e.g., "agent.ping", "system.cpu.load").
     * @return A {@link CompletableFuture} resolving to an {@link AgentResponse}.
     * @throws IllegalArgumentException if itemKey is null or empty.
     */
    public CompletableFuture<AgentResponse> get(String itemKey) {
        if (itemKey == null || itemKey.trim().isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Item key cannot be null or empty."));
        }

        logger.debug("Requesting item key '{}' asynchronously from agent {}:{}", itemKey, agentHost, agentPort);

        byte[] requestPacketBytes;
        try {
            // Compression is typically not used for agent requests from a simple getter.
            requestPacketBytes = ZabbixProtocol.createPacket(itemKey, false);
        } catch (ProcessingException e) {
            return CompletableFuture.failedFuture(
                    new ProcessingException("Failed to create Zabbix protocol packet for item key '" + itemKey + "': " + e.getMessage(), e)
            );
        }
        ByteBuffer requestPacketBuffer = ByteBuffer.wrap(requestPacketBytes);
        CompletableFuture<AgentResponse> future = new CompletableFuture<>();

        try {
            AsynchronousSocketChannel channel = AsynchronousSocketChannel.open(this.channelGroup);
            if (this.sourceIpAddress != null) {
                channel.bind(this.sourceIpAddress);
            }

            SocketAddress serverAddress = new InetSocketAddress(this.agentHost, this.agentPort);
            logger.debug("Attempting async connection to {}:{} for item key '{}'", agentHost, agentPort, itemKey);

            channel.connect(serverAddress, null, new CompletionHandler<Void, Void>() {
                @Override
                public void completed(Void result, Void attachment) {
                    logger.debug("Async connected to {}:{} for item key '{}'. Sending packet.", agentHost, agentPort, itemKey);
                    sendPacketAndReceiveResponseAsync(channel, requestPacketBuffer, itemKey, future);
                }

                @Override
                public void failed(Throwable exc, Void attachment) {
                    logger.error("Async connection failed to {}:{} for item key '{}': {}", agentHost, agentPort, itemKey, exc.getMessage());
                    future.completeExceptionally(new CommunicationException("Connection failed to agent " + agentHost + ":" + agentPort, exc));
                    closeChannelQuietly(channel);
                }
            });

        } catch (IOException e) { // From AsynchronousSocketChannel.open() or .bind()
            logger.error("IOException setting up async channel for {}:{}: {}", agentHost, agentPort, e.getMessage());
            future.completeExceptionally(new CommunicationException("Error setting up connection to agent " + agentHost + ":" + agentPort, e));
        }
        return future;
    }

    private void sendPacketAndReceiveResponseAsync(
            AsynchronousSocketChannel channel, ByteBuffer requestPacketBuffer, String itemKey,
            CompletableFuture<AgentResponse> overallFuture) {

        // 1. Write Request
        channel.write(requestPacketBuffer, this.timeoutMillis, TimeUnit.MILLISECONDS, null,
                new CompletionHandler<Integer, Void>() {
                    @Override
                    public void completed(Integer bytesWritten, Void attachment) {
                        if (requestPacketBuffer.hasRemaining()) {
                             // Robust handling would loop write, for now, error on partial.
                            logger.warn("Partial write for item key '{}': {} of {} bytes. Treating as error.", itemKey, bytesWritten, requestPacketBuffer.limit());
                            failed(new IOException("Partial write, remaining bytes: " + requestPacketBuffer.remaining()), null);
                            return;
                        }
                        logger.debug("Successfully wrote {} bytes for item key '{}'. Reading response header.", bytesWritten, itemKey);

                        // 2. Read Response Header
                        ByteBuffer headerReadBuffer = ByteBuffer.allocate(ZabbixProtocol.HEADER_SIZE);
                        channel.read(headerReadBuffer, timeoutMillis, TimeUnit.MILLISECONDS, null,
                                new CompletionHandler<Integer, Void>() {
                                    @Override
                                    public void completed(Integer headerBytesRead, Void attachment) {
                                        if (headerBytesRead < ZabbixProtocol.HEADER_SIZE) {
                                            failed(new IOException("Partial header read: " + headerBytesRead + " of " + ZabbixProtocol.HEADER_SIZE), null);
                                            return;
                                        }
                                        headerReadBuffer.flip();
                                        logger.debug("Read {} header bytes for item key '{}'.", headerBytesRead, itemKey);

                                        // Parse header
                                        headerReadBuffer.order(ByteOrder.LITTLE_ENDIAN);
                                        byte[] signature = new byte[ZabbixProtocol.ZABBIX_HEADER_SIGNATURE.length];
                                        headerReadBuffer.get(signature);

                                        if (!java.util.Arrays.equals(signature, ZabbixProtocol.ZABBIX_HEADER_SIGNATURE)) {
                                            failed(new ProcessingException("Invalid Zabbix header signature for item key '" + itemKey + "'."), null);
                                            return;
                                        }
                                        byte flags = headerReadBuffer.get();
                                        int dataLength = headerReadBuffer.getInt();
                                        int reservedLength = headerReadBuffer.getInt(); // Uncompressed length if compressed

                                        if ((flags & ZabbixProtocol.FLAG_ZABBIX_PROTOCOL) == 0) {
                                            failed(new ProcessingException("Packet for item key '" + itemKey + "' does not conform to Zabbix protocol."), null);
                                            return;
                                        }
                                        if ((flags & ZabbixProtocol.FLAG_LARGE_PACKET) != 0) { // Unlikely for agent
                                            failed(new ProcessingException("Large packet flag not supported for item key '" + itemKey + "'."), null);
                                            return;
                                        }
                                        if (dataLength < 0 || dataLength > (128 * 1024 * 1024)) { // Sanity limit
                                            failed(new ProcessingException("Invalid data length " + dataLength + " for item key '" + itemKey + "'."), null);
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
                                                        logger.debug("Read {} body bytes for item key '{}'.", bodyBytesRead, itemKey);

                                                        byte[] payloadBytes = new byte[dataLength];
                                                        bodyReadBuffer.get(payloadBytes);
                                                        String rawResponse;

                                                        try {
                                                            if ((flags & ZabbixProtocol.FLAG_COMPRESSION) != 0) { // Unlikely for agent
                                                                byte[] decompressed = decompressPayload(payloadBytes, reservedLength);
                                                                rawResponse = new String(decompressed, StandardCharsets.UTF_8);
                                                            } else {
                                                                rawResponse = new String(payloadBytes, StandardCharsets.UTF_8);
                                                            }
                                                            logger.debug("Parsed response for item key '{}': {}", itemKey, rawResponse.substring(0, Math.min(rawResponse.length(), 200)));
                                                            overallFuture.complete(new AgentResponse(rawResponse));
                                                        } catch (ProcessingException e) {
                                                            failed(e, null);
                                                        } finally {
                                                            closeChannelQuietly(channel);
                                                        }
                                                    }

                                                    @Override
                                                    public void failed(Throwable exc, Void attachment) {
                                                        logger.warn("Failed to read body for item key '{}': {}", itemKey, exc.getMessage());
                                                        overallFuture.completeExceptionally(new CommunicationException("Failed to read response body for item key '" + itemKey + "'.", exc));
                                                        closeChannelQuietly(channel);
                                                    }
                                                });
                                    }

                                    @Override
                                    public void failed(Throwable exc, Void attachment) {
                                        logger.warn("Failed to read header for item key '{}': {}", itemKey, exc.getMessage());
                                        overallFuture.completeExceptionally(new CommunicationException("Failed to read response header for item key '" + itemKey + "'.", exc));
                                        closeChannelQuietly(channel);
                                    }
                                });
                    }

                    @Override
                    public void failed(Throwable exc, Void attachment) {
                        logger.warn("Failed to write packet for item key '{}': {}", itemKey, exc.getMessage());
                        overallFuture.completeExceptionally(new CommunicationException("Failed to write request for item key '" + itemKey + "'.", exc));
                        closeChannelQuietly(channel);
                    }
                });
    }

    private byte[] decompressPayload(byte[] compressedData, int uncompressedSize) throws ProcessingException {
        Inflater inflater = new Inflater();
        inflater.setInput(compressedData);
        ByteArrayOutputStream baos = new ByteArrayOutputStream(uncompressedSize > 0 ? uncompressedSize : compressedData.length * 2);
        byte[] buffer = new byte[1024];
        try {
            while (!inflater.finished()) {
                if (inflater.needsInput()) {
                    throw new ProcessingException("Inflater needs input unexpectedly during Zabbix payload decompression.");
                }
                int count = inflater.inflate(buffer);
                if (count == 0 && inflater.needsDictionary()) {
                    throw new ProcessingException("Zabbix payload decompression failed: needs dictionary.");
                }
                if (count == 0 && inflater.finished()) break;
                if (count == 0) throw new ProcessingException("Zabbix payload decompression stalled or error.");
                baos.write(buffer, 0, count);
            }
        } catch (DataFormatException e) {
            throw new ProcessingException("Failed to decompress Zabbix payload: " + e.getMessage(), e);
        } finally {
            inflater.end();
        }
        byte[] decompressed = baos.toByteArray();
        if (uncompressedSize > 0 && decompressed.length != uncompressedSize) {
            logger.warn("Decompressed payload size ({}) does not match expected uncompressed size ({}).",
                    decompressed.length, uncompressedSize);
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

    @Override
    public void close() throws IOException {
        if (this.channelGroup != null && this.managedChannelGroup) {
            try {
                logger.info("Shutting down managed AsynchronousChannelGroup for ZabbixAsyncGetter.");
                this.channelGroup.shutdown();
                if (!this.channelGroup.awaitTermination(5, TimeUnit.SECONDS)) {
                    this.channelGroup.shutdownNow();
                }
            } catch (InterruptedException e) {
                this.channelGroup.shutdownNow();
                Thread.currentThread().interrupt();
            }
            logger.info("Managed AsynchronousChannelGroup for ZabbixAsyncGetter closed.");
        }
    }

    /**
     * Builder for {@link ZabbixAsyncGetter}.
     */
    public static class Builder {
        private String host;
        private int port = 10050; // Default Zabbix agent port
        private int timeoutSeconds = 5; // Default timeout
        private SocketAddress sourceIpAddress;
        private AsynchronousChannelGroup channelGroup;
        // useIpv6 is omitted as AsynchronousSocketChannel resolves based on InetSocketAddress

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
         * Sets the connection, write, and read timeout for socket operations.
         * @param seconds Timeout in seconds. If <= 0, operations may not time out or use OS defaults. Default is 5 seconds.
         * @return this builder
         */
        public Builder timeout(int seconds) {
            this.timeoutSeconds = seconds;
            return this;
        }

        /**
         * Sets a specific source IP address to bind to for outgoing connections.
         * @param ip The source IP address string.
         * @return this builder
         * @throws IllegalArgumentException if the IP address string is invalid.
         */
        public Builder sourceIp(String ip) {
            try {
                // For bind, it should be a local IP string. Port 0 for ephemeral.
                this.sourceIpAddress = new InetSocketAddress(ip, 0);
            } catch (Exception e) { // Catches SecurityException or IllegalArgumentException
                throw new IllegalArgumentException("Invalid source IP address string: " + ip, e);
            }
            return this;
        }
        /**
         * Sets a specific source {@link SocketAddress} to bind to.
         * @param sourceAddress The source socket address.
         * @return this builder
         */
        public Builder sourceIp(SocketAddress sourceAddress) {
            this.sourceIpAddress = sourceAddress;
            return this;
        }


        /**
         * Sets an external {@link AsynchronousChannelGroup} to be used for I/O operations.
         * If not set, a default one will be created and managed by this instance.
         * @param channelGroup The channel group to use.
         * @return this builder
         */
        public Builder channelGroup(AsynchronousChannelGroup channelGroup) {
            this.channelGroup = channelGroup;
            return this;
        }

        /**
         * Builds the {@link ZabbixAsyncGetter} instance.
         * @return A new ZabbixAsyncGetter instance.
         * @throws NullPointerException if host is not set.
         * @throws IllegalArgumentException if host is empty.
         */
        public ZabbixAsyncGetter build() {
            Objects.requireNonNull(host, "Agent host must be provided for ZabbixAsyncGetter.");
            if (host.trim().isEmpty()) {
                 throw new IllegalArgumentException("Agent host cannot be empty.");
            }
            return new ZabbixAsyncGetter(this);
        }
    }
}
