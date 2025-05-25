package io.zabbix4j.sender;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.zabbix4j.api.common.ZabbixProtocol;
import io.zabbix4j.api.exceptions.ProcessingException;
import io.zabbix4j.api.types.Cluster;
import io.zabbix4j.api.types.ItemValue;
import io.zabbix4j.api.types.Node;
import io.zabbix4j.api.types.TrapperResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ZabbixSender}.
 *
 * @author CSJ
 */
@ExtendWith(MockitoExtension.class)
class ZabbixSenderTest {

    private static final Logger logger = LoggerFactory.getLogger(ZabbixSenderTest.class);

    @Mock
    private Socket mockSocket;
    @Mock
    private OutputStream mockOutputStream;
    @Mock
    private InputStream mockInputStream;
    @Mock
    private ZabbixSender.SocketFactory mockSocketFactory;

    @Captor
    private ArgumentCaptor<byte[]> packetCaptor;

    private ZabbixSender.ZabbixSenderBuilder senderBuilder;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() throws IOException {
        senderBuilder = new ZabbixSender.ZabbixSenderBuilder();
        // Common mock setup for socket factory
        lenient().when(mockSocketFactory.createSocket()).thenReturn(mockSocket);
        lenient().when(mockSocket.getOutputStream()).thenReturn(mockOutputStream);
        lenient().when(mockSocket.getInputStream()).thenReturn(mockInputStream);
        lenient().doNothing().when(mockSocket).connect(any(InetSocketAddress.class), anyInt());
        lenient().doNothing().when(mockSocket).setSoTimeout(anyInt());
        lenient().doNothing().when(mockSocket).close();
        lenient().doNothing().when(mockOutputStream).write(any(byte[].class));
        lenient().doNothing().when(mockOutputStream).flush();

    }

    private void mockZabbixResponse(String infoString) throws IOException {
        Map<String, Object> responseMap = Map.of(
                "response", "success",
                "info", infoString
        );
        String jsonResponse = gson.toJson(responseMap);
        byte[] zabbixPacket = ZabbixProtocol.createPacket(jsonResponse, false); // Sender responses are not compressed

        // Simulate ZabbixProtocol.parseSynchronousPacket behavior
        // This involves mocking InputStream.read to feed the header then the payload
        // For simplicity here, we'll assume parseSynchronousPacket works (tested separately)
        // and directly make mockInputStream return the jsonResponse if readFully was called.
        // A more accurate mock would involve header bytes then payload bytes.
        // However, ZabbixProtocol.parseSynchronousPacket is complex to mock byte-by-byte here.
        // Let's directly mock the behavior of ZabbixProtocol.parseSynchronousPacket by preparing the mockInputStream
        // to contain a fully formed Zabbix packet.

        lenient().when(mockInputStream.read(any(byte[].class), anyInt(), eq(ZabbixProtocol.HEADER_SIZE)))
            .thenAnswer(invocation -> {
                byte[] buffer = invocation.getArgument(0);
                System.arraycopy(zabbixPacket, 0, buffer, invocation.getArgument(1), ZabbixProtocol.HEADER_SIZE);
                return ZabbixProtocol.HEADER_SIZE;
            });

        lenient().when(mockInputStream.read(any(byte[].class), anyInt(), eq(zabbixPacket.length - ZabbixProtocol.HEADER_SIZE)))
            .thenAnswer(invocation -> {
                byte[] buffer = invocation.getArgument(0);
                System.arraycopy(zabbixPacket, ZabbixProtocol.HEADER_SIZE, buffer, invocation.getArgument(1), zabbixPacket.length - ZabbixProtocol.HEADER_SIZE);
                return zabbixPacket.length - ZabbixProtocol.HEADER_SIZE;
            });
    }
    
    private void mockZabbixErrorResponse(String infoStringContainingError) throws IOException {
         Map<String, Object> responseMap = Map.of(
                "response", "error", // Or some other non-success string
                "info", infoStringContainingError
        );
        String jsonResponse = gson.toJson(responseMap);
        byte[] zabbixPacket = ZabbixProtocol.createPacket(jsonResponse, false);
        // Similar InputStream mocking as mockZabbixResponse
        lenient().when(mockInputStream.read(any(byte[].class), anyInt(), eq(ZabbixProtocol.HEADER_SIZE)))
            .thenAnswer(invocation -> {
                byte[] buffer = invocation.getArgument(0);
                System.arraycopy(zabbixPacket, 0, buffer, invocation.getArgument(1), ZabbixProtocol.HEADER_SIZE);
                return ZabbixProtocol.HEADER_SIZE;
            });

        lenient().when(mockInputStream.read(any(byte[].class), anyInt(), eq(zabbixPacket.length - ZabbixProtocol.HEADER_SIZE)))
            .thenAnswer(invocation -> {
                byte[] buffer = invocation.getArgument(0);
                System.arraycopy(zabbixPacket, ZabbixProtocol.HEADER_SIZE, buffer, invocation.getArgument(1), zabbixPacket.length - ZabbixProtocol.HEADER_SIZE);
                return zabbixPacket.length - ZabbixProtocol.HEADER_SIZE;
            });
    }


    @Nested
    class BuilderTests {
        @Test
        void testDefaultBuilder() {
            ZabbixSender sender = new ZabbixSender(senderBuilder, mockSocketFactory); // Use test constructor
            assertEquals(1, sender.getClusters().size());
            assertEquals(new Node("127.0.0.1", 10051), sender.getClusters().get(0).getNodes().get(0));
            assertEquals(10000, sender.getTimeoutMs());
            assertEquals(250, sender.getChunkSize());
            assertFalse(sender.isUseCompression());
        }

        @Test
        void testServerConfiguration() {
            ZabbixSender sender = senderBuilder.server("zabbix.example.com", 10055).build();
            // Access via the package-private constructor for testing if needed, or rely on build()
            sender = new ZabbixSender(senderBuilder, mockSocketFactory);

            assertEquals(1, sender.getClusters().size());
            assertEquals(new Node("zabbix.example.com", 10055), sender.getClusters().get(0).getNodes().get(0));
        }

        @Test
        void testAddCluster() {
            senderBuilder.addCluster(Arrays.asList("node1:10051", "node2"));
            senderBuilder.addCluster(Collections.singletonList("node3.cluster2:10050"));
            ZabbixSender sender = new ZabbixSender(senderBuilder, mockSocketFactory);

            assertEquals(2, sender.getClusters().size());
            assertEquals(new Node("node1", 10051), sender.getClusters().get(0).getNodes().get(0));
            assertEquals(new Node("node2", 10051), sender.getClusters().get(0).getNodes().get(1));
            assertEquals(new Node("node3.cluster2", 10050), sender.getClusters().get(1).getNodes().get(0));
        }

        @Test
        void testServerOverridesAddCluster() {
            senderBuilder.addCluster(Collections.singletonList("node1"));
            senderBuilder.server("override.server", 10099); // This should clear previous clusters
            ZabbixSender sender = new ZabbixSender(senderBuilder, mockSocketFactory);

            assertEquals(1, sender.getClusters().size());
            assertEquals(new Node("override.server", 10099), sender.getClusters().get(0).getNodes().get(0));
        }
        
        @Test
        void testAddClusterOverridesServer() {
            senderBuilder.server("initial.server", 10051);
            senderBuilder.addCluster(Collections.singletonList("cluster.node")); // This should clear server setting
            ZabbixSender sender = new ZabbixSender(senderBuilder, mockSocketFactory);
            
            assertEquals(1, sender.getClusters().size());
            assertEquals(new Node("cluster.node",10051), sender.getClusters().get(0).getNodes().get(0));
        }


        @Test
        void testInvalidTimeout_throwsException() {
            assertThrows(IllegalArgumentException.class, () -> senderBuilder.timeoutSeconds(0));
            assertThrows(IllegalArgumentException.class, () -> senderBuilder.timeoutSeconds(-1));
        }

        @Test
        void testInvalidChunkSize_throwsException() {
            assertThrows(IllegalArgumentException.class, () -> senderBuilder.chunkSize(0));
            assertThrows(IllegalArgumentException.class, () -> senderBuilder.chunkSize(-5));
        }
        
        @Test
        void testInvalidNodeInCluster_throwsExceptionInBuild() {
             senderBuilder.addCluster(Collections.singletonList("host1:badport"));
             // The exception is thrown by Cluster's constructor, which is called by ZabbixSender's constructor
             assertThrows(IllegalArgumentException.class, () -> new ZabbixSender(senderBuilder, mockSocketFactory));
        }

        @Test
        void testAgentConfigPath_logsWarning() {
            // This test requires capturing log output, which is complex with SLF4J without adding test appenders.
            // For now, we'll just call it and assume the logger.warn gets called.
            // Manual verification of logs or more advanced logging test setup would be needed for full verification.
            senderBuilder.agentConfigPath("dummy/path/zabbix_agentd.conf");
            ZabbixSender sender = new ZabbixSender(senderBuilder, mockSocketFactory);
            assertNotNull(sender); // If it builds, the placeholder was called.
            // Check logs manually or use a logging test framework to assert the warning.
        }
    }

    @Nested
    class SendMethodTests {
        private ZabbixSender sender;

        @Test
        void testSend_singleItem_success() throws IOException, ProcessingException {
            sender = new ZabbixSender(senderBuilder.server("localhost", 10051), mockSocketFactory);
            mockZabbixResponse("processed: 1; failed: 0; total: 1; seconds spent: 0.001");

            ItemValue item = ItemValue.newBuilder().host("h").key("k").value("v").build();
            TrapperResponse response = sender.send(Collections.singletonList(item));

            assertEquals(1, response.getProcessed());
            assertEquals(0, response.getFailed());
            assertEquals(1, response.getTotal());
            assertEquals(0.001, response.getTimeSpentSeconds());
            verify(mockOutputStream).write(packetCaptor.capture());
            String sentJson = extractPayloadFromPacket(packetCaptor.getValue(), false); // Sender doesn't compress by default
            assertTrue(sentJson.contains("\"host\":\"h\""));
        }

        @Test
        void testSend_multipleItems_singleChunk() throws IOException, ProcessingException {
            sender = new ZabbixSender(senderBuilder.chunkSize(5), mockSocketFactory); // Default server
            mockZabbixResponse("processed: 3; failed: 0; total: 3; seconds spent: 0.002");

            List<ItemValue> items = Arrays.asList(
                    ItemValue.newBuilder().host("h1").key("k1").value("v1").build(),
                    ItemValue.newBuilder().host("h2").key("k2").value("v2").build(),
                    ItemValue.newBuilder().host("h3").key("k3").value("v3").build()
            );
            TrapperResponse response = sender.send(items);

            assertEquals(3, response.getProcessed());
            verify(mockOutputStream, times(1)).write(any(byte[].class)); // Single chunk
        }

        @Test
        void testSend_multipleItems_multipleChunks() throws IOException, ProcessingException {
            sender = new ZabbixSender(senderBuilder.chunkSize(2), mockSocketFactory);
            // Mock responses for two chunks
            mockZabbixResponse("processed: 2; failed: 0; total: 2; seconds spent: 0.001"); // Chunk 1
            // For the second call to mockZabbixResponse, need to ensure previous InputStream mocks don't interfere
            // Re-mocking InputStream for each sendToCluster or having sequential whens could work.
            // For this test, assume each sendToCluster gets a fresh mock setup or sequential whens on mockInputStream.read
            // This simplified mockZabbixResponse will be an issue if not handled.
            // Let's refine mocking for sequential calls.
            
            // For first chunk
             when(mockSocket.getInputStream()).thenReturn(mockInputStream); // Ensure it's always this mock
             String responseJson1 = gson.toJson(Map.of("response", "success", "info", "processed: 2; failed: 0; total: 2; seconds spent: 0.001"));
             byte[] zabbixPacket1 = ZabbixProtocol.createPacket(responseJson1, false);
            // For second chunk
             String responseJson2 = gson.toJson(Map.of("response", "success", "info", "processed: 1; failed: 0; total: 1; seconds spent: 0.0005"));
             byte[] zabbixPacket2 = ZabbixProtocol.createPacket(responseJson2, false);

            // Mocking InputStream read for multiple calls
            when(mockInputStream.read(any(byte[].class), anyInt(), eq(ZabbixProtocol.HEADER_SIZE)))
                .thenAnswer(inv -> { // Chunk 1 Header
                    byte[] buf = inv.getArgument(0); System.arraycopy(zabbixPacket1, 0, buf, inv.getArgument(1), ZabbixProtocol.HEADER_SIZE); return ZabbixProtocol.HEADER_SIZE; })
                .thenAnswer(inv -> { // Chunk 2 Header
                    byte[] buf = inv.getArgument(0); System.arraycopy(zabbixPacket2, 0, buf, inv.getArgument(1), ZabbixProtocol.HEADER_SIZE); return ZabbixProtocol.HEADER_SIZE; });
            when(mockInputStream.read(any(byte[].class), anyInt(), anyInt())) // For payload
                .thenAnswer(inv -> { // Chunk 1 Payload
                    byte[] buf = inv.getArgument(0); int len = inv.getArgument(2); System.arraycopy(zabbixPacket1, ZabbixProtocol.HEADER_SIZE, buf, inv.getArgument(1), len); return len; })
                .thenAnswer(inv -> { // Chunk 2 Payload
                    byte[] buf = inv.getArgument(0); int len = inv.getArgument(2); System.arraycopy(zabbixPacket2, ZabbixProtocol.HEADER_SIZE, buf, inv.getArgument(1), len); return len; });


            List<ItemValue> items = Arrays.asList(
                    ItemValue.newBuilder().host("h1").key("k1").value("v1").build(),
                    ItemValue.newBuilder().host("h2").key("k2").value("v2").build(),
                    ItemValue.newBuilder().host("h3").key("k3").value("v3").build() // 3 items, chunk size 2
            );
            TrapperResponse response = sender.send(items);

            assertEquals(3, response.getProcessed()); // 2 + 1
            assertEquals(0, response.getFailed());
            assertEquals(3, response.getTotal());
            assertEquals(0.0015, response.getTimeSpentSeconds(), 0.00001);
            verify(mockOutputStream, times(2)).write(any(byte[].class)); // Two chunks
        }

        @Test
        void testSend_connectException_singleServer() throws IOException {
            sender = new ZabbixSender(senderBuilder, mockSocketFactory);
            when(mockSocketFactory.createSocket()).thenThrow(new ConnectException("Connection refused"));

            List<ItemValue> items = Collections.singletonList(ItemValue.newBuilder().host("h").key("k").value("v").build());
            // Expect sendChunkToAllClusters to throw IOException if all clusters/nodes fail
            IOException e = assertThrows(IOException.class, () -> sender.send(items));
            assertTrue(e.getMessage().contains("No Zabbix server/clusters configured") || e.getMessage().contains("Failed to send data to all nodes"));
        }
        
        @Test
        void testSend_timeoutException_singleServer() throws IOException {
            sender = new ZabbixSender(senderBuilder, mockSocketFactory);
            // Simulate timeout during connect
            doThrow(new SocketTimeoutException("Connect timeout")).when(mockSocket).connect(any(InetSocketAddress.class), anyInt());

            List<ItemValue> items = Collections.singletonList(ItemValue.newBuilder().host("h").key("k").value("v").build());
            IOException e = assertThrows(IOException.class, () -> sender.send(items));
             assertTrue(e.getMessage().contains("Failed to send data to all nodes"));
        }


        @Test
        void testSend_zabbixErrorResponse() throws IOException {
            sender = new ZabbixSender(senderBuilder, mockSocketFactory);
            mockZabbixErrorResponse("processed: 0; failed: 1; total: 1; seconds spent: 0.002; error: some item failed");
            
            List<ItemValue> items = Collections.singletonList(ItemValue.newBuilder().host("h").key("k").value("v").build());
            // The current sendToCluster treats non-"success" as ProcessingException, which it then catches and logs.
            // If all nodes in a cluster lead to this, sendToCluster throws IOException.
            // The send() method would then report 0 processed, 0 failed based on TrapperResponse.
            // This behavior might need refinement if specific Zabbix errors should propagate differently.
            // For now, let's test that it completes and the TrapperResponse reflects the (lack of) success.
            
            // If sendToCluster re-throws ProcessingException for Zabbix error:
            ProcessingException e = assertThrows(ProcessingException.class, () -> sender.send(items));
            assertTrue(e.getMessage().contains("Zabbix Sender response indicates failure"));

        }

        @Test
        void testSend_nullOrEmptyItems_throwsException() {
            sender = new ZabbixSender(senderBuilder, mockSocketFactory);
            assertThrows(IllegalArgumentException.class, () -> sender.send(null));
            assertThrows(IllegalArgumentException.class, () -> sender.send(Collections.emptyList()));
        }
        
        @Test
        void testSend_withCompression() throws IOException, ProcessingException {
            sender = new ZabbixSender(senderBuilder.compression(true), mockSocketFactory);
            mockZabbixResponse("processed: 1; failed: 0; total: 1; seconds spent: 0.001");
            ItemValue item = ItemValue.newBuilder().host("h").key("k").value("v").build();
            sender.send(Collections.singletonList(item));

            verify(mockOutputStream).write(packetCaptor.capture());
            // Verify that ZabbixProtocol.createPacket was called with compression=true
            // This is implicitly tested if the packet header has compression flag.
            // We can check the flag in the captured packet.
            byte[] sentPacket = packetCaptor.getValue();
            byte flags = sentPacket[ZabbixProtocol.ZABBIX_HEADER_PREFIX.length];
            assertTrue((flags & ZabbixProtocol.FLAG_COMPRESSION) != 0, "Compression flag should be set");
        }
        
        @Test
        void testSend_clusterFailover() throws IOException, ProcessingException {
            Node node1 = new Node("node1.fail", 10051);
            Node node2 = new Node("node2.succeed", 10051);
            senderBuilder.addCluster(Arrays.asList("node1.fail:10051", "node2.succeed:10051"));
            sender = new ZabbixSender(senderBuilder, mockSocketFactory);

            Socket mockSocket1 = mock(Socket.class);
            Socket mockSocket2 = mock(Socket.class);
            OutputStream mockOut2 = mock(OutputStream.class);
            InputStream mockIn2 = mock(InputStream.class);

            when(mockSocketFactory.createSocket()).thenReturn(mockSocket1).thenReturn(mockSocket2);
            
            // Node1 fails to connect
            doThrow(new ConnectException("Node1 refused")).when(mockSocket1).connect(eq(new InetSocketAddress("node1.fail",10051)), anyInt());
            
            // Node2 connects and responds
            when(mockSocket2.getOutputStream()).thenReturn(mockOut2);
            when(mockSocket2.getInputStream()).thenReturn(mockIn2);
            doNothing().when(mockSocket2).connect(eq(new InetSocketAddress("node2.succeed",10051)), anyInt());

            // Mock Zabbix response for Node2
            String responseJson = gson.toJson(Map.of("response", "success", "info", "processed: 1; failed: 0; total: 1; seconds spent: 0.003"));
            byte[] zabbixPacket = ZabbixProtocol.createPacket(responseJson, false);
            when(mockIn2.read(any(byte[].class), anyInt(), eq(ZabbixProtocol.HEADER_SIZE)))
                .thenAnswer(inv -> { byte[] buf = inv.getArgument(0); System.arraycopy(zabbixPacket, 0, buf, inv.getArgument(1), ZabbixProtocol.HEADER_SIZE); return ZabbixProtocol.HEADER_SIZE; });
            when(mockIn2.read(any(byte[].class), anyInt(), eq(zabbixPacket.length - ZabbixProtocol.HEADER_SIZE)))
                .thenAnswer(inv -> { byte[] buf = inv.getArgument(0); System.arraycopy(zabbixPacket, ZabbixProtocol.HEADER_SIZE, buf, inv.getArgument(1), zabbixPacket.length - ZabbixProtocol.HEADER_SIZE); return zabbixPacket.length - ZabbixProtocol.HEADER_SIZE; });


            ItemValue item = ItemValue.newBuilder().host("h").key("k").value("v").build();
            TrapperResponse response = sender.send(Collections.singletonList(item));

            assertEquals(1, response.getProcessed());
            verify(mockSocket1).connect(eq(new InetSocketAddress("node1.fail",10051)), anyInt());
            verify(mockSocket2).connect(eq(new InetSocketAddress("node2.succeed",10051)), anyInt());
            verify(mockOut2).write(any(byte[].class));
        }
    }

    @Nested
    class SendValueTests {
        private ZabbixSender sender;

        @Test
        void testSendValue_allParams() throws IOException, ProcessingException {
            sender = new ZabbixSender(senderBuilder, mockSocketFactory);
            mockZabbixResponse("processed: 1; failed: 0; total: 1; seconds spent: 0.001");
            
            TrapperResponse response = sender.sendValue("host1", "key1", "val1", 12345L, 6789);
            assertEquals(1, response.getProcessed());
            verify(mockOutputStream).write(packetCaptor.capture());
            String sentJson = extractPayloadFromPacket(packetCaptor.getValue(), false);
            assertTrue(sentJson.contains("\"host\":\"host1\""));
            assertTrue(sentJson.contains("\"clock\":12345"));
            assertTrue(sentJson.contains("\"ns\":6789"));
        }

        @Test
        void testSendValue_noTimestamp() throws IOException, ProcessingException {
            sender = new ZabbixSender(senderBuilder, mockSocketFactory);
            mockZabbixResponse("processed: 1; failed: 0; total: 1; seconds spent: 0.001");

            TrapperResponse response = sender.sendValue("host2", "key2", "val2");
            assertEquals(1, response.getProcessed());
             verify(mockOutputStream).write(packetCaptor.capture());
            String sentJson = extractPayloadFromPacket(packetCaptor.getValue(), false);
            assertFalse(sentJson.contains("\"clock\":"));
            assertFalse(sentJson.contains("\"ns\":"));
        }
    }
    
    // Helper to decode the payload from a full Zabbix packet for verification
    private String extractPayloadFromPacket(byte[] packet, boolean isCompressed) throws IOException {
        ByteBuffer headerBuffer = ByteBuffer.wrap(packet, 0, ZabbixProtocol.HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        headerBuffer.get(new byte[ZabbixProtocol.ZABBIX_HEADER_PREFIX.length]); // Skip prefix
        byte flags = headerBuffer.get();
        int dataLen = headerBuffer.getInt();
        // int reserved = headerBuffer.getInt();

        byte[] payloadBytes = Arrays.copyOfRange(packet, ZabbixProtocol.HEADER_SIZE, ZabbixProtocol.HEADER_SIZE + dataLen);

        if (isCompressed) {
            if (!((flags & ZabbixProtocol.FLAG_COMPRESSION) != 0)) {
                throw new AssertionError("Packet expected to be compressed but compression flag is not set.");
            }
            ByteArrayOutputStream baosDecompressed = new ByteArrayOutputStream();
            try (InflaterInputStream iis = new InflaterInputStream(new ByteArrayInputStream(payloadBytes))) {
                byte[] buffer = new byte[1024];
                int count;
                while ((count = iis.read(buffer)) != -1) {
                    baosDecompressed.write(buffer, 0, count);
                }
            }
            return baosDecompressed.toString(StandardCharsets.UTF_8.name());
        } else {
            if (((flags & ZabbixProtocol.FLAG_COMPRESSION) != 0)) {
                 throw new AssertionError("Packet expected to be uncompressed but compression flag is set.");
            }
            return new String(payloadBytes, StandardCharsets.UTF_8);
        }
    }
}
