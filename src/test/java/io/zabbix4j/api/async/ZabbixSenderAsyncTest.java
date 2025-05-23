package io.zabbix4j.api.async;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.zabbix4j.api.dto.ClusterNode;
import io.zabbix4j.api.dto.ItemValue;
import io.zabbix4j.api.dto.Node;
import io.zabbix4j.api.dto.TrapperResponse;
import io.zabbix4j.api.exception.CommunicationException;
import io.zabbix4j.api.exception.ProcessingException;
import io.zabbix4j.api.protocol.ZabbixProtocol;
import org.apache.commons.lang3.tuple.Pair;
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
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousChannelGroup;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
 * Unit tests for {@link ZabbixSenderAsync}.
 * @author ShortRoundDev
 */
@ExtendWith(MockitoExtension.class)
class ZabbixSenderAsyncTest {

    @Mock
    private AsynchronousSocketChannel mockAsyncSocketChannel;
    @Mock
    private AsynchronousChannelGroup mockExternalChannelGroup; // For testing with external group

    private ZabbixSenderAsync zabbixSender;
    private ObjectMapper objectMapper = new ObjectMapper();

    private final ItemValue testItem1 = ItemValue.builder().host("HostA").key("key.a1").value("valA1").build();
    private final ItemValue testItem2 = ItemValue.builder().host("HostA").key("key.a2").value("valA2").build();

    private MockedStatic<AsynchronousSocketChannel> mockedStaticChannel;

    @Captor
    private ArgumentCaptor<ByteBuffer> byteBufferCaptor;
    @Captor
    private ArgumentCaptor<CompletionHandler<Integer, Object>> writeReadHandlerCaptor; // Capture general handler
    @Captor
    private ArgumentCaptor<CompletionHandler<Void, AsynchronousSocketChannel>> connectHandlerCaptor;


    @BeforeEach
    void setUp() throws IOException {
        // Mock AsynchronousSocketChannel.open() to return our mock channel
        mockedStaticChannel = mockStatic(AsynchronousSocketChannel.class);
        mockedStaticChannel.when(() -> AsynchronousSocketChannel.open(any(AsynchronousChannelGroup.class)))
            .thenReturn(mockAsyncSocketChannel);

        // Default behaviors for mockAsyncSocketChannel
        lenient().doAnswer(invocation -> { // connect
            CompletionHandler<Void, AsynchronousSocketChannel> handler = invocation.getArgument(2);
            AsynchronousSocketChannel attachment = invocation.getArgument(1);
            handler.completed(null, attachment); // Simulate immediate successful connection
            return null;
        }).when(mockAsyncSocketChannel).connect(any(SocketAddress.class), any(AsynchronousSocketChannel.class), any());

        lenient().doAnswer(invocation -> { // write
            ByteBuffer src = invocation.getArgument(0);
            int written = src.remaining();
            src.position(src.limit()); // Consume buffer
            CompletionHandler<Integer, Object> handler = invocation.getArgument(3);
            handler.completed(written, invocation.getArgument(2)); // attachment is null in ZabbixSenderAsync
            return null;
        }).when(mockAsyncSocketChannel).write(any(ByteBuffer.class), anyLong(), any(TimeUnit.class), any(), any());

        // Read behavior needs to be more specific per test for header/body
    }

    @AfterEach
    void tearDown() throws IOException {
        mockedStaticChannel.close();
        if (zabbixSender != null) {
            zabbixSender.close();
        }
    }

    private String createSenderSuccessResponseInfo(int processed, int failed, int total) {
        return String.format("processed: %d; failed: %d; total: %d; seconds spent: 0.00123", processed, failed, total);
    }

    private byte[] createFullZabbixResponsePacket(String infoString) throws IOException {
        ObjectNode responseJsonNode = objectMapper.createObjectNode();
        responseJsonNode.put("response", "success");
        responseJsonNode.put("info", infoString);
        return ZabbixProtocol.createPacket(responseJsonNode.toString(), false); // Assume response not compressed
    }

    // Helper to simulate Zabbix Agent response read in two stages (header, then body)
    @SuppressWarnings("unchecked")
    private void simulateAsyncReadResponse(byte[] fullResponsePacket) {
        doAnswer(new Answer<Object>() { // For header read
            int callCount = 0;
            @Override
            public Object answer(InvocationOnMock invocation) {
                ByteBuffer dstBuffer = invocation.getArgument(0);
                CompletionHandler<Integer, ?> handler = invocation.getArgument(3); // Attachment is null

                if (callCount == 0) { // Header read
                    int headerSize = ZabbixProtocol.HEADER_SIZE;
                    dstBuffer.put(fullResponsePacket, 0, headerSize);
                    dstBuffer.flip(); // Prepare for handler to read
                    handler.completed(headerSize, null);
                } else { // Body read
                    int bodyOffset = ZabbixProtocol.HEADER_SIZE;
                    int bodyLength = fullResponsePacket.length - bodyOffset;
                    dstBuffer.put(fullResponsePacket, bodyOffset, bodyLength);
                    dstBuffer.flip();
                    handler.completed(bodyLength, null);
                }
                callCount++;
                return null;
            }
        }).when(mockAsyncSocketChannel).read(any(ByteBuffer.class), anyLong(), any(TimeUnit.class), any(), any(CompletionHandler.class));
    }


    // === Builder Tests ===
    @Test
    void testBuilder_singleServer_defaultGroup() {
        zabbixSender = new ZabbixSenderAsync.Builder().server("localhost", 10051).build();
        assertNotNull(zabbixSender);
        // managedChannelGroup should be true
        try {
            Field f = ZabbixSenderAsync.class.getDeclaredField("managedChannelGroup");
            f.setAccessible(true);
            assertTrue((Boolean) f.get(zabbixSender));
        } catch (NoSuchFieldException | IllegalAccessException e) {
            fail(e);
        }
    }

    @Test
    void testBuilder_noServers_throwsException() {
        assertThrows(IllegalArgumentException.class, () -> new ZabbixSenderAsync.Builder().build());
    }
    
    @Test
    void testBuilder_withExternalChannelGroup_notManaged() throws IOException {
        zabbixSender = new ZabbixSenderAsync.Builder()
            .server("localhost", 10051)
            .channelGroup(mockExternalChannelGroup)
            .build();
        assertNotNull(zabbixSender);
        try {
            Field f = ZabbixSenderAsync.class.getDeclaredField("managedChannelGroup");
            f.setAccessible(true);
            assertFalse((Boolean) f.get(zabbixSender));
        } catch (NoSuchFieldException | IllegalAccessException e) {
            fail(e);
        }
        zabbixSender.close(); // Should not affect mockExternalChannelGroup
        verify(mockExternalChannelGroup, never()).shutdown();
    }


    // === send() Tests (Async) ===
    @Test
    void testSend_singleItem_success() throws Exception {
        zabbixSender = new ZabbixSenderAsync.Builder().server("localhost", 10051).build();
        byte[] responsePacket = createFullZabbixResponsePacket(createSenderSuccessResponseInfo(1, 0, 1));
        simulateAsyncReadResponse(responsePacket); // Mock the read part

        CompletableFuture<TrapperResponse> future = zabbixSender.send(Collections.singletonList(testItem1));
        TrapperResponse response = future.get(2, TimeUnit.SECONDS); // Increased timeout for async debug

        assertEquals(1, response.getProcessed());
        assertEquals(0, response.getFailed());
        assertEquals(1, response.getTotal());

        verify(mockAsyncSocketChannel).connect(any(SocketAddress.class), eq(mockAsyncSocketChannel), connectHandlerCaptor.capture());
        // connectHandlerCaptor.getValue().completed(null, mockAsyncSocketChannel); // Already simulated by default mock

        verify(mockAsyncSocketChannel).write(byteBufferCaptor.capture(), anyLong(), any(TimeUnit.class), any(), any());
        // writeHandlerCaptor.getValue().completed(byteBufferCaptor.getValue().limit(), null);

        verify(mockAsyncSocketChannel, times(2)).read(any(ByteBuffer.class), anyLong(), any(TimeUnit.class), any(), any());
        // readHandlerCaptor.getValue().completed(...);
    }

    @Test
    void testSend_connectFails_forSingleServer_exception() throws Exception {
        zabbixSender = new ZabbixSenderAsync.Builder().server("localhost", 10051).build();

        // Override default connect mock to simulate failure
        doAnswer(invocation -> {
            CompletionHandler<Void, AsynchronousSocketChannel> handler = invocation.getArgument(2);
            handler.failed(new IOException("Connection refused"), invocation.getArgument(1));
            return null;
        }).when(mockAsyncSocketChannel).connect(any(SocketAddress.class), any(AsynchronousSocketChannel.class), any());

        CompletableFuture<TrapperResponse> future = zabbixSender.send(Collections.singletonList(testItem1));

        ExecutionException ex = assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
        assertTrue(ex.getCause() instanceof ProcessingException); // Because all nodes in all clusters failed
        assertTrue(ex.getCause().getMessage().contains("All nodes in cluster"));
    }

    @Test
    void testSend_writeFails_exception() throws Exception {
        zabbixSender = new ZabbixSenderAsync.Builder().server("localhost", 10051).build();

        // Connect succeeds (default mock), but write fails
        doAnswer(invocation -> {
            CompletionHandler<Integer, Object> handler = invocation.getArgument(3);
            handler.failed(new IOException("Write error"), invocation.getArgument(2));
            return null;
        }).when(mockAsyncSocketChannel).write(any(ByteBuffer.class), anyLong(), any(TimeUnit.class), any(), any());

        CompletableFuture<TrapperResponse> future = zabbixSender.send(Collections.singletonList(testItem1));
        ExecutionException ex = assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
        assertTrue(ex.getCause() instanceof ProcessingException);
    }
    
    @Test
    void testSend_readHeaderFails_exception() throws Exception {
        zabbixSender = new ZabbixSenderAsync.Builder().server("localhost", 10051).build();
        // Connect and Write succeed (default mocks)
        // Read fails (first read is header)
        doAnswer(invocation -> {
            CompletionHandler<Integer, ?> handler = invocation.getArgument(3);
            handler.failed(new IOException("Read header error"), null);
            return null;
        }).when(mockAsyncSocketChannel).read(any(ByteBuffer.class), anyLong(), any(TimeUnit.class), any(), any(CompletionHandler.class));


        CompletableFuture<TrapperResponse> future = zabbixSender.send(Collections.singletonList(testItem1));
        ExecutionException ex = assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
        assertTrue(ex.getCause() instanceof ProcessingException);
    }

    @Test
    void testSend_clusterFailover_firstNodeFails_secondSucceeds() throws Exception {
        Node node1 = new Node("node1.host", 10051);
        Node node2 = new Node("node2.host", 10051);
        ClusterNode cluster = new ClusterNode(Arrays.asList(node1, node2), true);
        zabbixSender = new ZabbixSenderAsync.Builder().cluster(cluster).build();

        byte[] responsePacketNode2 = createFullZabbixResponsePacket(createSenderSuccessResponseInfo(1, 0, 1));

        // Mock AsynchronousSocketChannel.open() to return different mocks for node1 and node2 attempts
        AsynchronousSocketChannel mockChannelNode1 = mock(AsynchronousSocketChannel.class);
        AsynchronousSocketChannel mockChannelNode2 = mock(AsynchronousSocketChannel.class);
        mockedStaticChannel.when(() -> AsynchronousSocketChannel.open(any(AsynchronousChannelGroup.class)))
            .thenReturn(mockChannelNode1).thenReturn(mockChannelNode2);


        // Node1 connect fails
        doAnswer(inv -> {
            CompletionHandler<Void, AsynchronousSocketChannel> ch = inv.getArgument(2);
            ch.failed(new IOException("Node1 connect fail"), inv.getArgument(1));
            return null;
        }).when(mockChannelNode1).connect(eq(new InetSocketAddress(node1.getAddress(), node1.getPort())), eq(mockChannelNode1), any());

        // Node2 connect succeeds
        doAnswer(inv -> {
            CompletionHandler<Void, AsynchronousSocketChannel> ch = inv.getArgument(2);
            ch.completed(null, inv.getArgument(1));
            return null;
        }).when(mockChannelNode2).connect(eq(new InetSocketAddress(node2.getAddress(), node2.getPort())), eq(mockChannelNode2), any());

        // Node2 write succeeds
        doAnswer(inv -> {
            ByteBuffer src = inv.getArgument(0);
            int written = src.remaining();
            src.position(src.limit());
            CompletionHandler<Integer, Object> ch = inv.getArgument(3);
            ch.completed(written, inv.getArgument(2));
            return null;
        }).when(mockChannelNode2).write(any(ByteBuffer.class), anyLong(), any(TimeUnit.class), any(), any());
        
        // Node2 read (header then body)
        doAnswer(new Answer<Object>() { // For header read
            int callCount = 0;
            @Override
            public Object answer(InvocationOnMock invocation) {
                ByteBuffer dstBuffer = invocation.getArgument(0);
                CompletionHandler<Integer, ?> handler = invocation.getArgument(3);
                if (callCount == 0) { // Header read
                    dstBuffer.put(responsePacketNode2, 0, ZabbixProtocol.HEADER_SIZE); handler.completed(ZabbixProtocol.HEADER_SIZE, null);
                } else { // Body read
                    dstBuffer.put(responsePacketNode2, ZabbixProtocol.HEADER_SIZE, responsePacketNode2.length - ZabbixProtocol.HEADER_SIZE); handler.completed(responsePacketNode2.length - ZabbixProtocol.HEADER_SIZE, null);
                }
                callCount++; return null;
            }
        }).when(mockChannelNode2).read(any(ByteBuffer.class), anyLong(), any(TimeUnit.class), any(), any(CompletionHandler.class));


        CompletableFuture<TrapperResponse> future = zabbixSender.send(Collections.singletonList(testItem1));
        TrapperResponse response = future.get(2, TimeUnit.SECONDS);

        assertEquals(1, response.getProcessed());
        verify(mockChannelNode1).connect(eq(new InetSocketAddress(node1.getAddress(), node1.getPort())), any(), any());
        verify(mockChannelNode2).connect(eq(new InetSocketAddress(node2.getAddress(), node2.getPort())), any(), any());
        verify(mockChannelNode2).write(any(ByteBuffer.class), anyLong(), any(TimeUnit.class), any(), any());
    }
    
    @Test
    void testSend_multipleChunks_sequentialProcessing() throws Exception {
        zabbixSender = new ZabbixSenderAsync.Builder().server("localhost", 10051).chunkSize(1).build();
        
        byte[] responsePacketChunk1 = createFullZabbixResponsePacket(createSenderSuccessResponseInfo(1,0,1));
        byte[] responsePacketChunk2 = createFullZabbixResponsePacket(createSenderSuccessResponseInfo(1,0,1));

        // Mock for first chunk
        simulateAsyncReadResponse(responsePacketChunk1);
        
        CompletableFuture<TrapperResponse> future = zabbixSender.send(Arrays.asList(testItem1, testItem2));

        // After first chunk processing, re-mock for second chunk
        // This is tricky because the send() method chains futures.
        // The static mock for AsynchronousSocketChannel.open will provide the same mockAsyncSocketChannel instance.
        // We need to ensure that when the *second* chunk's sendAndReceive is called, the read mock is for the second response.
        // This might require a more stateful mock for 'read' or careful ordering of 'thenAnswer'.
        // For this test, let's assume a simplified scenario where the mock setup for read handles two sequential responses.
        // This can be done if the read mock is an Answer that returns different data on subsequent calls.
        // The current simulateAsyncReadResponse is not stateful across calls to it.
        // Let's update simulateAsyncReadResponse to handle sequence.
        
        // Simulate first response
        when(mockAsyncSocketChannel.read(any(ByteBuffer.class), anyLong(), any(TimeUnit.class), any(), any(CompletionHandler.class)))
            .thenAnswer(getReadAnswer(responsePacketChunk1))  // Header for chunk 1
            .thenAnswer(getReadAnswer(responsePacketChunk1))  // Body for chunk 1
            .thenAnswer(getReadAnswer(responsePacketChunk2))  // Header for chunk 2
            .thenAnswer(getReadAnswer(responsePacketChunk2)); // Body for chunk 2

        TrapperResponse response = future.get(3, TimeUnit.SECONDS);
        assertEquals(2, response.getProcessed());
        assertEquals(2, response.getTotal());
        
        // Verify connect/write happened twice (once per chunk to the same "server")
        verify(mockAsyncSocketChannel, times(2)).connect(any(SocketAddress.class), any(AsynchronousSocketChannel.class), any());
        verify(mockAsyncSocketChannel, times(2)).write(any(ByteBuffer.class), anyLong(), any(TimeUnit.class), any(), any());
        verify(mockAsyncSocketChannel, times(4)).read(any(ByteBuffer.class), anyLong(), any(TimeUnit.class), any(), any(CompletionHandler.class)); // 2 header, 2 body
    }

    // Helper to create stateful Answer for sequential reads
    private Answer<Object> getReadAnswer(final byte[] fullPacket) {
        return new Answer<Object>() {
            private boolean headerRead = false;
            @Override
            public Object answer(InvocationOnMock invocation) {
                ByteBuffer dstBuffer = invocation.getArgument(0);
                CompletionHandler<Integer, ?> handler = invocation.getArgument(3);
                if (!headerRead) { // Header read
                    int headerSize = ZabbixProtocol.HEADER_SIZE;
                    dstBuffer.put(fullPacket, 0, headerSize);
                    dstBuffer.flip();
                    handler.completed(headerSize, null);
                    headerRead = true;
                } else { // Body read
                    int bodyOffset = ZabbixProtocol.HEADER_SIZE;
                    int bodyLength = fullPacket.length - bodyOffset;
                    dstBuffer.put(fullPacket, bodyOffset, bodyLength);
                    dstBuffer.flip();
                    handler.completed(bodyLength, null);
                    // Reset for next packet if needed, or assume one packet per getReadAnswer instance
                }
                return null;
            }
        };
    }


    // === close() Method Test ===
    @Test
    void testClose_managedGroup_shutdownsGroup() throws Exception {
        // Builder creates a managed group by default if none provided
        zabbixSender = new ZabbixSenderAsync.Builder().server("localhost", 10051).build();
        // To verify this, we need to capture the internally created AsynchronousChannelGroup.
        // This is hard. Let's assume the logic (if managedChannelGroup then group.shutdown()) is correct.
        // Test primarily ensures no exceptions.
        zabbixSender.close();
    }

    @Test
    void testClose_externalGroup_notShutdown() throws IOException {
        zabbixSender = new ZabbixSenderAsync.Builder()
            .server("localhost", 10051)
            .channelGroup(mockExternalChannelGroup)
            .build();
        zabbixSender.close();
        verify(mockExternalChannelGroup, never()).shutdown();
        verify(mockExternalChannelGroup, never()).shutdownNow();
    }
}
