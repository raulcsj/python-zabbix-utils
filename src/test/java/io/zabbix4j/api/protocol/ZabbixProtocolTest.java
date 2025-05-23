package io.zabbix4j.api.protocol;

import io.zabbix4j.api.exception.CommunicationException;
import io.zabbix4j.api.exception.ProcessingException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import java.util.zip.DataFormatException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ZabbixProtocol}.
 * @author ShortRoundDev
 */
class ZabbixProtocolTest {

    private final String TEST_PAYLOAD = "{\"request\":\"sender data\",\"data\":[{\"host\":\"TestHost\",\"key\":\"item.key\",\"value\":\"123\"}]}";
    private final byte[] TEST_PAYLOAD_BYTES = TEST_PAYLOAD.getBytes(StandardCharsets.UTF_8);

    @Test
    void testCreatePacket_uncompressed() {
        byte[] packet = ZabbixProtocol.createPacket(TEST_PAYLOAD, false);

        assertEquals(ZabbixProtocol.HEADER_SIZE + TEST_PAYLOAD_BYTES.length, packet.length);

        ByteBuffer buffer = ByteBuffer.wrap(packet);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        byte[] signature = new byte[ZabbixProtocol.ZABBIX_HEADER_SIGNATURE.length];
        buffer.get(signature);
        assertArrayEquals(ZabbixProtocol.ZABBIX_HEADER_SIGNATURE, signature);

        byte flags = buffer.get();
        assertEquals(ZabbixProtocol.FLAG_ZABBIX_PROTOCOL, flags); // Only protocol flag

        int dataLength = buffer.getInt();
        assertEquals(TEST_PAYLOAD_BYTES.length, dataLength);

        int reservedLength = buffer.getInt();
        assertEquals(0, reservedLength); // Should be 0 for uncompressed

        byte[] payloadInPacket = new byte[dataLength];
        buffer.get(payloadInPacket);
        assertArrayEquals(TEST_PAYLOAD_BYTES, payloadInPacket);
    }

    @Test
    void testCreatePacket_compressed() throws DataFormatException {
        byte[] packet = ZabbixProtocol.createPacket(TEST_PAYLOAD, true);

        // Manually compress to check length if needed, but ZabbixProtocol does this internally
        Deflater deflater = new Deflater();
        deflater.setInput(TEST_PAYLOAD_BYTES);
        deflater.finish();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] tempBuf = new byte[1024];
        while (!deflater.finished()) {
            int count = deflater.deflate(tempBuf);
            baos.write(tempBuf, 0, count);
        }
        deflater.end();
        byte[] compressedPayloadBytes = baos.toByteArray();

        assertEquals(ZabbixProtocol.HEADER_SIZE + compressedPayloadBytes.length, packet.length);

        ByteBuffer buffer = ByteBuffer.wrap(packet);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        byte[] signature = new byte[ZabbixProtocol.ZABBIX_HEADER_SIGNATURE.length];
        buffer.get(signature);
        assertArrayEquals(ZabbixProtocol.ZABBIX_HEADER_SIGNATURE, signature);

        byte flags = buffer.get();
        assertTrue((flags & ZabbixProtocol.FLAG_ZABBIX_PROTOCOL) != 0);
        assertTrue((flags & ZabbixProtocol.FLAG_COMPRESSION) != 0);

        int dataLength = buffer.getInt();
        assertEquals(compressedPayloadBytes.length, dataLength);

        int reservedLength = buffer.getInt();
        assertEquals(TEST_PAYLOAD_BYTES.length, reservedLength); // Original uncompressed length

        byte[] payloadInPacket = new byte[dataLength];
        buffer.get(payloadInPacket);
        assertArrayEquals(compressedPayloadBytes, payloadInPacket);

        // Verify decompression
        Inflater inflater = new Inflater();
        inflater.setInput(payloadInPacket);
        byte[] decompressedPayload = new byte[TEST_PAYLOAD_BYTES.length];
        int decompressedLength = inflater.inflate(decompressedPayload);
        inflater.end();
        assertEquals(TEST_PAYLOAD_BYTES.length, decompressedLength);
        assertArrayEquals(TEST_PAYLOAD_BYTES, decompressedPayload);
    }

    @Test
    void testCreatePacket_nullPayload() {
        byte[] packet = ZabbixProtocol.createPacket(null, false);
        assertEquals(ZabbixProtocol.HEADER_SIZE, packet.length); // Payload is empty string -> 0 length data

        ByteBuffer buffer = ByteBuffer.wrap(packet);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.position(ZabbixProtocol.ZABBIX_HEADER_SIGNATURE.length + 1); // Skip signature and flags
        int dataLength = buffer.getInt();
        assertEquals(0, dataLength);
    }

    @Test
    void testParseResponse_uncompressed() throws IOException {
        byte[] packet = ZabbixProtocol.createPacket(TEST_PAYLOAD, false);
        InputStream inputStream = new ByteArrayInputStream(packet);

        String parsedPayload = ZabbixProtocol.parseResponse(inputStream);
        assertEquals(TEST_PAYLOAD, parsedPayload);
    }

    @Test
    void testParseResponse_compressed() throws IOException {
        byte[] packet = ZabbixProtocol.createPacket(TEST_PAYLOAD, true);
        InputStream inputStream = new ByteArrayInputStream(packet);

        String parsedPayload = ZabbixProtocol.parseResponse(inputStream);
        assertEquals(TEST_PAYLOAD, parsedPayload);
    }

    @Test
    void testParseResponse_invalidSignature() {
        byte[] packet = ZabbixProtocol.createPacket(TEST_PAYLOAD, false);
        packet[0] = 'X'; // Corrupt signature
        InputStream inputStream = new ByteArrayInputStream(packet);

        ProcessingException ex = assertThrows(ProcessingException.class, () -> ZabbixProtocol.parseResponse(inputStream));
        assertTrue(ex.getMessage().contains("Invalid Zabbix header signature"));
    }

    @Test
    void testParseResponse_missingProtocolFlag() {
        // Manually craft a packet with bad flags
        ByteBuffer headerBuffer = ByteBuffer.allocate(ZabbixProtocol.HEADER_SIZE);
        headerBuffer.order(ByteOrder.LITTLE_ENDIAN);
        headerBuffer.put(ZabbixProtocol.ZABBIX_HEADER_SIGNATURE);
        headerBuffer.put((byte) 0x00); // No flags set
        headerBuffer.putInt(TEST_PAYLOAD_BYTES.length);
        headerBuffer.putInt(0);

        ByteBuffer packetBuffer = ByteBuffer.allocate(ZabbixProtocol.HEADER_SIZE + TEST_PAYLOAD_BYTES.length);
        packetBuffer.put(headerBuffer.array());
        packetBuffer.put(TEST_PAYLOAD_BYTES);

        InputStream inputStream = new ByteArrayInputStream(packetBuffer.array());
        ProcessingException ex = assertThrows(ProcessingException.class, () -> ZabbixProtocol.parseResponse(inputStream));
        assertTrue(ex.getMessage().contains("Packet does not conform to Zabbix protocol"));
    }

    @Test
    void testParseResponse_largePacketFlag() {
        ByteBuffer headerBuffer = ByteBuffer.allocate(ZabbixProtocol.HEADER_SIZE);
        headerBuffer.order(ByteOrder.LITTLE_ENDIAN);
        headerBuffer.put(ZabbixProtocol.ZABBIX_HEADER_SIGNATURE);
        headerBuffer.put((byte) (ZabbixProtocol.FLAG_ZABBIX_PROTOCOL | ZabbixProtocol.FLAG_LARGE_PACKET));
        headerBuffer.putInt(10); // Dummy length
        headerBuffer.putInt(0);   // Dummy reserved

        InputStream inputStream = new ByteArrayInputStream(headerBuffer.array()); // Only header needed for this check
        ProcessingException ex = assertThrows(ProcessingException.class, () -> ZabbixProtocol.parseResponse(inputStream));
        assertTrue(ex.getMessage().contains("LARGE_PACKET flag set"));
    }

    @Test
    void testParseResponse_dataLengthTooShort_eof() {
        byte[] packetHeader = ZabbixProtocol.createPacket(TEST_PAYLOAD, false);
        // Correct header, but we'll provide fewer bytes for the payload than declared
        ByteBuffer buffer = ByteBuffer.wrap(packetHeader);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.position(ZabbixProtocol.ZABBIX_HEADER_SIGNATURE.length + 1); // After signature and flags
        int declaredDataLength = buffer.getInt(); // Get the correct declared length

        // Create an input stream with a truncated payload
        byte[] truncatedPacket = Arrays.copyOf(packetHeader, ZabbixProtocol.HEADER_SIZE + declaredDataLength - 5);
        InputStream inputStream = new ByteArrayInputStream(truncatedPacket);

        CommunicationException ex = assertThrows(CommunicationException.class, () -> ZabbixProtocol.parseResponse(inputStream));
        assertTrue(ex.getCause() instanceof java.io.EOFException); // Should be due to reading past EOF
    }
    
    @Test
    void testParseResponse_headerTooShort_eof() {
        byte[] truncatedPacket = Arrays.copyOf(ZabbixProtocol.ZABBIX_HEADER_SIGNATURE, ZabbixProtocol.HEADER_SIZE -1);
        InputStream inputStream = new ByteArrayInputStream(truncatedPacket);

        CommunicationException ex = assertThrows(CommunicationException.class, () -> ZabbixProtocol.parseResponse(inputStream));
        assertTrue(ex.getCause() instanceof java.io.EOFException);
    }


    @Test
    void testParseResponse_corruptedCompressedData() {
        byte[] packet = ZabbixProtocol.createPacket(TEST_PAYLOAD, true);
        // Corrupt payload part of the packet
        if (packet.length > ZabbixProtocol.HEADER_SIZE + 5) { // Ensure there's payload to corrupt
            packet[ZabbixProtocol.HEADER_SIZE + 5] ^= 0xFF; // Flip some bits
        } else {
            // This test might not be meaningful if compressed payload is too short
            System.err.println("Skipping corruptedCompressedData test: payload too short.");
            return;
        }
        InputStream inputStream = new ByteArrayInputStream(packet);

        ProcessingException ex = assertThrows(ProcessingException.class, () -> ZabbixProtocol.parseResponse(inputStream));
        assertTrue(ex.getMessage().contains("Failed to decompress Zabbix payload"));
    }
    
    @Test
    void testParseResponse_compressed_zeroReservedLengthButDataPresent() {
        // Create a compressed packet
        byte[] compressedPacket = ZabbixProtocol.createPacket(TEST_PAYLOAD, true);
        ByteBuffer buffer = ByteBuffer.wrap(compressedPacket);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        // Manually set reservedLength (uncompressed size) to 0
        buffer.position(ZabbixProtocol.ZABBIX_HEADER_SIGNATURE.length + 1 + 4); // pos of reservedLength
        buffer.putInt(0);

        InputStream inputStream = new ByteArrayInputStream(compressedPacket);
        // ZabbixProtocol.parseResponse logs a warning but attempts decompression.
        // If TEST_PAYLOAD is small, it might succeed if initial buffer guess is large enough.
        // For this test, we expect it to parse correctly if the payload is small.
        // If it were a large payload where reservedLength is critical for buffer allocation, it might fail.
        // The current ZabbixProtocol.parseResponse has logic to guess if reservedLength is 0.
        String parsedPayload = ZabbixProtocol.parseResponse(inputStream);
        assertEquals(TEST_PAYLOAD, parsedPayload); // Assuming decompression guess works for small payload
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, -100})
    void testParseResponse_invalidDataLengthInHeader(int invalidLength) {
        ByteBuffer headerBuffer = ByteBuffer.allocate(ZabbixProtocol.HEADER_SIZE);
        headerBuffer.order(ByteOrder.LITTLE_ENDIAN);
        headerBuffer.put(ZabbixProtocol.ZABBIX_HEADER_SIGNATURE);
        headerBuffer.put(ZabbixProtocol.FLAG_ZABBIX_PROTOCOL);
        headerBuffer.putInt(invalidLength); // Invalid data length
        headerBuffer.putInt(0);

        InputStream inputStream = new ByteArrayInputStream(headerBuffer.array());
        ProcessingException ex = assertThrows(ProcessingException.class, () -> ZabbixProtocol.parseResponse(inputStream));
        assertTrue(ex.getMessage().contains("Invalid data length"));
    }
}
