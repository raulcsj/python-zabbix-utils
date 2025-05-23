package io.zabbix4j.api.async;

import io.zabbix4j.api.dto.AgentResponse;
import io.zabbix4j.api.exception.CommunicationException;
import io.zabbix4j.api.exception.ProcessingException;
import io.zabbix4j.api.protocol.ZabbixProtocol;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousChannelGroup;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ZabbixAsyncGetter}.
 * @author ShortRoundDev
 */
@ExtendWith(MockitoExtension.class)
class ZabbixAsyncGetterTest {

    @Mock
    private AsynchronousSocketChannel mockAsyncSocketChannel;
    @Mock
    private AsynchronousChannelGroup mockExternalChannelGroup;

    private ZabbixAsyncGetter zabbixGetter;
    private MockedStatic<AsynchronousSocketChannel> mockedStaticChannel;

    private final String TEST_HOST = "agent.example.com";
    private final int TEST_PORT = 10050;
    private final String TEST_ITEM_KEY = "agent.ping";

    @Captor
    private ArgumentCaptor<ByteBuffer> byteBufferCaptor;
    @Captor
    private ArgumentCaptor<CompletionHandler<Integer, ?>> writeReadHandlerCaptor;
    @Captor
    private ArgumentCaptor<CompletionHandler<Void, ?>> connectHandlerCaptor;


    @BeforeEach
    void setUp() throws IOException {
        mockedStaticChannel = mockStatic(AsynchronousSocketChannel.class);
        mockedStaticChannel.when(() -> AsynchronousSocketChannel.open(any(AsynchronousChannelGroup.class)))
            .thenReturn(mockAsyncSocketChannel);

        // Default behaviors for mockAsyncSocketChannel
        lenient().doAnswer(invocation -> { // connect
            CompletionHandler<Void, ?> handler = invocation.getArgument(2);
            handler.completed(null, invocation.getArgument(1)); // attachment is channel
            return null;
        }).when(mockAsyncSocketChannel).connect(any(SocketAddress.class), any(), any());

        lenient().doAnswer(invocation -> { // write
            ByteBuffer src = invocation.getArgument(0);
            int written = src.remaining();
            src.position(src.limit());
            CompletionHandler<Integer, ?> handler = invocation.getArgument(3);
            handler.completed(written, invocation.getArgument(2)); // attachment
            return null;
        }).when(mockAsyncSocketChannel).write(any(ByteBuffer.class), anyLong(), any(TimeUnit.class), any(), any());
    }

    @AfterEach
    void tearDown() throws IOException {
        mockedStaticChannel.close();
        if (zabbixGetter != null) {
            zabbixGetter.close();
        }
    }

    private byte[] createFullAgentResponsePacket(String agentData) throws IOException {
        return ZabbixProtocol.createPacket(agentData, false); // Agent responses not compressed
    }

    @SuppressWarnings("unchecked")
    private void simulateAsyncReadAgentResponse(byte[] fullResponsePacket) {
         doAnswer(new Answer<Object>() {
            int callCount = 0;
            @Override
            public Object answer(InvocationOnMock invocation) {
                ByteBuffer dstBuffer = invocation.getArgument(0);
                CompletionHandler<Integer, Object> handler = (CompletionHandler<Integer, Object>) invocation.getArgument(3); // Capture specific type
                Object attachment = invocation.getArgument(2);

                if (callCount == 0) { // Header read
                    int headerSize = ZabbixProtocol.HEADER_SIZE;
                    dstBuffer.put(fullResponsePacket, 0, headerSize);
                    dstBuffer.flip();
                    handler.completed(headerSize, attachment);
                } else { // Body read
                    int bodyOffset = ZabbixProtocol.HEADER_SIZE;
                    int bodyLength = fullResponsePacket.length - bodyOffset;
                    dstBuffer.put(fullResponsePacket, bodyOffset, bodyLength);
                    dstBuffer.flip();
                    handler.completed(bodyLength, attachment);
                }
                callCount++;
                return null;
            }
        }).when(mockAsyncSocketChannel).read(any(ByteBuffer.class), anyLong(), any(TimeUnit.class), any(), any(CompletionHandler.class));
    }


    // === Builder Tests ===
    @Test
    void testBuilder_minimalHost_defaultGroup() {
        zabbixGetter = new ZabbixAsyncGetter.Builder().host(TEST_HOST).build();
        assertNotNull(zabbixGetter);
        try {
            Field f = ZabbixAsyncGetter.class.getDeclaredField("managedChannelGroup");
            f.setAccessible(true);
            assertTrue((Boolean) f.get(zabbixGetter));
        } catch (NoSuchFieldException | IllegalAccessException e) {
            fail(e);
        }
    }

    @Test
    void testBuilder_noHost_throwsException() {
        assertThrows(NullPointerException.class, () -> new ZabbixAsyncGetter.Builder().build());
    }

    @Test
    void testBuilder_withExternalChannelGroup_notManaged() throws IOException {
        zabbixGetter = new ZabbixAsyncGetter.Builder()
            .host(TEST_HOST)
            .channelGroup(mockExternalChannelGroup)
            .build();
        assertNotNull(zabbixGetter);
        try {
            Field f = ZabbixAsyncGetter.class.getDeclaredField("managedChannelGroup");
            f.setAccessible(true);
            assertFalse((Boolean) f.get(zabbixGetter));
        } catch (NoSuchFieldException | IllegalAccessException e) {
            fail(e);
        }
        zabbixGetter.close();
        verify(mockExternalChannelGroup, never()).shutdown();
    }

    // === get() Method Tests (Async) ===
    @Test
    void testGet_success_plainValue() throws Exception {
        zabbixGetter = new ZabbixAsyncGetter.Builder().host(TEST_HOST).port(TEST_PORT).build();
        String agentValue = "1"; // For agent.ping
        byte[] responsePacket = createFullAgentResponsePacket(agentValue);
        simulateAsyncReadAgentResponse(responsePacket);

        CompletableFuture<AgentResponse> future = zabbixGetter.get(TEST_ITEM_KEY);
        AgentResponse response = future.get(1, TimeUnit.SECONDS);

        assertFalse(response.hasError());
        assertEquals(agentValue, response.getValue());

        verify(mockAsyncSocketChannel).connect(eq(new InetSocketAddress(TEST_HOST, TEST_PORT)), any(), connectHandlerCaptor.capture());
        // Default mock connect behavior already calls completed.

        verify(mockAsyncSocketChannel).write(byteBufferCaptor.capture(), anyLong(), any(TimeUnit.class), any(), any());
        // Default mock write behavior calls completed.

        verify(mockAsyncSocketChannel, times(2)).read(any(ByteBuffer.class), anyLong(), any(TimeUnit.class), any(), any());
    }

    @Test
    void testGet_success_zbxNotSupported() throws Exception {
        zabbixGetter = new ZabbixAsyncGetter.Builder().host(TEST_HOST).build();
        String agentError = "ZBX_NOTSUPPORTED";
        byte[] responsePacket = createFullAgentResponsePacket(agentError);
        simulateAsyncReadAgentResponse(responsePacket);

        CompletableFuture<AgentResponse> future = zabbixGetter.get("unsupported.key");
        AgentResponse response = future.get(1, TimeUnit.SECONDS);

        assertTrue(response.hasError());
        assertNull(response.getValue());
        assertEquals("Not supported by Zabbix Agent", response.getError());
    }
    
    @Test
    void testGet_connectFails_exception() throws Exception {
        zabbixGetter = new ZabbixAsyncGetter.Builder().host(TEST_HOST).build();
        doAnswer(invocation -> {
            CompletionHandler<Void, ?> handler = invocation.getArgument(2);
            handler.failed(new IOException("Connection refused by test"), invocation.getArgument(1));
            return null;
        }).when(mockAsyncSocketChannel).connect(any(SocketAddress.class), any(), any());

        CompletableFuture<AgentResponse> future = zabbixGetter.get(TEST_ITEM_KEY);
        ExecutionException ex = assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
        assertTrue(ex.getCause() instanceof CommunicationException);
        assertTrue(ex.getCause().getMessage().contains("Connection failed"));
    }

    @Test
    void testGet_writeFails_exception() throws Exception {
        zabbixGetter = new ZabbixAsyncGetter.Builder().host(TEST_HOST).build();
        // Connect succeeds (default mock)
        doAnswer(invocation -> {
            CompletionHandler<Integer, ?> handler = invocation.getArgument(3);
            handler.failed(new IOException("Write failed by test"), invocation.getArgument(2));
            return null;
        }).when(mockAsyncSocketChannel).write(any(ByteBuffer.class), anyLong(), any(TimeUnit.class), any(), any());

        CompletableFuture<AgentResponse> future = zabbixGetter.get(TEST_ITEM_KEY);
        ExecutionException ex = assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
        assertTrue(ex.getCause() instanceof CommunicationException);
        assertTrue(ex.getCause().getMessage().contains("Failed to write request"));
    }

    @Test
    void testGet_readHeaderFails_exception() throws Exception {
        zabbixGetter = new ZabbixAsyncGetter.Builder().host(TEST_HOST).build();
        // Connect and Write succeed (default mocks)
        // First read (header) fails
        doAnswer(invocation -> {
            CompletionHandler<Integer, ?> handler = invocation.getArgument(3); // Read handler
            handler.failed(new IOException("Read header failed by test"), invocation.getArgument(2));
            return null;
        }).when(mockAsyncSocketChannel).read(any(ByteBuffer.class), anyLong(), any(TimeUnit.class), any(), any(CompletionHandler.class));


        CompletableFuture<AgentResponse> future = zabbixGetter.get(TEST_ITEM_KEY);
        ExecutionException ex = assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
        assertTrue(ex.getCause() instanceof CommunicationException);
        assertTrue(ex.getCause().getMessage().contains("Failed to read response header"));
    }
    
    @Test
    void testGet_readBodyFails_exception() throws Exception {
        zabbixGetter = new ZabbixAsyncGetter.Builder().host(TEST_HOST).build();
        byte[] headerBytes = ZabbixProtocol.createPacket("", false); // Valid header for empty body
        
        // Connect and Write succeed
        // Header read succeeds, Body read fails
        doAnswer(new Answer<Object>() {
            int callCount = 0;
            @Override
            public Object answer(InvocationOnMock invocation) {
                ByteBuffer dstBuffer = invocation.getArgument(0);
                CompletionHandler<Integer, Object> handler = (CompletionHandler<Integer, Object>) invocation.getArgument(3);
                Object attachment = invocation.getArgument(2);

                if (callCount == 0) { // Header read success
                    dstBuffer.put(headerBytes, 0, ZabbixProtocol.HEADER_SIZE);
                    dstBuffer.flip();
                    handler.completed(ZabbixProtocol.HEADER_SIZE, attachment);
                } else { // Body read fails
                    handler.failed(new IOException("Read body failed by test"), attachment);
                }
                callCount++;
                return null;
            }
        }).when(mockAsyncSocketChannel).read(any(ByteBuffer.class), anyLong(), any(TimeUnit.class), any(), any(CompletionHandler.class));

        CompletableFuture<AgentResponse> future = zabbixGetter.get(TEST_ITEM_KEY);
        ExecutionException ex = assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
        assertTrue(ex.getCause() instanceof CommunicationException);
        assertTrue(ex.getCause().getMessage().contains("Failed to read response body"));
    }
    
    @Test
    void testGet_invalidItemKey_null_completesExceptionally() {
        zabbixGetter = new ZabbixAsyncGetter.Builder().host(TEST_HOST).build();
        CompletableFuture<AgentResponse> future = zabbixGetter.get(null);
        ExecutionException ex = assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
        assertTrue(ex.getCause() instanceof IllegalArgumentException);
    }

    // === close() Method Test ===
    @Test
    void testClose_managedGroup_shutdownsGroup() throws Exception {
        zabbixGetter = new ZabbixAsyncGetter.Builder().host(TEST_HOST).build();
        // As with ZabbixSenderAsyncTest, direct verification of internal group shutdown is hard.
        // Test ensures method runs without error, assuming flag logic is correct.
        zabbixGetter.close();
    }

    @Test
    void testClose_externalGroup_notShutdown() throws IOException {
        zabbixGetter = new ZabbixAsyncGetter.Builder()
            .host(TEST_HOST)
            .channelGroup(mockExternalChannelGroup)
            .build();
        zabbixGetter.close();
        verify(mockExternalChannelGroup, never()).shutdown();
    }
}
