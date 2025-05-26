package io.zabbix4j.api;

import io.zabbix4j.api.exception.ZabbixProcessingException;
import io.zabbix4j.api.types.Cluster;
import io.zabbix4j.api.types.ItemValue;
import io.zabbix4j.api.types.Node;
import io.zabbix4j.api.types.TrapperResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;


import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Unit and integration-style tests for the {@link AsyncSender} class,
 * using a {@link NioZabbixServerSimulator} for network interactions.
 *
 * @author CSJ
 */
@Timeout(15) // Default timeout for all tests in this class (seconds)
class AsyncSenderTest {

    private NioZabbixServerSimulator simulator1;
    private NioZabbixServerSimulator simulator2;
    private static final int SIMULATOR1_PORT = 10071; // Distinct ports for AsyncSender tests
    private static final int SIMULATOR2_PORT = 10072;
    private static final String SIMULATOR_HOST = "127.0.0.1";

    @TempDir
    Path tempDir;

    private ListAppender<ILoggingEvent> listAppender;

    @BeforeEach
    void setUp() {
        Logger senderLogger = (Logger) LoggerFactory.getLogger(AsyncSender.class);
        listAppender = new ListAppender<>();
        listAppender.start();
        senderLogger.addAppender(listAppender);
        senderLogger.setLevel(Level.DEBUG);

        // Silence NioZabbixServerSimulator logs unless debugging tests
        Logger simLogger = (Logger) LoggerFactory.getLogger(NioZabbixServerSimulator.class);
        simLogger.setLevel(Level.WARN); 
    }

    @AfterEach
    void tearDown() {
        if (simulator1 != null) {
            simulator1.stop();
        }
        if (simulator2 != null) {
            simulator2.stop();
        }
        Logger senderLogger = (Logger) LoggerFactory.getLogger(AsyncSender.class);
        senderLogger.detachAppender(listAppender);
        listAppender.stop();
    }

    private void startSimulator1(String responseJson) throws IOException {
        simulator1 = new NioZabbixServerSimulator();
        simulator1.setJsonResponse(responseJson);
        simulator1.start(SIMULATOR1_PORT);
    }
    
    private void startSimulator2(String responseJson) throws IOException {
        simulator2 = new NioZabbixServerSimulator();
        simulator2.setJsonResponse(responseJson);
        simulator2.start(SIMULATOR2_PORT);
    }


    @Nested
    @DisplayName("Builder and Constructor Tests")
    class BuilderTests {
        @Test
        @DisplayName("Build with single server and port")
        void testBuild_SingleServer() {
            AsyncSender sender = AsyncSender.builder()
                .server(SIMULATOR_HOST, SIMULATOR1_PORT)
                .timeout(500, TimeUnit.MILLISECONDS)
                .build();
            assertNotNull(sender);
            assertEquals(1, sender.getClusters().size());
            assertEquals(new Node(SIMULATOR_HOST, SIMULATOR1_PORT), sender.getClusters().get(0).getNodes().get(0));
            assertEquals(500, sender.getTimeout());
        }

        @Test
        @DisplayName("Build with agent config file (reuse Sender's parser logic)")
        void testBuild_WithAgentConfigFile() throws IOException, ZabbixProcessingException {
            Path configFile = tempDir.resolve("zabbix_agentd_sender_test.conf");
            List<String> lines = Arrays.asList(
                "ServerActive=127.0.0.1:10088;127.0.0.2", // Different ports to avoid conflict
                "SourceIP=192.168.1.55",
                "TLSConnect=cert",
                "TLSCAFile=/path/to/ca_async.pem"
            );
            Files.write(configFile, lines, StandardCharsets.UTF_8);

            AsyncSender sender = AsyncSender.builder()
                .agentConfigPath(configFile.toString())
                .build();

            assertNotNull(sender);
            // The actual parsing and merging logic is inside the Builder's loadConfigurationFromAgent
            // which calls Sender.parseAgentConfiguration. The test for Sender.Builder already verifies this logic in detail.
            // Here, we mainly check that the call path doesn't break and some values are loaded if not overridden.
            // The warning log from AsyncSender.Builder.loadConfigurationFromAgent indicates the conceptual nature of data access.
            // For this test, we assume if it builds without error and default server list is empty, it tried to load.
            // To properly verify, Sender.SenderConfigData would need to be public with getters.
            // For now, we trust the call is made. If no servers added explicitly and config path is bad, it would fail.
            // Here, we are checking that it can be built.
            // If the config path was used and ServerActive was empty or invalid, it would fail at build due to no servers.
            // This means it likely populated clusters from the file.
            assertFalse(sender.getClusters().isEmpty(), "Clusters should be populated from agent config if not overridden.");
            // We can't directly assert specific values without making SenderConfigData public or adding getters.
            // The fact that it builds and clusters is not empty is an indicator.
        }

        @Test
        @DisplayName("Build without server definition should throw IllegalArgumentException")
        void testBuild_NoServerDefined_ThrowsException() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> AsyncSender.builder().build());
            assertEquals("No Zabbix servers/proxies defined.", e.getMessage());
        }
    }

    @Nested
    @DisplayName("send(List<ItemValue> items) and sendValue(...) Tests")
    class SendMethodTests {
        final String successResponseJsonSingle = "{\"response\":\"success\",\"info\":\"processed: 1; failed: 0; total: 1; seconds spent: 0.001\"}";
        final String successResponseJsonMulti(int count) {
            return String.format("{\"response\":\"success\",\"info\":\"processed: %d; failed: 0; total: %d; seconds spent: 0.00%d\"}", count, count, count);
        }
        final String failureResponseJsonSingle = "{\"response\":\"success\",\"info\":\"processed: 0; failed: 1; total: 1; details: item error\"}";


        @Test
        @DisplayName("send single ItemValue to single server successfully")
        void testSend_SingleItem_SingleServer_Success() throws Exception {
            startSimulator1(successResponseJsonSingle);
            AsyncSender sender = AsyncSender.builder().server(SIMULATOR_HOST, SIMULATOR1_PORT).timeout(2, TimeUnit.SECONDS).build();
            ItemValue item = new ItemValue("AsyncHost", "async.key", "async_value");

            CompletableFuture<TrapperResponse> future = sender.send(Collections.singletonList(item));
            TrapperResponse response = future.get(5, TimeUnit.SECONDS);
            simulator1.awaitConnectionHandled(1, TimeUnit.SECONDS);


            assertNotNull(response);
            assertEquals(1, response.getProcessed());
            assertEquals(0, response.getFailed());
            assertEquals(1, response.getTotal());
            assertEquals(1, simulator1.getRequestCount());
            String lastPayload = simulator1.getLastReceivedJsonPayload();
            assertNotNull(lastPayload);
            assertTrue(lastPayload.contains("\"host\":\"AsyncHost\""));
        }

        @Test
        @DisplayName("send multiple items with chunking")
        void testSend_MultipleItems_Chunking() throws Exception {
            simulator1 = new NioZabbixServerSimulator(); // Manual start/stop for multi-response
            simulator1.start(SIMULATOR1_PORT);

            AsyncSender sender = AsyncSender.builder()
                .server(SIMULATOR_HOST, SIMULATOR1_PORT)
                .chunkSize(2)
                .timeout(2, TimeUnit.SECONDS)
                .build();

            List<ItemValue> items = Arrays.asList(
                new ItemValue("HostA", "key1", "val1"),
                new ItemValue("HostA", "key2", "val2"),
                new ItemValue("HostB", "key3", "val3")
            );
            
            // Expect 2 chunks. Chunk 1 (2 items), Chunk 2 (1 item)
            // Simulator needs to handle two separate connections/requests.
            // The NioZabbixServerSimulator is designed to handle multiple connections sequentially in its accept loop.
            
            // Set response for the first chunk
            simulator1.setJsonResponse(successResponseJsonMulti(2));
            CompletableFuture<TrapperResponse> future = sender.send(items);
            
            // Wait for the first chunk to be processed by the simulator
            assertTrue(simulator1.awaitConnectionHandled(3, TimeUnit.SECONDS), "Simulator did not handle first connection in time.");
            assertEquals(1, simulator1.getRequestCount()); // First chunk processed
            
            // Set response for the second chunk
            // Need a way for the simulator to reset its internal state or have a queue of responses.
            // For this test, since the simulator handles one connection at a time and closes it,
            // the second request from the Sender will be a new connection.
            simulator1.setJsonResponse(successResponseJsonSingle); 
            // The future is already running, we assume it will make the second call.
            
            TrapperResponse totalResponse = future.get(8, TimeUnit.SECONDS); // Wait for all chunks to complete

            // Wait for the second connection to be handled as well
            // This depends on how quickly the second request is made after the first one.
            // A better way would be for the simulator to count down a latch for *each* connection.
            // For now, let's just check the total request count.
            // We might need a small delay or a more robust synchronization for the simulator's second request.
            int finalRequestCount = 0;
            for(int i=0; i<5; ++i) { // Poll for a short time
                finalRequestCount = simulator1.getRequestCount();
                if (finalRequestCount >= 2) break;
                Thread.sleep(200);
            }
            assertEquals(2, finalRequestCount, "Simulator should have received two requests due to chunking.");

            assertNotNull(totalResponse);
            assertEquals(3, totalResponse.getProcessed());
            assertEquals(0, totalResponse.getFailed());
            assertEquals(3, totalResponse.getTotal());
        }


        @Test
        @DisplayName("send with compression enabled")
        void testSend_CompressionEnabled() throws Exception {
            startSimulator1(successResponseJsonSingle);
            AsyncSender sender = AsyncSender.builder()
                .server(SIMULATOR_HOST, SIMULATOR1_PORT)
                .enableCompression(true)
                .timeout(2, TimeUnit.SECONDS)
                .build();
            ItemValue item = new ItemValue("AsyncCompressHost", "async.compress.key", "long value for async compression");

            TrapperResponse response = sender.sendValue(item.getHost(), item.getKey(), item.getValue()).get(5, TimeUnit.SECONDS);
            simulator1.awaitConnectionHandled(1, TimeUnit.SECONDS);
            assertEquals(1, response.getProcessed());
            assertEquals(1, simulator1.getRequestCount());
        }

        @Test
        @DisplayName("send when server returns item failure in info")
        void testSend_ServerReturnsItemFailure() throws Exception {
            startSimulator1(failureResponseJsonSingle);
            AsyncSender sender = AsyncSender.builder().server(SIMULATOR_HOST, SIMULATOR1_PORT).build();
            TrapperResponse response = sender.sendValue("AsyncTestHost", "async.failing.key", "val").get(5, TimeUnit.SECONDS);
            simulator1.awaitConnectionHandled(1, TimeUnit.SECONDS);

            assertEquals(0, response.getProcessed());
            assertEquals(1, response.getFailed());
            assertEquals(1, response.getTotal());
        }

        @Test
        @DisplayName("send when server returns non-success response field should mark items as failed")
        void testSend_ServerReturnsNonSuccessResponse() throws Exception {
            startSimulator1("{\"response\":\"error\", \"info\":\"server melted\"}");
            AsyncSender sender = AsyncSender.builder().server(SIMULATOR_HOST, SIMULATOR1_PORT).timeout(1, TimeUnit.SECONDS).build();
            
            TrapperResponse response = sender.sendValue("AsyncTestHost", "key.err.async", "val").get(5, TimeUnit.SECONDS);
            simulator1.awaitConnectionHandled(1, TimeUnit.SECONDS);

            assertEquals(0, response.getProcessed(), "No items should be processed on server error response.");
            assertEquals(1, response.getFailed(), "Item should be marked as failed.");
            assertEquals(1, response.getTotal());
        }

        @Test
        @DisplayName("send to non-responsive port should complete exceptionally then result in failed items")
        void testSend_ConnectionRefused() throws InterruptedException {
            // Simulator is NOT started for this port
            AsyncSender sender = AsyncSender.builder().server(SIMULATOR_HOST, SIMULATOR1_PORT + 10).timeout(100, TimeUnit.MILLISECONDS).build();
            CompletableFuture<TrapperResponse> future = sender.sendValue("AsyncTestHost", "key.conn.async", "val");

            TrapperResponse response = future.join(); // This will rethrow if the future completed exceptionally with a non-checked exception
                                                    // or return the result from the handle stage.
            assertEquals(0, response.getProcessed());
            assertEquals(1, response.getFailed());
            assertEquals(1, response.getTotal());
        }
        
        @Test
        @DisplayName("send with read timeout (server hangs) should result in failed items")
        void testSend_ReadTimeout() throws IOException, InterruptedException {
            simulator1 = new NioZabbixServerSimulator();
            simulator1.setHangBeforeResponse(true); // Configure simulator to hang
            simulator1.start(SIMULATOR1_PORT);

            AsyncSender sender = AsyncSender.builder().server(SIMULATOR_HOST, SIMULATOR1_PORT).timeout(200, TimeUnit.MILLISECONDS).build();
            CompletableFuture<TrapperResponse> future = sender.sendValue("AsyncTestHost", "key.timeout.async", "val");

            TrapperResponse response = future.join();
            simulator1.awaitConnectionHandled(1, TimeUnit.SECONDS); // ensure interaction attempt

            assertEquals(0, response.getProcessed());
            assertEquals(1, response.getFailed());
            assertEquals(1, response.getTotal());
        }


        @Test
        @DisplayName("send with cluster failover successful on second node")
        void testSend_ClusterFailover_SuccessOnSecondNode() throws Exception {
            // Simulator 1 (node1) will not be started (or will fail)
            simulator2 = new NioZabbixServerSimulator();
            simulator2.setJsonResponse(successResponseJsonSingle);
            simulator2.start(SIMULATOR2_PORT);

            Cluster cluster = new Cluster(Arrays.asList(
                SIMULATOR_HOST + ":" + SIMULATOR1_PORT,     // This one will fail
                SIMULATOR_HOST + ":" + SIMULATOR2_PORT      // This one should succeed
            ));
            AsyncSender sender = AsyncSender.builder().cluster(cluster).timeout(300, TimeUnit.MILLISECONDS).build();
            ItemValue item = new ItemValue("AsyncFailoverHost", "async.fkey", "val");

            TrapperResponse response = sender.send(Collections.singletonList(item)).get(5, TimeUnit.SECONDS);
            simulator2.awaitConnectionHandled(1, TimeUnit.SECONDS);

            assertEquals(1, response.getProcessed());
            assertEquals(0, response.getFailed());
            assertEquals(1, response.getTotal());
            assertEquals(0, simulator1 != null ? simulator1.getRequestCount() : 0);
            assertEquals(1, simulator2.getRequestCount());
        }
        
        @Test
        @DisplayName("send with redirect response should log and mark as failed for that node attempt")
        void testSend_RedirectResponse_LogsAndFailsAttempt() throws Exception {
            String redirectJson = "{\"response\":\"success\",\"info\":\"processed: 0; failed: 0; total: 0; seconds spent: 0.000\",\"redirect\":\"" + SIMULATOR_HOST + ":" + SIMULATOR2_PORT + "\"}";
            startSimulator1(redirectJson);

            AsyncSender sender = AsyncSender.builder().server(SIMULATOR_HOST, SIMULATOR1_PORT).build();
            ItemValue item = new ItemValue("AsyncRedirectHost", "redirect.key.async", "val");

            TrapperResponse response = sender.send(Collections.singletonList(item)).get(5, TimeUnit.SECONDS);
            simulator1.awaitConnectionHandled(1, TimeUnit.SECONDS);
            
            assertEquals(0, response.getProcessed());
            assertEquals(1, response.getFailed()); // Failed because redirect is not followed and treated as an error for this node

            assertTrue(listAppender.list.stream()
                .anyMatch(event -> event.getLevel() == Level.WARN &&
                                   event.getFormattedMessage().contains("Redirected by " + SIMULATOR_HOST+":"+SIMULATOR1_PORT)),
                "Redirect warning should be logged for async sender.");
        }


        @Test
        @DisplayName("sendValue convenience methods should delegate correctly")
        void testSendValue_ConvenienceMethods() throws Exception {
            startSimulator1(successResponseJsonSingle);
            AsyncSender sender = AsyncSender.builder().server(SIMULATOR_HOST, SIMULATOR1_PORT).build();

            TrapperResponse r1 = sender.sendValue("AsyncHostX", "keyX.async", "valX").get(5, TimeUnit.SECONDS);
            simulator1.awaitConnectionHandled(1, TimeUnit.SECONDS); // Wait for first send
            assertEquals(1, r1.getProcessed());

            simulator1.setJsonResponse(successResponseJsonSingle); // Reset response for next call
            long clock = System.currentTimeMillis() / 1000L;
            TrapperResponse r2 = sender.sendValue("AsyncHostY", "keyY.async", "valY", clock, 123).get(5, TimeUnit.SECONDS);
            simulator1.awaitConnectionHandled(1, TimeUnit.SECONDS); // Wait for second send
            assertEquals(1, r2.getProcessed());
            
            assertEquals(2, simulator1.getRequestCount());
        }

        @Test
        @DisplayName("send with null items list should complete exceptionally with IllegalArgumentException")
        void testSend_NullItemsList() {
            AsyncSender sender = AsyncSender.builder().server(SIMULATOR_HOST, SIMULATOR1_PORT).build();
            CompletableFuture<TrapperResponse> future = sender.send(null);
            ExecutionException ex = assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
            assertTrue(ex.getCause() instanceof IllegalArgumentException);
            assertEquals("Items list cannot be null or empty.", ex.getCause().getMessage());
        }

        @Test
        @DisplayName("send with list containing null ItemValue should complete exceptionally with IllegalArgumentException")
        void testSend_ListWithNullItem() {
            AsyncSender sender = AsyncSender.builder().server(SIMULATOR_HOST, SIMULATOR1_PORT).build();
            List<ItemValue> itemsWithNull = new ArrayList<>();
            itemsWithNull.add(new ItemValue("H", "k", "v"));
            itemsWithNull.add(null);
            CompletableFuture<TrapperResponse> future = sender.send(itemsWithNull);
            ExecutionException ex = assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
            assertTrue(ex.getCause() instanceof IllegalArgumentException);
            assertEquals("ItemValue within the list cannot be null.", ex.getCause().getMessage());
        }
    }
}
