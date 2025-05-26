package io.zabbix4j.api;

import io.zabbix4j.api.protocol.ZabbixProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A simple socket-based server to simulate a Zabbix Server/Proxy for testing Sender.
 * It accepts connections, reads Zabbix sender protocol packets, and sends predefined JSON responses.
 *
 * @author CSJ
 */
public class SocketZabbixServerSimulator {
    private static final Logger logger = LoggerFactory.getLogger(SocketZabbixServerSimulator.class);
    private ServerSocket serverSocket;
    private volatile boolean running = false;
    private String jsonResponseToSend;
    private final AtomicInteger requestCount = new AtomicInteger(0);
    private final List<String> receivedPayloads = new CopyOnWriteArrayList<>(); // Thread-safe list
    private ExecutorService clientHandlerExecutor;
    private Thread serverThread;
    private CountDownLatch stopLatch;
    private int port;

    /**
     * Starts the simulator on the specified port.
     *
     * @param port The port to listen on.
     * @throws IOException if an I/O error occurs when opening the server socket.
     */
    public void start(int port) throws IOException {
        if (running) {
            logger.warn("Simulator already running on port {}. Stop it first.", this.port);
            return;
        }
        this.port = port;
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress("127.0.0.1", port));
        running = true;
        requestCount.set(0);
        receivedPayloads.clear();
        clientHandlerExecutor = Executors.newCachedThreadPool(); // Handle multiple clients if needed quickly
        stopLatch = new CountDownLatch(1);

        serverThread = new Thread(() -> {
            logger.info("SocketZabbixServerSimulator started on port {}, waiting for connections...", port);
            while (running && !Thread.currentThread().isInterrupted()) {
                try {
                    Socket clientSocket = serverSocket.accept(); // This blocks
                    if (!running) { // Check again after accept returns, in case stop was called
                        clientSocket.close();
                        break;
                    }
                    logger.info("Simulator accepted connection from: {}", clientSocket.getRemoteSocketAddress());
                    clientHandlerExecutor.submit(() -> handleClient(clientSocket));
                } catch (SocketException e) {
                    if (!running) { // Expected when serverSocket.close() is called by stop()
                        logger.info("ServerSocket closed, simulator shutting down accept loop.");
                    } else {
                        logger.error("ServerSocket accept error on port {}: {}", port, e.getMessage(), e);
                    }
                } catch (IOException e) {
                    if (running) {
                        logger.error("IOException accepting connection on port {}: {}", port, e.getMessage(), e);
                    }
                }
            }
            logger.info("Simulator server thread on port {} exiting.", port);
            stopLatch.countDown();
        });
        serverThread.setName("ZabbixSim-Port-" + port);
        serverThread.start();
    }

    private void handleClient(Socket clientSocket) {
        try (Socket s = clientSocket; // Ensure socket is closed
             InputStream in = s.getInputStream();
             OutputStream out = s.getOutputStream()) {

            // 1. Read Zabbix Protocol Packet from client
            String receivedJsonPayload = ZabbixProtocol.parsePacket(in); // This can throw IOException or ZabbixProcessingException
            logger.debug("Simulator (port {}) received payload: {}", port, receivedJsonPayload);
            receivedPayloads.add(receivedJsonPayload);
            requestCount.incrementAndGet();

            // 2. Send predefined response (if any)
            if (jsonResponseToSend != null) {
                logger.debug("Simulator (port {}) sending response: {}", port, jsonResponseToSend);
                byte[] responseBytes = ZabbixProtocol.createPacket(jsonResponseToSend, false); // Server responses are not compressed
                out.write(responseBytes);
                out.flush();
            } else {
                logger.warn("Simulator (port {}): No JSON response set. Client might time out or get empty response.", port);
                // Closing the socket without a response might be interpreted as an error by the client.
                // For some tests (e.g. read timeout), this is desired.
            }
        } catch (Exception e) { // Catch ZabbixProcessingException and IOException
            logger.error("Simulator (port {}) error handling client {}: {}", port, clientSocket.getRemoteSocketAddress(), e.getMessage(), e);
        } finally {
            logger.debug("Simulator (port {}) finished handling client {}", port, clientSocket.getRemoteSocketAddress());
        }
    }

    /**
     * Stops the simulator and closes the server socket.
     * Waits for the server thread to shut down.
     */
    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        logger.info("Stopping SocketZabbixServerSimulator on port {}...", port);
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close(); // This will interrupt the blocking accept()
            }
        } catch (IOException e) {
            logger.error("Error closing server socket on port {}: {}", port, e.getMessage(), e);
        }

        if (clientHandlerExecutor != null) {
            clientHandlerExecutor.shutdown();
            try {
                if (!clientHandlerExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    clientHandlerExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                clientHandlerExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        if (serverThread != null && serverThread.isAlive()) {
            serverThread.interrupt(); // Interrupt if it's stuck for any other reason
            try {
                 if (stopLatch != null) {
                    if (!stopLatch.await(5, TimeUnit.SECONDS)) {
                        logger.warn("Simulator server thread on port {} did not terminate cleanly after 5 seconds.", port);
                    }
                 } else {
                    serverThread.join(5000); // Fallback join
                 }
            } catch (InterruptedException e) {
                logger.warn("Interrupted while waiting for simulator server thread on port {} to stop.", port);
                Thread.currentThread().interrupt();
            }
        }
        logger.info("SocketZabbixServerSimulator on port {} stopped.", port);
    }

    /**
     * Sets the JSON string to be sent back to the client as the "result" part of a Zabbix response.
     * The simulator will wrap this in the Zabbix Sender protocol.
     *
     * @param jsonResponse The JSON string to send.
     */
    public void setResponseJson(String jsonResponse) {
        this.jsonResponseToSend = jsonResponse;
    }

    /**
     * Gets the number of requests processed by the simulator since it was started.
     *
     * @return The number of requests.
     */
    public int getRequestCount() {
        return requestCount.get();
    }

    /**
     * Gets a list of all raw JSON payloads received by the simulator.
     * The list is a copy, so modifications to it won't affect the simulator's internal list.
     *
     * @return A list of received JSON payload strings.
     */
    public List<String> getReceivedPayloads() {
        return new ArrayList<>(receivedPayloads);
    }

    /**
     * Gets the last JSON payload received by the simulator.
     *
     * @return The last received JSON payload string, or null if no requests have been processed.
     */
    public String getLastReceivedPayload() {
        if (receivedPayloads.isEmpty()) {
            return null;
        }
        return receivedPayloads.get(receivedPayloads.size() - 1);
    }
}
