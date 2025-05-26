package io.zabbix4j.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A simple NIO-based server to simulate a Zabbix Agent for testing AsyncGetter.
 * It accepts a single connection, optionally reads a request, sends a predefined response,
 * and then closes the connection.
 *
 * @author CSJ
 */
public class NioZabbixAgentSimulator {
    private static final Logger logger = LoggerFactory.getLogger(NioZabbixAgentSimulator.class);
    private AsynchronousServerSocketChannel serverChannel;
    private byte[] responsePacket;
    private volatile boolean running = false;
    private CountDownLatch connectionHandledLatch; // To signal completion for test synchronization

    /**
     * Starts the simulator on the specified port.
     *
     * @param port The port to listen on.
     * @throws IOException if an I/O error occurs when opening the server socket.
     */
    public void start(int port) throws IOException {
        if (running) {
            logger.warn("Simulator already running. Stop it first.");
            return;
        }
        serverChannel = AsynchronousServerSocketChannel.open();
        serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        serverChannel.bind(new InetSocketAddress("127.0.0.1", port));
        running = true;
        connectionHandledLatch = new CountDownLatch(1); // Expect one connection to be handled

        logger.info("NioZabbixAgentSimulator started on port {}, waiting for a connection...", port);

        serverChannel.accept(null, new CompletionHandler<AsynchronousSocketChannel, Void>() {
            @Override
            public void completed(AsynchronousSocketChannel clientChannel, Void attachment) {
                if (!running) { // Server might have been stopped concurrently
                    closeClientChannel(clientChannel, "Server stopped during accept.");
                    connectionHandledLatch.countDown();
                    return;
                }
                
                // Accept next connection only if server is still running (though this basic server handles one)
                // For multiple connections, serverChannel.accept would be called again here.
                // For this test server, we usually expect one connection per start().

                logger.info("Simulator accepted connection from: {}", getRemoteAddress(clientChannel));
                handleClient(clientChannel);
            }

            @Override
            public void failed(Throwable exc, Void attachment) {
                if (running) { // Only log if we weren't expecting a stop
                    logger.error("Simulator failed to accept connection.", exc);
                }
                connectionHandledLatch.countDown(); // Ensure latch releases on failure too
            }
        });
    }

    private String getRemoteAddress(AsynchronousSocketChannel clientChannel) {
        try {
            return clientChannel.getRemoteAddress().toString();
        } catch (IOException e) {
            return "unknown";
        }
    }


    private void handleClient(AsynchronousSocketChannel clientChannel) {
        // First, try to read some data (simulating consuming client's request)
        ByteBuffer readBuffer = ByteBuffer.allocate(1024); // Assuming client request won't be huge
        clientChannel.read(readBuffer, 60, TimeUnit.SECONDS, null, new CompletionHandler<Integer, Void>() {
            @Override
            public void completed(Integer bytesRead, Void attachment) {
                if (bytesRead > 0) {
                    readBuffer.flip();
                    byte[] received = new byte[readBuffer.limit()];
                    readBuffer.get(received);
                    logger.debug("Simulator received {} bytes from client: {}", bytesRead, new String(received).trim());
                } else if (bytesRead == -1) {
                    logger.warn("Client closed connection before sending data or after partial send.");
                    closeClientChannel(clientChannel, "Client closed early.");
                    connectionHandledLatch.countDown();
                    return;
                }


                if (responsePacket != null && responsePacket.length > 0) {
                    logger.debug("Simulator sending response packet ({} bytes).", responsePacket.length);
                    ByteBuffer writeBuffer = ByteBuffer.wrap(responsePacket);
                    clientChannel.write(writeBuffer, 60, TimeUnit.SECONDS, null, new CompletionHandler<Integer, Void>() {
                        @Override
                        public void completed(Integer bytesWritten, Void attachment) {
                            if (bytesWritten < responsePacket.length) {
                                logger.warn("Simulator: Incomplete write. Wrote {} of {} bytes.", bytesWritten, responsePacket.length);
                            } else {
                                logger.debug("Simulator finished writing response.");
                            }
                            closeClientChannel(clientChannel, "Response sent.");
                            connectionHandledLatch.countDown();
                        }

                        @Override
                        public void failed(Throwable exc, Void attachment) {
                            logger.error("Simulator failed to write response.", exc);
                            closeClientChannel(clientChannel, "Write failed.");
                            connectionHandledLatch.countDown();
                        }
                    });
                } else {
                    logger.warn("Simulator: No response packet set, or it's empty. Closing client connection.");
                    closeClientChannel(clientChannel, "No response to send.");
                    connectionHandledLatch.countDown();
                }
            }

            @Override
            public void failed(Throwable exc, Void attachment) {
                logger.error("Simulator failed to read from client.", exc);
                closeClientChannel(clientChannel, "Read failed.");
                connectionHandledLatch.countDown();
            }
        });
    }
    
    private void closeClientChannel(AsynchronousSocketChannel clientChannel, String reason) {
        if (clientChannel != null && clientChannel.isOpen()) {
            try {
                logger.debug("Simulator closing client channel. Reason: {}", reason);
                clientChannel.close();
            } catch (IOException e) {
                logger.warn("Simulator error closing client channel.", e);
            }
        }
    }


    /**
     * Stops the simulator and closes the server socket.
     */
    public void stop() {
        running = false;
        if (serverChannel != null && serverChannel.isOpen()) {
            try {
                logger.info("Stopping NioZabbixAgentSimulator...");
                serverChannel.close();
                logger.info("NioZabbixAgentSimulator stopped.");
            } catch (IOException e) {
                logger.error("Error stopping NioZabbixAgentSimulator.", e);
            }
        }
         if (connectionHandledLatch != null && connectionHandledLatch.getCount() > 0) {
            // If stop is called before any connection was handled, ensure latch counts down.
            logger.debug("Forcing connectionHandledLatch countdown due to stop.");
            connectionHandledLatch.countDown(); 
        }
    }

    /**
     * Sets the byte array to be sent back to the client as a response.
     *
     * @param responseBytes The Zabbix protocol packet to send.
     */
    public void setResponsePacket(byte[] responseBytes) {
        this.responsePacket = responseBytes;
    }

    /**
     * Waits for the current connection to be handled (read, write, close).
     * Useful for test synchronization.
     * @param timeout Max time to wait.
     * @param unit Time unit for timeout.
     * @return true if the connection was handled within the timeout, false otherwise.
     * @throws InterruptedException if the current thread is interrupted while waiting.
     */
    public boolean awaitConnectionHandled(long timeout, TimeUnit unit) throws InterruptedException {
        if (connectionHandledLatch == null) return true; // Nothing to wait for if not started or already passed
        return connectionHandledLatch.await(timeout, unit);
    }
}
