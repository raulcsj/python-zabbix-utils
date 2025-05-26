package io.zabbix4j.api;

import io.zabbix4j.api.exception.ZabbixProcessingException;
import io.zabbix4j.api.protocol.ZabbixProtocol;
import io.zabbix4j.api.types.AgentResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.ConnectException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
// No Mockito needed if using the real AsyncGetter and NioZabbixAgentSimulator

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Unit and integration tests for the {@link AsyncGetter} class,
 * using a {@link NioZabbixAgentSimulator} for network interactions.
 *
 * @author CSJ
 */
@Timeout(10) // Default timeout for all tests in this class (seconds)
class AsyncGetterTest {

    private NioZabbixAgentSimulator simulator;
    private static final int SIMULATOR_PORT = 10058; // Use a specific port for testing
    private static final String SIMULATOR_HOST = "127.0.0.1";

    @BeforeEach
    void setUp() throws IOException {
        simulator = new NioZabbixAgentSimulator();
        // Simulator started on demand in tests that need it
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (simulator != null) {
            simulator.stop();
            // Wait for the simulator to fully stop and release resources if necessary
            // simulator.awaitConnectionHandled(1, TimeUnit.SECONDS); // Ensure server socket closed
        }
    }

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {
        @Test
        @DisplayName("Full constructor should set all fields correctly")
        void testFullConstructor() {
            AsyncGetter getter = new AsyncGetter("host1", 10055, 5000, "192.168.1.100");
            assertNotNull(getter);
            assertEquals("host1", getter.getHost()); // Assuming getters are added for testing or fields are package-private
            assertEquals(10055, getter.getPort());
            assertEquals(5000, getter.getTimeout());
            assertEquals("192.168.1.100", getter.getSourceIp());
        }

        @Test
        @DisplayName("Constructor without source IP")
        void testConstructor_NoSourceIp() {
            AsyncGetter getter = new AsyncGetter("host2", 10050, 3000);
            assertNotNull(getter);
            assertNull(getter.getSourceIp());
        }

        @Test
        @DisplayName("Constructor with default timeout")
        void testConstructor_DefaultTimeout() {
            AsyncGetter getter = new AsyncGetter("host4", 10050);
            assertNotNull(getter);
            assertEquals(AsyncGetter.DEFAULT_TIMEOUT_MS, getter.getTimeout());
        }

        @Test
        @DisplayName("Constructor with default port and timeout")
        void testConstructor_DefaultPortAndTimeout() {
            AsyncGetter getter = new AsyncGetter("host5");
            assertNotNull(getter);
            assertEquals(AsyncGetter.DEFAULT_ZABBIX_AGENT_PORT, getter.getPort());
            assertEquals(AsyncGetter.DEFAULT_TIMEOUT_MS, getter.getTimeout());
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"  "})
        @DisplayName("Constructor should throw IllegalArgumentException for invalid host")
        void testConstructor_InvalidHost(String invalidHost) {
            Exception e = assertThrows(IllegalArgumentException.class,
                () -> new AsyncGetter(invalidHost, AsyncGetter.DEFAULT_ZABBIX_AGENT_PORT, AsyncGetter.DEFAULT_TIMEOUT_MS, null));
            assertEquals("Host cannot be null or empty.", e.getMessage());
        }

        @ParameterizedTest
        @ValueSource(ints = {0, -1, 65536})
        @DisplayName("Constructor should throw IllegalArgumentException for invalid port")
        void testConstructor_InvalidPort(int invalidPort) {
            Exception e = assertThrows(IllegalArgumentException.class,
                () -> new AsyncGetter("localhost", invalidPort, AsyncGetter.DEFAULT_TIMEOUT_MS, null));
            assertEquals("Port number must be between 1 and 65535. Got: " + invalidPort, e.getMessage());
        }

        @ParameterizedTest
        @ValueSource(ints = {0, -1})
        @DisplayName("Constructor should throw IllegalArgumentException for non-positive timeout")
        void testConstructor_InvalidTimeout(int invalidTimeout) {
            Exception e = assertThrows(IllegalArgumentException.class,
                () -> new AsyncGetter("localhost", AsyncGetter.DEFAULT_ZABBIX_AGENT_PORT, invalidTimeout, null));
            assertEquals("Timeout must be positive. Got: " + invalidTimeout, e.getMessage());
        }
    }

    @Nested
    @DisplayName("get(String key) Method Tests")
    class GetMethodTests {

        private void startSimulatorWithResponse(String responsePayload) throws IOException, ZabbixProcessingException {
            byte[] responsePacket = ZabbixProtocol.createPacket(responsePayload, false);
            simulator.setResponsePacket(responsePacket);
            simulator.start(SIMULATOR_PORT);
        }
        
        private void startSimulatorWithRawResponse(byte[] responsePacket) throws IOException {
            simulator.setResponsePacket(responsePacket);
            simulator.start(SIMULATOR_PORT);
        }


        @Test
        @DisplayName("get() successful response with simple value")
        void testGet_SuccessfulResponse() throws Exception {
            String keyValue = "agent.ping";
            String expectedResponseValue = "1";
            startSimulatorWithResponse(expectedResponseValue);

            AsyncGetter getter = new AsyncGetter(SIMULATOR_HOST, SIMULATOR_PORT, 5000);
            CompletableFuture<AgentResponse> future = getter.get(keyValue);

            AgentResponse response = future.get(6, TimeUnit.SECONDS); // Wait for completion
            simulator.awaitConnectionHandled(1, TimeUnit.SECONDS);


            assertNotNull(response);
            assertEquals(expectedResponseValue, response.getValue());
            assertNull(response.getError());
            assertFalse(response.hasError());
        }

        @Test
        @DisplayName("get() should handle ZBX_NOTSUPPORTED simple response")
        void testGet_AgentReturnsZbxNotSupportedSimple() throws Exception {
            String rawAgentResponse = "ZBX_NOTSUPPORTED";
            startSimulatorWithResponse(rawAgentResponse);

            AsyncGetter getter = new AsyncGetter(SIMULATOR_HOST, SIMULATOR_PORT, 5000);
            CompletableFuture<AgentResponse> future = getter.get("unsupported.key1");
            AgentResponse response = future.get(6, TimeUnit.SECONDS);
            simulator.awaitConnectionHandled(1, TimeUnit.SECONDS);

            assertNotNull(response);
            assertEquals(rawAgentResponse, response.getRawResponse());
            assertNull(response.getValue());
            assertEquals("Not supported by Zabbix Agent", response.getError());
            assertTrue(response.hasError());
        }

        @Test
        @DisplayName("get() should handle ZBX_NOTSUPPORTED with details response")
        void testGet_AgentReturnsZbxNotSupportedWithDetails() throws Exception {
            String details = "This item is not supported on this platform.";
            String rawAgentResponse = "ZBX_NOTSUPPORTED\0" + details;
            // Note: ZabbixProtocol.createPacket will treat this as a literal string,
            // but ZabbixProtocol.parsePacket on the client side will interpret it correctly if the agent sent it this way.
            // The simulator just sends the bytes it's given.
            startSimulatorWithResponse(rawAgentResponse);


            AsyncGetter getter = new AsyncGetter(SIMULATOR_HOST, SIMULATOR_PORT, 5000);
            CompletableFuture<AgentResponse> future = getter.get("unsupported.key2");
            AgentResponse response = future.get(6, TimeUnit.SECONDS);
            simulator.awaitConnectionHandled(1, TimeUnit.SECONDS);

            assertNotNull(response);
            assertEquals(rawAgentResponse, response.getRawResponse()); // Raw response includes the details part
            assertNull(response.getValue()); // Value is null
            assertEquals(details, response.getError()); // Error is the detail string
            assertTrue(response.hasError());
        }

        @Test
        @DisplayName("get() should throw ZabbixProcessingException on connection timeout")
        void testGet_ConnectionTimeout() {
            // Using a non-routable IP address to simulate timeout
            AsyncGetter getter = new AsyncGetter("10.255.255.1", 12345, 100); // Very short timeout
            CompletableFuture<AgentResponse> future = getter.get("some.key");

            ExecutionException ex = assertThrows(ExecutionException.class, () -> future.get(500, TimeUnit.MILLISECONDS));
            assertTrue(ex.getCause() instanceof ZabbixProcessingException, "Cause should be ZabbixProcessingException");
            assertTrue(ex.getCause().getMessage().contains("Timeout sending to node") || ex.getCause().getMessage().contains("Failed to connect"),
                       "Message should indicate timeout or connection failure. Actual: " + ex.getCause().getMessage());
        }

        @Test
        @DisplayName("get() should throw ZabbixProcessingException on connection refused")
        void testGet_ConnectionRefused() {
            // Simulator is not started on SIMULATOR_PORT + 1
            AsyncGetter getter = new AsyncGetter(SIMULATOR_HOST, SIMULATOR_PORT + 1, 500);
            CompletableFuture<AgentResponse> future = getter.get("some.key");

            ExecutionException ex = assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
            assertTrue(ex.getCause() instanceof ZabbixProcessingException, "Cause should be ZabbixProcessingException");
            assertTrue(ex.getCause().getCause() instanceof ConnectException || ex.getCause().getMessage().contains("Failed to connect"),
                       "Root cause should be ConnectException or message indicates connection failure. Actual: " + ex.getCause().getMessage());
        }
        
        @Test
        @DisplayName("get() with null key should throw IllegalArgumentException synchronously")
        void testGet_NullKey() {
            AsyncGetter getter = new AsyncGetter(SIMULATOR_HOST, SIMULATOR_PORT);
            Exception e = assertThrows(IllegalArgumentException.class, () -> getter.get(null));
            assertEquals("Item key cannot be null or empty.", e.getMessage());
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "  "})
        @DisplayName("get() with empty or blank key should throw IllegalArgumentException synchronously")
        void testGet_EmptyOrBlankKey(String invalidKey) {
            AsyncGetter getter = new AsyncGetter(SIMULATOR_HOST, SIMULATOR_PORT);
            Exception e = assertThrows(IllegalArgumentException.class, () -> getter.get(invalidKey));
            assertEquals("Item key cannot be null or empty.", e.getMessage());
        }
        
        @Test
        @DisplayName("get() should handle server closing connection before sending response")
        void testGet_ServerClosesEarly_NoResponse() throws IOException, InterruptedException {
            simulator.setResponsePacket(null); // Simulator will close after reading
            simulator.start(SIMULATOR_PORT);

            AsyncGetter getter = new AsyncGetter(SIMULATOR_HOST, SIMULATOR_PORT, 500);
            CompletableFuture<AgentResponse> future = getter.get("test.key");
            
            ExecutionException ex = assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
            assertTrue(ex.getCause() instanceof ZabbixProcessingException);
            assertTrue(ex.getCause().getMessage().contains("Incomplete header read") || ex.getCause().getMessage().contains("Failed to read header"));
            simulator.awaitConnectionHandled(1, TimeUnit.SECONDS);
        }

        @Test
        @DisplayName("get() should handle server sending incomplete header")
        void testGet_ServerSendsIncompleteHeader() throws IOException, InterruptedException {
            byte[] incompleteHeader = {'Z', 'B', 'X', 'D', 0x01}; // Only 5 bytes
            startSimulatorWithRawResponse(incompleteHeader);

            AsyncGetter getter = new AsyncGetter(SIMULATOR_HOST, SIMULATOR_PORT, 500);
            CompletableFuture<AgentResponse> future = getter.get("test.key");

            ExecutionException ex = assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
            assertTrue(ex.getCause() instanceof ZabbixProcessingException);
            assertTrue(ex.getCause().getMessage().contains("Incomplete header read"));
            simulator.awaitConnectionHandled(1, TimeUnit.SECONDS);
        }
    }
}
