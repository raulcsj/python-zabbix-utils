package io.zabbix4j.api.common;

import io.zabbix4j.api.exceptions.ProcessingException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Unit tests for {@link ZabbixProtocol}.
 *
 * @author CSJ
 */
class ZabbixProtocolTest {

    private static final Logger logger = LoggerFactory.getLogger(ZabbixProtocolTest.class);


    private byte[] ZABBIX_HEADER_PREFIX = "ZBXD".getBytes(StandardCharsets.US_ASCII);
    private int HEADER_SIZE = 13;
    private byte FLAG_PROTOCOL_VERSION = 0x01;
    private byte FLAG_COMPRESSION = 0x02;


    @Test
    void testCreatePacket_uncompressed() throws IOException, ProcessingException {
        String payload = "{\"request\":\"sender data\",\"data\":[{\"host\":\"Test Host\",\"key\":\"item.key\",\"value\":\"123\"}]}";
        byte[] packet = ZabbixProtocol.createPacket(payload, false);

        assertNotNull(packet);
        assertTrue(packet.length > HEADER_SIZE);

        // Verify Header
        ByteBuffer headerBuffer = ByteBuffer.wrap(packet, 0, HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        byte[] prefix = new byte[ZABBIX_HEADER_PREFIX.length];
        headerBuffer.get(prefix);
        assertArrayEquals(ZABBIX_HEADER_PREFIX, prefix);

        byte flags = headerBuffer.get();
        assertEquals(FLAG_PROTOCOL_VERSION, flags); // Only version flag should be set

        int dataLen = headerBuffer.getInt();
        assertEquals(payload.getBytes(StandardCharsets.UTF_8).length, dataLen);

        int reserved = headerBuffer.getInt();
        assertEquals(0, reserved); // Reserved should be 0 for uncompressed

        // Verify Payload
        String actualPayload = new String(packet, HEADER_SIZE, dataLen, StandardCharsets.UTF_8);
        assertEquals(payload, actualPayload);
    }

    @Test
    void testCreatePacket_compressed() throws IOException, ProcessingException {
        String payload = "{\"request\":\"long sender data that should compress well\",\"data\":[{\"host\":\"Test Host Compress\",\"key\":\"item.key.compress\",\"value\":\"A long value to make compression effective... A long value to make compression effective...\"}]}";
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        byte[] packet = ZabbixProtocol.createPacket(payload, true);

        assertNotNull(packet);
        assertTrue(packet.length > HEADER_SIZE);

        // Verify Header
        ByteBuffer headerBuffer = ByteBuffer.wrap(packet, 0, HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        byte[] prefix = new byte[ZABBIX_HEADER_PREFIX.length];
        headerBuffer.get(prefix);
        assertArrayEquals(ZABBIX_HEADER_PREFIX, prefix);

        byte flags = headerBuffer.get();
        assertEquals(FLAG_PROTOCOL_VERSION | FLAG_COMPRESSION, flags);

        int dataLen = headerBuffer.getInt(); // Compressed length
        assertTrue(dataLen < payloadBytes.length, "Compressed data length should be less than original for this payload.");

        int reserved = headerBuffer.getInt(); // Uncompressed length
        assertEquals(payloadBytes.length, reserved);

        // Verify Payload (decompress and check)
        ByteArrayOutputStream baosDecompressed = new ByteArrayOutputStream();
        try (InflaterInputStream iis = new InflaterInputStream(new ByteArrayInputStream(packet, HEADER_SIZE, dataLen))) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = iis.read(buffer)) != -1) {
                baosDecompressed.write(buffer, 0, count);
            }
        }
        String decompressedPayload = baosDecompressed.toString(StandardCharsets.UTF_8.name());
        assertEquals(payload, decompressedPayload);
    }

    @Test
    void testCreatePacket_nullPayload_throwsException() {
        ProcessingException exception = assertThrows(ProcessingException.class,
                () -> ZabbixProtocol.createPacket(null, false));
        assertEquals("Payload cannot be null or empty", exception.getMessage());
    }

    @Test
    void testCreatePacket_emptyPayload_throwsException() {
        ProcessingException exception = assertThrows(ProcessingException.class,
                () -> ZabbixProtocol.createPacket("", true));
        assertEquals("Payload cannot be null or empty", exception.getMessage());
    }


    @Test
    void testParseSynchronousPacket_uncompressed() throws IOException, ProcessingException {
        String payload = "{\"response\":\"success\",\"info\":\"processed: 1; failed: 0; total: 1; seconds spent: 0.001\"}";
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);

        ByteBuffer packetBuffer = ByteBuffer.allocate(HEADER_SIZE + payloadBytes.length);
        packetBuffer.order(ByteOrder.LITTLE_ENDIAN);
        packetBuffer.put(ZABBIX_HEADER_PREFIX);
        packetBuffer.put(FLAG_PROTOCOL_VERSION);
        packetBuffer.putInt(payloadBytes.length);
        packetBuffer.putInt(0); // reserved for uncompressed
        packetBuffer.put(payloadBytes);

        InputStream inputStream = new ByteArrayInputStream(packetBuffer.array());
        String parsedPayload = ZabbixProtocol.parseSynchronousPacket(inputStream);
        assertEquals(payload, parsedPayload);
    }

    @Test
    void testParseSynchronousPacket_compressed() throws IOException, ProcessingException {
        String payload = "{\"response\":\"success\",\"info\":\"processed: 10; failed: 2; total: 12; seconds spent: 0.023\"}";
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);

        // Compress payload
        ByteArrayOutputStream baosCompressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream dos = new DeflaterOutputStream(baosCompressed)) {
            dos.write(payloadBytes);
        }
        byte[] compressedPayloadBytes = baosCompressed.toByteArray();

        ByteBuffer packetBuffer = ByteBuffer.allocate(HEADER_SIZE + compressedPayloadBytes.length);
        packetBuffer.order(ByteOrder.LITTLE_ENDIAN);
        packetBuffer.put(ZABBIX_HEADER_PREFIX);
        packetBuffer.put((byte) (FLAG_PROTOCOL_VERSION | FLAG_COMPRESSION));
        packetBuffer.putInt(compressedPayloadBytes.length);
        packetBuffer.putInt(payloadBytes.length); // uncompressed length in reserved
        packetBuffer.put(compressedPayloadBytes);

        InputStream inputStream = new ByteArrayInputStream(packetBuffer.array());
        String parsedPayload = ZabbixProtocol.parseSynchronousPacket(inputStream);
        assertEquals(payload, parsedPayload);
    }

    @Test
    void testParseSynchronousPacket_invalidHeaderPrefix() {
        byte[] invalidPacket = "INVALID_PREFIX_ZBXD_etc".getBytes(StandardCharsets.US_ASCII);
        InputStream inputStream = new ByteArrayInputStream(invalidPacket);
        ProcessingException exception = assertThrows(ProcessingException.class,
                () -> ZabbixProtocol.parseSynchronousPacket(inputStream));
        assertTrue(exception.getMessage().contains("Invalid Zabbix protocol header received: Prefix mismatch"));
    }

    @Test
    void testParseSynchronousPacket_missingProtocolVersionFlag() {
        ByteBuffer packetBuffer = ByteBuffer.allocate(HEADER_SIZE);
        packetBuffer.order(ByteOrder.LITTLE_ENDIAN);
        packetBuffer.put(ZABBIX_HEADER_PREFIX);
        packetBuffer.put((byte) 0x00); // No flags
        packetBuffer.putInt(0);
        packetBuffer.putInt(0);
        InputStream inputStream = new ByteArrayInputStream(packetBuffer.array());
        ProcessingException exception = assertThrows(ProcessingException.class,
                () -> ZabbixProtocol.parseSynchronousPacket(inputStream));
        assertTrue(exception.getMessage().contains("Missing protocol version flag"));
    }

    @Test
    void testParseSynchronousPacket_largePacketFlagNotSupported() {
        ByteBuffer packetBuffer = ByteBuffer.allocate(HEADER_SIZE);
        packetBuffer.order(ByteOrder.LITTLE_ENDIAN);
        packetBuffer.put(ZABBIX_HEADER_PREFIX);
        packetBuffer.put((byte) (FLAG_PROTOCOL_VERSION | 0x04)); // Large packet flag
        packetBuffer.putInt(0);
        packetBuffer.putInt(0);
        InputStream inputStream = new ByteArrayInputStream(packetBuffer.array());
        ProcessingException exception = assertThrows(ProcessingException.class,
                () -> ZabbixProtocol.parseSynchronousPacket(inputStream));
        assertTrue(exception.getMessage().contains("Current implementation does not support large packets"));
    }
    @Test
    void testParseSynchronousPacket_corruptedCompressedData() throws IOException {
        String payload = "This is some data";
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);

        // Create a "valid" compressed stream that is actually just garbage
        byte[] corruptedCompressedData = "not_really_zlib_data".getBytes();

        ByteBuffer packetBuffer = ByteBuffer.allocate(HEADER_SIZE + corruptedCompressedData.length);
        packetBuffer.order(ByteOrder.LITTLE_ENDIAN);
        packetBuffer.put(ZABBIX_HEADER_PREFIX);
        packetBuffer.put((byte) (FLAG_PROTOCOL_VERSION | FLAG_COMPRESSION));
        packetBuffer.putInt(corruptedCompressedData.length);
        packetBuffer.putInt(payloadBytes.length); // Original length
        packetBuffer.put(corruptedCompressedData);

        InputStream inputStream = new ByteArrayInputStream(packetBuffer.array());
        ProcessingException exception = assertThrows(ProcessingException.class,
                () -> ZabbixProtocol.parseSynchronousPacket(inputStream));
        assertTrue(exception.getMessage().contains("Failed to decompress Zabbix response payload"));
    }

    @Test
    void testParseSynchronousPacket_incompleteHeader() {
        byte[] incompletePacket = Arrays.copyOf(ZABBIX_HEADER_PREFIX, HEADER_SIZE - 1); // One byte short
        InputStream inputStream = new ByteArrayInputStream(incompletePacket);
        assertThrows(EOFException.class, () -> ZabbixProtocol.parseSynchronousPacket(inputStream));
    }

    @Test
    void testParseSynchronousPacket_incompletePayload() {
        String payload = "short_payload";
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);

        ByteBuffer packetBuffer = ByteBuffer.allocate(HEADER_SIZE + payloadBytes.length -1); // Data is one byte short
        packetBuffer.order(ByteOrder.LITTLE_ENDIAN);
        packetBuffer.put(ZABBIX_HEADER_PREFIX);
        packetBuffer.put(FLAG_PROTOCOL_VERSION);
        packetBuffer.putInt(payloadBytes.length); // Declare full length
        packetBuffer.putInt(0);
        packetBuffer.put(payloadBytes, 0, payloadBytes.length -1); // But provide less data

        InputStream inputStream = new ByteArrayInputStream(packetBuffer.array());
        assertThrows(EOFException.class, () -> ZabbixProtocol.parseSynchronousPacket(inputStream));
    }


    // Test for readFully via reflection as it's private
    @Test
    void testReadFully_normalRead() throws Exception {
        byte[] expectedData = "Hello, World!".getBytes(StandardCharsets.UTF_8);
        InputStream inputStream = new ByteArrayInputStream(expectedData);

        Method readFullyMethod = ZabbixProtocol.class.getDeclaredMethod("readFully", InputStream.class, int.class);
        readFullyMethod.setAccessible(true);

        byte[] actualData = (byte[]) readFullyMethod.invoke(null, inputStream, expectedData.length);
        assertArrayEquals(expectedData, actualData);
    }

    @Test
    void testReadFully_eofException() throws Exception {
        byte[] data = "short".getBytes(StandardCharsets.UTF_8);
        InputStream inputStream = new ByteArrayInputStream(data);

        Method readFullyMethod = ZabbixProtocol.class.getDeclaredMethod("readFully", InputStream.class, int.class);
        readFullyMethod.setAccessible(true);

        try {
            readFullyMethod.invoke(null, inputStream, data.length + 5); // Try to read more than available
            fail("Expected EOFException was not thrown");
        } catch (InvocationTargetException e) {
            assertTrue(e.getCause() instanceof EOFException, "Cause should be EOFException");
        }
    }

    @Test
    void testPrivateConstructor() throws NoSuchMethodException {
        Constructor<ZabbixProtocol> constructor = ZabbixProtocol.class.getDeclaredConstructor();
        assertTrue(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers()));
        constructor.setAccessible(true);
        assertThrows(java.lang.reflect.InvocationTargetException.class, () -> {
            try {
                constructor.newInstance();
            } catch (java.lang.reflect.InvocationTargetException e) {
                if (e.getCause() instanceof UnsupportedOperationException) {
                    throw e; // Re-throw to be caught by assertThrows
                }
                throw new RuntimeException("Unexpected cause", e.getCause());
            }
        });
    }
}
