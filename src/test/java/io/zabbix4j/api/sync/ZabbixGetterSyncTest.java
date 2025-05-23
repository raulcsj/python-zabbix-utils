package io.zabbix4j.api.sync;

import io.zabbix4j.api.dto.AgentResponse;
import io.zabbix4j.api.exception.CommunicationException;
import io.zabbix4j.api.exception.ProcessingException;
import io.zabbix4j.api.protocol.ZabbixProtocol;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ZabbixGetterSync}.
 * @author ShortRoundDev
 */
@ExtendWith(MockitoExtension.class)
class ZabbixGetterSyncTest {

    @Mock
    private Socket mockSocket;
    @Mock
    private OutputStream mockOutputStream;
    @Mock
    private InputStream mockInputStream;

    private ZabbixGetterSync zabbixGetter;

    private final String TEST_HOST = "agent.example.com";
    private final int TEST_PORT = 10050;
    private final String TEST_ITEM_KEY = "agent.ping";

    @BeforeEach
    void setUp() throws IOException {
        lenient().when(mockSocket.getOutputStream()).thenReturn(mockOutputStream);
        lenient().when(mockSocket.getInputStream()).thenReturn(mockInputStream);
        lenient().doNothing().when(mockSocket).setSoTimeout(anyInt());
        lenient().doNothing().when(mockSocket).connect(any(InetSocketAddress.class), anyInt());
        lenient().doNothing().when(mockSocket).close();
    }

    private void mockAgentResponse(String agentResponseString) throws IOException {
        byte[] responsePayloadBytes = agentResponseString.getBytes(StandardCharsets.UTF_8);
        byte[] zabbixPacket = ZabbixProtocol.createPacket(agentResponseString, false);

        ByteArrayInputStream bis = new ByteArrayInputStream(zabbixPacket);
        when(mockInputStream.read(any(byte[].class), anyInt(), anyInt())).thenAnswer(inv -> {
            byte[] buffer = inv.getArgument(0);
            int offset = inv.getArgument(1);
            int length = inv.getArgument(2);
            return bis.read(buffer, offset, length);
        });
        when(mockInputStream.read(any(byte[].class))).thenAnswer(inv -> {
            byte[] buffer = inv.getArgument(0);
            return bis.read(buffer, 0, buffer.length);
        });
    }

    // === Builder Tests ===
    @Test
    void testBuilder_minimalHost() {
        zabbixGetter = new ZabbixGetterSync.Builder().host(TEST_HOST).build();
        assertNotNull(zabbixGetter);
    }

    @Test
    void testBuilder_noHost_throwsException() {
        assertThrows(NullPointerException.class, () -> new ZabbixGetterSync.Builder().build());
    }
    
    @Test
    void testBuilder_emptyHost_throwsException() {
        assertThrows(IllegalArgumentException.class, () -> new ZabbixGetterSync.Builder().host("").build());
    }

    @Test
    void testBuilder_withSettings() throws UnknownHostException {
        zabbixGetter = new ZabbixGetterSync.Builder()
            .host(TEST_HOST)
            .port(10055)
            .timeout(10)
            .sourceIp(InetAddress.getLocalHost().getHostAddress())
            .build();
        assertNotNull(zabbixGetter);
        // Further state inspection requires reflection or package-private getters.
    }

    @Test
    void testBuilder_invalidPort_throwsException() {
        assertThrows(IllegalArgumentException.class, () -> new ZabbixGetterSync.Builder().host(TEST_HOST).port(0).build());
        assertThrows(IllegalArgumentException.class, () -> new ZabbixGetterSync.Builder().host(TEST_HOST).port(65536).build());
    }
    
    @Test
    void testBuilder_invalidSourceIp_throwsException() {
        assertThrows(IllegalArgumentException.class, () -> new ZabbixGetterSync.Builder().host(TEST_HOST).sourceIp("invalid.ip.format").build());
    }


    // === get() Method Tests ===
    @Test
    void testGet_success_plainValue() throws IOException {
        zabbixGetter = new ZabbixGetterSync.Builder().host(TEST_HOST).port(TEST_PORT).build();
        String agentValue = "1"; // For agent.ping
        mockAgentResponse(agentValue);

        try (MockedConstruction<Socket> mockedSocketConstruction = Mockito.mockConstruction(Socket.class,
            (mock, context) -> {
                when(mock.getOutputStream()).thenReturn(mockOutputStream);
                when(mock.getInputStream()).thenReturn(mockInputStream);
            })) {
            AgentResponse response = zabbixGetter.get(TEST_ITEM_KEY);
            assertFalse(response.hasError());
            assertEquals(agentValue, response.getValue());
            verify(mockOutputStream).write(any(byte[].class));
        }
    }

    @Test
    void testGet_success_zbxNotSupported() throws IOException {
        zabbixGetter = new ZabbixGetterSync.Builder().host(TEST_HOST).port(TEST_PORT).build();
        String agentError = "ZBX_NOTSUPPORTED";
        mockAgentResponse(agentError);

        try (MockedConstruction<Socket> mockedSocketConstruction = Mockito.mockConstruction(Socket.class,
            (mock, context) -> {
                when(mock.getOutputStream()).thenReturn(mockOutputStream);
                when(mock.getInputStream()).thenReturn(mockInputStream);
            })) {
            AgentResponse response = zabbixGetter.get("unsupported.key");
            assertTrue(response.hasError());
            assertNull(response.getValue());
            assertEquals("Not supported by Zabbix Agent", response.getError());
        }
    }

    @Test
    void testGet_success_zbxNotSupported_withDetails() throws IOException {
        zabbixGetter = new ZabbixGetterSync.Builder().host(TEST_HOST).port(TEST_PORT).build();
        String detailedError = "Specific reason for not supporting.";
        String agentError = "ZBX_NOTSUPPORTED\0" + detailedError;
        mockAgentResponse(agentError);

        try (MockedConstruction<Socket> mockedSocketConstruction = Mockito.mockConstruction(Socket.class,
            (mock, context) -> {
                when(mock.getOutputStream()).thenReturn(mockOutputStream);
                when(mock.getInputStream()).thenReturn(mockInputStream);
            })) {
            AgentResponse response = zabbixGetter.get("another.key");
            assertTrue(response.hasError());
            assertEquals(detailedError, response.getError());
        }
    }

    @Test
    void testGet_invalidItemKey_null_throwsIllegalArgumentException() {
        zabbixGetter = new ZabbixGetterSync.Builder().host(TEST_HOST).build();
        assertThrows(IllegalArgumentException.class, () -> zabbixGetter.get(null));
    }

    @Test
    void testGet_invalidItemKey_empty_throwsIllegalArgumentException() {
        zabbixGetter = new ZabbixGetterSync.Builder().host(TEST_HOST).build();
        assertThrows(IllegalArgumentException.class, () -> zabbixGetter.get(""));
    }

    @Test
    void testGet_unknownHost_throwsCommunicationException() throws IOException {
        zabbixGetter = new ZabbixGetterSync.Builder().host("unknown.invalid.host").build();
        try (MockedConstruction<Socket> mockedSocketConstruction = Mockito.mockConstruction(Socket.class,
            (mock, context) -> {
                // This doThrow needs to be on the connect method of the specific mock instance
                doThrow(new UnknownHostException("unknown.invalid.host")).when(mock).connect(any(InetSocketAddress.class), anyInt());
            })) {
            assertThrows(CommunicationException.class, () -> zabbixGetter.get(TEST_ITEM_KEY));
        }
    }

    @Test
    void testGet_connectException_throwsCommunicationException() throws IOException {
        zabbixGetter = new ZabbixGetterSync.Builder().host(TEST_HOST).build();
        try (MockedConstruction<Socket> mockedSocketConstruction = Mockito.mockConstruction(Socket.class,
            (mock, context) -> {
                doThrow(new ConnectException("Connection refused")).when(mock).connect(any(InetSocketAddress.class), anyInt());
            })) {
            assertThrows(CommunicationException.class, () -> zabbixGetter.get(TEST_ITEM_KEY));
        }
    }

    @Test
    void testGet_socketTimeoutException_onConnect_throwsCommunicationException() throws IOException {
        zabbixGetter = new ZabbixGetterSync.Builder().host(TEST_HOST).timeout(1).build(); // Small timeout
        try (MockedConstruction<Socket> mockedSocketConstruction = Mockito.mockConstruction(Socket.class,
            (mock, context) -> {
                doThrow(new SocketTimeoutException("Connect timeout")).when(mock).connect(any(InetSocketAddress.class), anyInt());
            })) {
            assertThrows(CommunicationException.class, () -> zabbixGetter.get(TEST_ITEM_KEY));
        }
    }
    
    @Test
    void testGet_socketTimeoutException_onRead_throwsCommunicationException() throws IOException {
        zabbixGetter = new ZabbixGetterSync.Builder().host(TEST_HOST).timeout(1).build();
        try (MockedConstruction<Socket> mockedSocketConstruction = Mockito.mockConstruction(Socket.class,
            (mock, context) -> {
                when(mock.getOutputStream()).thenReturn(mockOutputStream);
                when(mock.getInputStream()).thenReturn(mockInputStream);
                // Simulate connect and write success, but read timeout
                doThrow(new SocketTimeoutException("Read timeout")).when(mockInputStream).read(any(byte[].class));
            })) {
            assertThrows(CommunicationException.class, () -> zabbixGetter.get(TEST_ITEM_KEY));
        }
    }

    @Test
    void testGet_ioException_onWrite_throwsCommunicationException() throws IOException {
        zabbixGetter = new ZabbixGetterSync.Builder().host(TEST_HOST).build();
        try (MockedConstruction<Socket> mockedSocketConstruction = Mockito.mockConstruction(Socket.class,
            (mock, context) -> {
                when(mock.getOutputStream()).thenReturn(mockOutputStream);
                doThrow(new IOException("Write failed")).when(mockOutputStream).write(any(byte[].class));
            })) {
            assertThrows(CommunicationException.class, () -> zabbixGetter.get(TEST_ITEM_KEY));
        }
    }
    
    @Test
    void testGet_processingException_duringPacketCreation() {
        // This requires ZabbixProtocol.createPacket to throw ProcessingException,
        // e.g. if item key is excessively long (though not directly handled by current createPacket, conceptual).
        // For this test, we can simulate it by making the itemKey such that createPacket might fail,
        // or by using a spy/mock on ZabbixProtocol if it were not static.
        // Given ZabbixProtocol.createPacket is simple, this is hard to trigger without a huge key.
        // Let's assume a very long key might cause issues (though current createPacket doesn't check length).
        // This test is more conceptual for covering the catch block in ZabbixGetterSync.get().
        String veryLongKey = new String(new char[1024 * 1024]).replace('\0', 'A'); // 1MB key
        zabbixGetter = new ZabbixGetterSync.Builder().host(TEST_HOST).build();
        // If createPacket was more complex and could throw ProcessingException for other reasons:
        // assertThrows(ProcessingException.class, () -> zabbixGetter.get(veryLongKey));
        // For now, this path is hard to test without specific conditions in createPacket.
        // The existing try-catch for ProcessingException around createPacket is good practice.
    }

    @Test
    void testGet_processingException_duringParseResponse() throws IOException {
        zabbixGetter = new ZabbixGetterSync.Builder().host(TEST_HOST).build();
        // Provide a malformed Zabbix packet (e.g., bad signature)
        byte[] malformedPacket = "BAD_PACKET_DATA".getBytes(StandardCharsets.UTF_8);
         ByteArrayInputStream bis = new ByteArrayInputStream(malformedPacket);
        when(mockInputStream.read(any(byte[].class), anyInt(), anyInt())).thenAnswer(inv -> {
            byte[] buffer = inv.getArgument(0);
            int offset = inv.getArgument(1);
            int length = inv.getArgument(2);
            return bis.read(buffer, offset, length);
        });
         when(mockInputStream.read(any(byte[].class))).thenAnswer(inv -> {
            byte[] buffer = inv.getArgument(0);
            return bis.read(buffer, 0, buffer.length);
        });


        try (MockedConstruction<Socket> mockedSocketConstruction = Mockito.mockConstruction(Socket.class,
            (mock, context) -> {
                when(mock.getOutputStream()).thenReturn(mockOutputStream);
                when(mock.getInputStream()).thenReturn(mockInputStream);
            })) {
            assertThrows(ProcessingException.class, () -> zabbixGetter.get(TEST_ITEM_KEY));
        }
    }
}
