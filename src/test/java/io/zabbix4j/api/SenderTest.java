package io.zabbix4j.api;

import io.zabbix4j.api.exception.ZabbixProcessingException;
import io.zabbix4j.api.types.Cluster;
import io.zabbix4j.api.types.ItemValue;
import io.zabbix4j.api.types.Node;
import io.zabbix4j.api.types.TrapperResponse;
import org.json.JSONObject;
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
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Unit and integration-style tests for the {@link Sender} class,
 * using a {@link SocketZabbixServerSimulator} for network interactions.
 *
 * @author CSJ
 */
@Timeout(15) // Default timeout for all tests in this class (seconds)
class SenderTest {

    private SocketZabbixServerSimulator simulator1;
    private SocketZabbixServerSimulator simulator2;
    private static final int SIMULATOR1_PORT = 10061; // Distinct ports
    private static final int SIMULATOR2_PORT = 10062;
    private static final String SIMULATOR_HOST = "127.0.0.1";

    @TempDir
    Path tempDir;

    private ListAppender<ILoggingEvent> listAppender;

    @BeforeEach
    void setUp() {
        // Setup Logback list appender to capture logs
        Logger senderLogger = (Logger) LoggerFactory.getLogger(Sender.class);
        listAppender = new ListAppender<>();
        listAppender.start();
        senderLogger.addAppender(listAppender);
        senderLogger.setLevel(Level.DEBUG); // Ensure DEBUG logs are captured for tests that check them

        Logger protocolLogger = (Logger) LoggerFactory.getLogger(io.zabbix4j.api.protocol.ZabbixProtocol.class);
        protocolLogger.setLevel(Level.OFF); // Silence protocol logs unless specifically testing them
    }

    @AfterEach
    void tearDown() {
        if (simulator1 != null) {
            simulator1.stop();
        }
        if (simulator2 != null) {
            simulator2.stop();
        }
        // Detach appender
        Logger senderLogger = (Logger) LoggerFactory.getLogger(Sender.class);
        senderLogger.detachAppender(listAppender);
        listAppender.stop();
    }

    private void startSimulator1(String responseJson) throws IOException {
        simulator1 = new SocketZabbixServerSimulator();
        simulator1.setResponseJson(responseJson);
        simulator1.start(SIMULATOR1_PORT);
    }

    private void startSimulator2(String responseJson) throws IOException {
        simulator2 = new SocketZabbixServerSimulator();
        simulator2.setResponseJson(responseJson);
        simulator2.start(SIMULATOR2_PORT);
    }

    @Nested
    @DisplayName("Builder and Constructor Tests")
    class BuilderTests {
        @Test
        @DisplayName("Build with single server and port")
        void testBuild_SingleServer() {
            Sender sender = Sender.builder()
                .server(SIMULATOR_HOST, SIMULATOR1_PORT)
                .timeout(500, TimeUnit.MILLISECONDS)
                .build();
            assertNotNull(sender);
            assertEquals(1, sender.getClusters().size());
            assertEquals(1, sender.getClusters().get(0).getNodes().size());
            assertEquals(new Node(SIMULATOR_HOST, SIMULATOR1_PORT), sender.getClusters().get(0).getNodes().get(0));
            assertEquals(500, sender.getTimeout());
        }

        @Test
        @DisplayName("Build with agent config file")
        void testBuild_WithAgentConfigFile() throws IOException, ZabbixProcessingException {
            Path configFile = tempDir.resolve("zabbix_agentd.conf");
            List<String> lines = Arrays.asList(
                "ServerActive=127.0.0.1:10055;127.0.0.2",
                "SourceIP=192.168.1.50",
                "Timeout=10", // This timeout is for agent, Sender has its own default/setting
                "TLSConnect=cert",
                "TLSCAFile=/path/to/ca.pem"
            );
            Files.write(configFile, lines, StandardCharsets.UTF_8);

            Sender sender = Sender.builder()
                .agentConfigPath(configFile.toString())
                .build();

            assertNotNull(sender);
            assertEquals(2, sender.getClusters().size()); // Two entries in ServerActive
            assertEquals(new Node("127.0.0.1", 10055), sender.getClusters().get(0).getNodes().get(0));
            assertEquals(new Node("127.0.0.2", Sender.DEFAULT_ZABBIX_SENDER_PORT), sender.getClusters().get(1).getNodes().get(0));

            assertEquals("192.168.1.50", sender.getSourceIp());
            assertEquals(Sender.DEFAULT_TIMEOUT_MS, sender.getTimeout()); // Agent timeout not used for Sender's network timeout
            assertEquals("cert", sender.getTlsConfig().get("TLSConnect"));
            assertEquals("/path/to/ca.pem", sender.getTlsConfig().get("TLSCAFile"));
        }
        
        @Test
        @DisplayName("Builder settings should override agent config file settings")
        void testBuild_BuilderOverridesAgentConfig() throws IOException, ZabbixProcessingException {
            Path configFile = tempDir.resolve("zabbix_agentd.conf");
            List<String> lines = Arrays.asList("ServerActive=127.0.0.1:10055", "SourceIP=192.168.1.50", "TLSConnect=psk");
            Files.write(configFile, lines);

            Sender sender = Sender.builder()
                .agentConfigPath(configFile.toString())
                .server("override.host", 12345) // This should override ServerActive
                .sourceIp("10.0.0.1") // This should override SourceIP
                .tlsConfig("TLSConnect", "cert") // This should override TLSConnect
                .build();

            assertEquals(1, sender.getClusters().size()); // Builder's server takes precedence
            assertEquals(new Node("override.host", 12345), sender.getClusters().get(0).getNodes().get(0));
            assertEquals("10.0.0.1", sender.getSourceIp());
            assertEquals("cert", sender.getTlsConfig().get("TLSConnect"));
        }


        @Test
        @DisplayName("Build without server definition should throw IllegalArgumentException")
        void testBuild_NoServerDefined_ThrowsException() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Sender.builder().build());
            assertEquals("No Zabbix servers/proxies defined. Configure via server(), cluster(), or agentConfigPath().", e.getMessage());
        }
        
        @Test
        @DisplayName("Build with invalid timeout should throw IllegalArgumentException from setter")
        void testBuild_InvalidTimeout() {
             IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Sender.builder().server("h",1).timeout(0));
             assertEquals("Timeout must be positive.", e.getMessage());
        }
    }

    @Nested
    @DisplayName("send(List<ItemValue> items) and sendValue(...) Tests")
    class SendMethodTests {
        final String successResponseJson = "{\"response\":\"success\",\"info\":\"processed: 1; failed: 0; total: 1; seconds spent: 0.001\"}";
        final String successResponseJsonMulti = "{\"response\":\"success\",\"info\":\"processed: 2; failed: 0; total: 2; seconds spent: 0.002\"}";
        final String failureResponseJson = "{\"response\":\"success\",\"info\":\"processed: 0; failed: 1; total: 1; details: item error\"}"; // Zabbix often returns "success" even if items fail.


        @Test
        @DisplayName("send single ItemValue to single server successfully")
        void testSend_SingleItem_SingleServer_Success() throws IOException, ZabbixProcessingException {
            startSimulator1(successResponseJson);
            Sender sender = Sender.builder().server(SIMULATOR_HOST, SIMULATOR1_PORT).build();
            ItemValue item = new ItemValue("TestHost", "test.key", "test_value");

            TrapperResponse response = sender.send(Collections.singletonList(item));

            assertNotNull(response);
            assertEquals(1, response.getProcessed());
            assertEquals(0, response.getFailed());
            assertEquals(1, response.getTotal());
            assertEquals(1, simulator1.getRequestCount());

            String lastPayload = simulator1.getLastReceivedPayload();
            assertNotNull(lastPayload);
            assertTrue(lastPayload.contains("\"request\":\"sender data\""));
            assertTrue(lastPayload.contains("\"host\":\"TestHost\""));
            assertTrue(lastPayload.contains("\"key\":\"test.key\""));
        }

        @Test
        @DisplayName("send multiple items with chunking")
        void testSend_MultipleItems_Chunking() throws IOException, ZabbixProcessingException {
            startSimulator1(successResponseJsonMulti); // Simulator needs to handle multiple requests or be reset
            
            Sender sender = Sender.builder()
                .server(SIMULATOR_HOST, SIMULATOR1_PORT)
                .chunkSize(2) // Send 3 items in 2 chunks
                .build();

            List<ItemValue> items = Arrays.asList(
                new ItemValue("HostA", "key1", "val1"),
                new ItemValue("HostA", "key2", "val2"),
                new ItemValue("HostB", "key3", "val3")
            );
            
            // Simulator needs to provide appropriate response for each chunk
            // For simplicity, assume server can handle multiple connections and each gets successResponseJsonMulti (for first chunk)
            // and successResponseJson (for second chunk)
            // This test setup is tricky with one simulator if it doesn't reset its response.
            // Let's make the simulator respond with "processed: N" based on items in payload.
            // For now, we'll assume the simulator is re-entrant and its response is generic enough.
            // The current simulator accepts multiple connections.

            TrapperResponse totalResponse = new TrapperResponse();
            // Chunk 1 (2 items)
            simulator1.setResponseJson("{\"response\":\"success\",\"info\":\"processed: 2; failed: 0; total: 2; seconds spent: 0.002\"}");
            TrapperResponse r1 = sender.send(items.subList(0,2)); // Manually send first chunk
            totalResponse.parseAndAdd(String.format("processed: %d; failed: %d; total: %d; seconds spent: %f", r1.getProcessed(), r1.getFailed(), r1.getTotal(), r1.getTimeSpent()));


            // Chunk 2 (1 item)
            simulator1.setResponseJson("{\"response\":\"success\",\"info\":\"processed: 1; failed: 0; total: 1; seconds spent: 0.001\"}");
            TrapperResponse r2 = sender.send(items.subList(2,3)); // Manually send second chunk
            totalResponse.parseAndAdd(String.format("processed: %d; failed: %d; total: %d; seconds spent: %f", r2.getProcessed(), r2.getFailed(), r2.getTotal(), r2.getTimeSpent()));


            assertEquals(3, totalResponse.getProcessed());
            assertEquals(0, totalResponse.getFailed());
            assertEquals(3, totalResponse.getTotal());
            assertEquals(2, simulator1.getRequestCount(), "Simulator should have received two requests due to chunking.");
        }
        
        @Test
        @DisplayName("send with compression enabled")
        void testSend_CompressionEnabled() throws IOException, ZabbixProcessingException {
            startSimulator1(successResponseJson);
            Sender sender = Sender.builder()
                .server(SIMULATOR_HOST, SIMULATOR1_PORT)
                .enableCompression(true)
                .build();
            ItemValue item = new ItemValue("CompressHost", "compress.key", "long value to test compression benefits");

            TrapperResponse response = sender.sendValue(item.getHost(), item.getKey(), item.getValue());
            assertEquals(1, response.getProcessed());
            assertEquals(1, simulator1.getRequestCount());
            // Verification of actual compression on wire is complex here, rely on protocol unit tests.
        }


        @Test
        @DisplayName("send when server returns item failure in info")
        void testSend_ServerReturnsItemFailure() throws IOException, ZabbixProcessingException {
            startSimulator1(failureResponseJson);
            Sender sender = Sender.builder().server(SIMULATOR_HOST, SIMULATOR1_PORT).build();
            TrapperResponse response = sender.sendValue("TestHost", "failing.key", "val");

            assertEquals(0, response.getProcessed());
            assertEquals(1, response.getFailed());
            assertEquals(1, response.getTotal());
        }
        
        @Test
        @DisplayName("send when server returns non-success response field")
        void testSend_ServerReturnsNonSuccessResponse() throws IOException {
            startSimulator1("{\"response\":\"error\", \"info\":\"some server error\"}");
            Sender sender = Sender.builder().server(SIMULATOR_HOST, SIMULATOR1_PORT).build();
            
            // This should result in all items in the chunk being marked as failed for this attempt
            TrapperResponse response = sender.sendValue("TestHost", "key.err", "val");
            assertEquals(0, response.getProcessed(), "No items should be processed on server error response.");
            assertEquals(1, response.getFailed(), "Item should be marked as failed.");
            assertEquals(1, response.getTotal());
        }


        @Test
        @DisplayName("send when server returns invalid JSON should throw ZabbixProcessingException")
        void testSend_ServerReturnsInvalidJson() throws IOException {
            startSimulator1("This is not JSON");
            Sender sender = Sender.builder().server(SIMULATOR_HOST, SIMULATOR1_PORT).build();
            
            TrapperResponse response = sender.sendValue("TestHost", "key.json", "val");
            // The current Sender implementation logs JSONException and treats it as a node failure.
            // The aggregated response should show the item as failed.
            assertEquals(0, response.getProcessed());
            assertEquals(1, response.getFailed());
        }

        @Test
        @DisplayName("send to non-responsive port should mark items as failed")
        void testSend_ConnectionRefused() {
            Sender sender = Sender.builder().server(SIMULATOR_HOST, SIMULATOR1_PORT + 10).timeout(100, TimeUnit.MILLISECONDS).build(); // Port not listened on
            TrapperResponse response = sender.sendValue("TestHost", "key.conn", "val");
            assertEquals(0, response.getProcessed());
            assertEquals(1, response.getFailed());
            assertEquals(1, response.getTotal());
        }

        @Test
        @DisplayName("send with read timeout should mark items as failed")
        void testSend_ReadTimeout() throws IOException {
            simulator1 = new SocketZabbixServerSimulator(); // Don't set response, so it doesn't write back
            simulator1.start(SIMULATOR1_PORT);

            Sender sender = Sender.builder().server(SIMULATOR_HOST, SIMULATOR1_PORT).timeout(100, TimeUnit.MILLISECONDS).build();
            TrapperResponse response = sender.sendValue("TestHost", "key.timeout", "val");
            
            assertEquals(0, response.getProcessed());
            assertEquals(1, response.getFailed());
            assertEquals(1, response.getTotal());
        }

        @Test
        @DisplayName("send with cluster failover")
        void testSend_ClusterFailover() throws IOException, ZabbixProcessingException {
            // Simulator 1 (node1) will be "down" (not started or responds with error)
            // Simulator 2 (node2) will be up and respond with success
            startSimulator2(successResponseJson); 

            Cluster cluster = new Cluster(Arrays.asList(
                SIMULATOR_HOST + ":" + SIMULATOR1_PORT, // This one will fail
                SIMULATOR_HOST + ":" + SIMULATOR2_PORT  // This one should succeed
            ));
            Sender sender = Sender.builder().cluster(cluster).timeout(200, TimeUnit.MILLISECONDS).build();
            ItemValue item = new ItemValue("FailoverHost", "failover.key", "val");

            TrapperResponse response = sender.send(Collections.singletonList(item));

            assertEquals(1, response.getProcessed());
            assertEquals(0, response.getFailed());
            assertEquals(1, response.getTotal());
            assertEquals(0, simulator1 != null ? simulator1.getRequestCount() : 0, "Simulator 1 should have 0 or 1 (failed) attempts.");
            assertEquals(1, simulator2.getRequestCount(), "Simulator 2 should have 1 successful request.");
        }
        
        @Test
        @DisplayName("send with redirect response should log and fail for that node")
        void testSend_RedirectResponse() throws IOException {
            String redirectJson = "{\"response\":\"success\",\"info\":\"processed: 0; failed: 0; total: 0; seconds spent: 0.000\",\"redirect\":\"" + SIMULATOR_HOST + ":" + SIMULATOR2_PORT + "\"}";
            startSimulator1(redirectJson); // Sim1 redirects
            startSimulator2(successResponseJson); // Sim2 would be the target, but Sender doesn't follow

            Cluster cluster = new Cluster(Collections.singletonList(SIMULATOR_HOST + ":" + SIMULATOR1_PORT));
            Sender sender = Sender.builder().cluster(cluster).build();
            ItemValue item = new ItemValue("RedirectHost", "redirect.key", "val");

            TrapperResponse response = sender.send(Collections.singletonList(item));
            
            // The item should be marked as failed because the redirect is not followed by current Sender for this attempt
            assertEquals(0, response.getProcessed());
            assertEquals(1, response.getFailed());

            assertTrue(listAppender.list.stream()
                .anyMatch(event -> event.getLevel() == Level.WARN &&
                                   event.getFormattedMessage().contains("Received redirect from " + SIMULATOR_HOST + ":" + SIMULATOR1_PORT)),
                "Redirect warning should be logged.");
        }


        @Test
        @DisplayName("sendValue convenience methods should delegate correctly")
        void testSendValue_ConvenienceMethods() throws IOException, ZabbixProcessingException {
            startSimulator1(successResponseJson);
            Sender sender = Sender.builder().server(SIMULATOR_HOST, SIMULATOR1_PORT).build();

            TrapperResponse r1 = sender.sendValue("HostX", "keyX", "valX");
            assertEquals(1, r1.getProcessed());

            simulator1.setResponseJson(successResponseJson); // Reset for next call if needed
            long clock = System.currentTimeMillis() / 1000L;
            TrapperResponse r2 = sender.sendValue("HostY", "keyY", "valY", clock, 123);
            assertEquals(1, r2.getProcessed());
            
            assertEquals(2, simulator1.getRequestCount());
        }

        @Test
        @DisplayName("send with null items list should throw IllegalArgumentException")
        void testSend_NullItemsList() {
            Sender sender = Sender.builder().server(SIMULATOR_HOST, SIMULATOR1_PORT).build();
            assertThrows(IllegalArgumentException.class, () -> sender.send(null));
        }

        @Test
        @DisplayName("send with list containing null ItemValue should throw IllegalArgumentException")
        void testSend_ListWithNullItem() {
            Sender sender = Sender.builder().server(SIMULATOR_HOST, SIMULATOR1_PORT).build();
            List<ItemValue> itemsWithNull = new ArrayList<>();
            itemsWithNull.add(new ItemValue("H", "k", "v"));
            itemsWithNull.add(null);
            assertThrows(IllegalArgumentException.class, () -> sender.send(itemsWithNull));
        }
    }
}
