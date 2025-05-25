package io.zabbix4j.getter;

import io.zabbix4j.api.common.ZabbixProtocol;
import io.zabbix4j.api.exceptions.ProcessingException;
import io.zabbix4j.api.types.AgentResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ZabbixGetter}.
 *
 * @author CSJ
 */
@ExtendWith(MockitoExtension.class)
class ZabbixGetterTest {

    @Mock
    private Socket mockSocket;
    @Mock
    private OutputStream mockOutputStream;
    @Mock
    private InputStream mockInputStream;
    @Mock
    private ZabbixGetter.SocketFactory mockSocketFactory;

    @Captor
    private ArgumentCaptor<byte[]> packetCaptor;

    private ZabbixGetter.ZabbixGetterBuilder getterBuilder;

    @BeforeEach
    void setUp() throws IOException {
        getterBuilder = new ZabbixGetter.ZabbixGetterBuilder();
        // Common mock setup for socket factory
        lenient().when(mockSocketFactory.createSocket()).thenReturn(mockSocket);
        lenient().when(mockSocket.getOutputStream()).thenReturn(mockOutputStream);
        lenient().when(mockSocket.getInputStream()).thenReturn(mockInputStream);
        lenient().doNothing().when(mockSocket).connect(any(InetSocketAddress.class), anyInt());
        lenient().doNothing().when(mockSocket).setSoTimeout(anyInt());
        lenient().doNothing().when(mockSocket).close(); // Important for try-with-resources
        lenient().doNothing().when(mockOutputStream).write(any(byte[].class));
        lenient().doNothing().when(mockOutputStream).flush();
    }

    private void mockAgentRawResponse(String rawAgentData) throws IOException {
        // Construct a full Zabbix protocol packet (header + payload)
        byte[] payloadBytes = rawAgentData.getBytes(StandardCharsets.UTF_8);
        ByteBuffer packetBuffer = ByteBuffer.allocate(ZabbixProtocol.HEADER_SIZE + payloadBytes.length);
        packetBuffer.order(ByteOrder.LITTLE_ENDIAN);
        packetBuffer.put(ZabbixProtocol.ZABBIX_HEADER_PREFIX);
        packetBuffer.put(ZabbixProtocol.FLAG_PROTOCOL_VERSION); // No compression for getter
        packetBuffer.putInt(payloadBytes.length);
        packetBuffer.putInt(0); // Reserved
        packetBuffer.put(payloadBytes);
        byte[] fullZabbixPacket = packetBuffer.array();

        // Mock InputStream to return this packet
        when(mockInputStream.read(any(byte[].class), anyInt(), eq(ZabbixProtocol.HEADER_SIZE)))
            .thenAnswer(invocation -> {
                byte[] buffer = invocation.getArgument(0);
                System.arraycopy(fullZabbixPacket, 0, buffer, invocation.getArgument(1), ZabbixProtocol.HEADER_SIZE);
                return ZabbixProtocol.HEADER_SIZE;
            });

        when(mockInputStream.read(any(byte[].class), anyInt(), eq(payloadBytes.length)))
            .thenAnswer(invocation -> {
                byte[] buffer = invocation.getArgument(0);
                System.arraycopy(fullZabbixPacket, ZabbixProtocol.HEADER_SIZE, buffer, invocation.getArgument(1), payloadBytes.length);
                return payloadBytes.length;
            });
        // Handle potential further reads returning -1 (EOF)
        lenient().when(mockInputStream.read(any(byte[].class), anyInt(), anyInt())).thenReturn(-1);
    }


    @Nested
    class BuilderTests {
        @Test
        void testDefaultBuilder() {
            ZabbixGetter getter = new ZabbixGetter(getterBuilder, mockSocketFactory); // Use test constructor
            assertEquals("127.0.0.1", getter.agentHost);
            assertEquals(10050, getter.agentPort);
            assertEquals(10000, getter.timeoutMs);
            assertFalse(getter.useIpv6);
            assertNull(getter.sourceIp);
        }

        @Test
        void testValidConfigurations() {
            ZabbixGetter getter = getterBuilder
                    .host("zabbix.agent.local")
                    .port(10055)
                    .timeoutSeconds(5)
                    .useIpv6(true)
                    .sourceIp("192.168.1.100")
                    .build(); // Uses default Socket::new
            // Reconstruct with mock factory for field inspection if fields are not public
            getter = new ZabbixGetter(getterBuilder, mockSocketFactory);

            assertEquals("zabbix.agent.local", getter.agentHost);
            assertEquals(10055, getter.agentPort);
            assertEquals(5000, getter.timeoutMs);
            assertTrue(getter.useIpv6);
            assertEquals("192.168.1.100", getter.sourceIp);
        }

        @Test
        void testInvalidHost_throwsException() {
            assertThrows(IllegalArgumentException.class, () -> getterBuilder.host(null));
            assertThrows(IllegalArgumentException.class, () -> getterBuilder.host(""));
            assertThrows(IllegalArgumentException.class, () -> getterBuilder.host("  "));
        }

        @Test
        void testInvalidPort_throwsException() {
            assertThrows(IllegalArgumentException.class, () -> getterBuilder.port(0));
            assertThrows(IllegalArgumentException.class, () -> getterBuilder.port(65536));
            assertThrows(IllegalArgumentException.class, () -> getterBuilder.port(-1));
        }

        @Test
        void testInvalidTimeout_throwsException() {
            assertThrows(IllegalArgumentException.class, () -> getterBuilder.timeoutSeconds(0));
            assertThrows(IllegalArgumentException.class, () -> getterBuilder.timeoutSeconds(-5));
        }
    }

    @Nested
    class GetMethodTests {
        private ZabbixGetter getter;

        @Test
        void testGet_successful() throws IOException, ProcessingException {
            getter = new ZabbixGetter(getterBuilder.host("localhost").port(10050), mockSocketFactory);
            String agentValue = "123.45";
            mockAgentRawResponse(agentValue);

            AgentResponse response = getter.get("system.cpu.load");

            assertNotNull(response);
            assertEquals(agentValue, response.getValue());
            assertNull(response.getError());
            assertFalse(response.hasError());

            verify(mockOutputStream).write(packetCaptor.capture());
            String sentPayload = new String(packetCaptor.getValue(), ZabbixProtocol.HEADER_SIZE, packetCaptor.getValue().length - ZabbixProtocol.HEADER_SIZE, StandardCharsets.UTF_8);
            assertEquals("system.cpu.load", sentPayload.trim()); // Trim to remove potential null chars if not perfectly sized
            verify(mockSocket).connect(new InetSocketAddress("localhost", 10050), 10000);
            verify(mockSocket).close();
        }

        @Test
        void testGet_agentReturnsZbxNotSupported_simple() throws IOException, ProcessingException {
            getter = new ZabbixGetter(getterBuilder, mockSocketFactory);
            mockAgentRawResponse("ZBX_NOTSUPPORTED");

            AgentResponse response = getter.get("unsupported.item");

            assertNotNull(response);
            assertNull(response.getValue());
            assertEquals("Not supported by Zabbix Agent", response.getError());
            assertTrue(response.hasError());
        }

        @Test
        void testGet_agentReturnsZbxNotSupported_withMessage() throws IOException, ProcessingException {
            getter = new ZabbixGetter(getterBuilder, mockSocketFactory);
            String errorMessage = "Detailed reason for not supporting.";
            mockAgentRawResponse("ZBX_NOTSUPPORTED\0" + errorMessage);

            AgentResponse response = getter.get("another.unsupported.item");

            assertNotNull(response);
            assertNull(response.getValue());
            assertEquals(errorMessage, response.getError());
            assertTrue(response.hasError());
        }

        @Test
        void testGet_connectTimeout() throws IOException {
            getter = new ZabbixGetter(getterBuilder.timeoutSeconds(1), mockSocketFactory);
            doThrow(new SocketTimeoutException("Connect timeout")).when(mockSocket).connect(any(InetSocketAddress.class), eq(1000));

            ProcessingException e = assertThrows(ProcessingException.class, () -> getter.get("some.key"));
            assertTrue(e.getMessage().contains("Timeout connecting to or reading from Zabbix agent"));
        }

        @Test
        void testGet_connectException() throws IOException {
            getter = new ZabbixGetter(getterBuilder, mockSocketFactory);
            doThrow(new ConnectException("Connection refused")).when(mockSocket).connect(any(InetSocketAddress.class), anyInt());

            ProcessingException e = assertThrows(ProcessingException.class, () -> getter.get("some.key"));
            assertTrue(e.getMessage().contains("Connection refused by Zabbix agent"));
        }

        @Test
        void testGet_outputStreamWriteError() throws IOException {
            getter = new ZabbixGetter(getterBuilder, mockSocketFactory);
            doThrow(new IOException("Broken pipe")).when(mockOutputStream).write(any(byte[].class));

            ProcessingException e = assertThrows(ProcessingException.class, () -> getter.get("some.key"));
            assertTrue(e.getMessage().contains("IOException during communication") && e.getMessage().contains("Broken pipe"));
        }

        @Test
        void testGet_inputStreamReadError_duringHeader() throws IOException {
            getter = new ZabbixGetter(getterBuilder, mockSocketFactory);
            // Simulate error when ZabbixProtocol.parseSynchronousPacket tries to read header
            when(mockInputStream.read(any(byte[].class), anyInt(), eq(ZabbixProtocol.HEADER_SIZE)))
                .thenThrow(new IOException("Read error during header"));

            ProcessingException e = assertThrows(ProcessingException.class, () -> getter.get("some.key"));
            // This exception comes from ZabbixProtocol.readFully, wrapped by parseSynchronousPacket, then by get()
            assertTrue(e.getMessage().contains("IOException during communication") || e.getMessage().contains("Failed to execute API request due to network or I/O error"));
        }
        
        @Test
        void testGet_inputStreamReadError_duringPayload() throws IOException {
            getter = new ZabbixGetter(getterBuilder, mockSocketFactory);

            // Mock successful header read
            String fakePayload = "test";
            byte[] payloadBytes = fakePayload.getBytes(StandardCharsets.UTF_8);
            ByteBuffer header = ByteBuffer.allocate(ZabbixProtocol.HEADER_SIZE);
            header.order(ByteOrder.LITTLE_ENDIAN);
            header.put(ZabbixProtocol.ZABBIX_HEADER_PREFIX);
            header.put(ZabbixProtocol.FLAG_PROTOCOL_VERSION);
            header.putInt(payloadBytes.length);
            header.putInt(0);

            when(mockInputStream.read(any(byte[].class), anyInt(), eq(ZabbixProtocol.HEADER_SIZE)))
                .thenAnswer(invocation -> {
                    byte[] buffer = invocation.getArgument(0);
                    System.arraycopy(header.array(), 0, buffer, invocation.getArgument(1), ZabbixProtocol.HEADER_SIZE);
                    return ZabbixProtocol.HEADER_SIZE;
                });

            // Simulate error when ZabbixProtocol.parseSynchronousPacket tries to read payload
            when(mockInputStream.read(any(byte[].class), anyInt(), eq(payloadBytes.length)))
                .thenThrow(new IOException("Read error during payload"));
            
            ProcessingException e = assertThrows(ProcessingException.class, () -> getter.get("some.key"));
            assertTrue(e.getMessage().contains("IOException during communication") || e.getMessage().contains("Failed to execute API request due to network or I/O error"));
        }


        @Test
        void testGet_invalidItemKey_null() {
            getter = new ZabbixGetter(getterBuilder, mockSocketFactory);
            assertThrows(IllegalArgumentException.class, () -> getter.get(null));
        }

        @Test
        void testGet_invalidItemKey_empty() {
            getter = new ZabbixGetter(getterBuilder, mockSocketFactory);
            assertThrows(IllegalArgumentException.class, () -> getter.get(""));
        }

        @Test
        void testGet_invalidItemKey_blank() {
            getter = new ZabbixGetter(getterBuilder, mockSocketFactory);
            assertThrows(IllegalArgumentException.class, () -> getter.get("   "));
        }

        @Test
        void testGet_withSourceIpBinding() throws IOException, ProcessingException {
            getter = new ZabbixGetter(getterBuilder.sourceIp("10.0.0.1"), mockSocketFactory);
            mockAgentRawResponse("value_from_specific_ip");
            getter.get("network.test");
            verify(mockSocket).bind(eq(new InetSocketAddress("10.0.0.1", 0)));
        }

        @Test
        void testGet_withSourceIpBinding_fails() throws IOException, ProcessingException {
            // Simulate bind failure
            doThrow(new IOException("Cannot assign requested address")).when(mockSocket).bind(any(InetSocketAddress.class));
            getter = new ZabbixGetter(getterBuilder.sourceIp("invalid.source.ip"), mockSocketFactory);
            mockAgentRawResponse("value_anyway"); // Should still proceed
            
            AgentResponse response = getter.get("network.test.bindfail");
            assertEquals("value_anyway", response.getValue());
            // Verify connect was still called, etc.
            verify(mockSocket).connect(any(InetSocketAddress.class), anyInt());
        }
    }
}
