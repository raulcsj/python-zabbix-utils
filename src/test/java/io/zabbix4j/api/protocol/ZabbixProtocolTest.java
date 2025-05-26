package io.zabbix4j.api.protocol;

import io.zabbix4j.api.exception.ZabbixProcessingException;
import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Unit tests for the {@link ZabbixProtocol} class.
 *
 * @author CSJ
 */
class ZabbixProtocolTest {

    private byte[] decompressZlib(byte[] compressedData) throws IOException, DataFormatException {
        Inflater inflater = new Inflater();
        inflater.setInput(compressedData);
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream(compressedData.length)) {
            byte[] buffer = new byte[1024];
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count == 0 && inflater.needsInput()) {
                    break; // Should not happen if data is complete
                }
                outputStream.write(buffer, 0, count);
            }
            return outputStream.toByteArray();
        } finally {
            inflater.end();
        }
    }

    @Nested
    @DisplayName("createPacket(String payload, boolean compression) Tests")
    class CreatePacketTests {

        @Test
        @DisplayName("Create packet with simple payload, no compression")
        void testCreatePacket_SimplePayload_NoCompression() throws ZabbixProcessingException {
            String payload = "Hello Zabbix";
            byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
            byte[] packet = ZabbixProtocol.createPacket(payload, false);

            assertEquals(ZabbixProtocol.HEADER_SIZE + payloadBytes.length, packet.length, "Packet length mismatch.");
            assertArrayEquals(ZabbixProtocol.ZABBIX_HEADER_BYTES, Arrays.copyOfRange(packet, 0, 4), "Header prefix mismatch.");

            ByteBuffer buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(ZabbixProtocol.FLAGS_PROTOCOL_VERSION, buffer.get(4), "Flags should be 0x01.");
            assertEquals(payloadBytes.length, buffer.getInt(5), "Data length should match payload length.");
            assertEquals(0, buffer.getInt(9), "Reserved field should be 0 for no compression.");
            assertArrayEquals(payloadBytes, Arrays.copyOfRange(packet, ZabbixProtocol.HEADER_SIZE, packet.length), "Payload data mismatch.");
        }

        @Test
        @DisplayName("Create packet with simple payload, with compression")
        void testCreatePacket_SimplePayload_WithCompression() throws ZabbixProcessingException, IOException, DataFormatException {
            String payload = "Hello Zabbix, this is a test string for compression.";
            byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
            byte[] packet = ZabbixProtocol.createPacket(payload, true);

            assertTrue(packet.length > ZabbixProtocol.HEADER_SIZE, "Packet length should be greater than header size.");
            assertArrayEquals(ZabbixProtocol.ZABBIX_HEADER_BYTES, Arrays.copyOfRange(packet, 0, 4), "Header prefix mismatch.");

            ByteBuffer buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals((byte)(ZabbixProtocol.FLAGS_PROTOCOL_VERSION | ZabbixProtocol.FLAGS_COMPRESSION), buffer.get(4), "Flags should be 0x03.");
            
            int compressedDataLength = buffer.getInt(5);
            assertEquals(payloadBytes.length, buffer.getInt(9), "Reserved field should hold uncompressed length.");
            assertEquals(ZabbixProtocol.HEADER_SIZE + compressedDataLength, packet.length, "Packet length should match header + compressed data length.");

            byte[] compressedPayload = Arrays.copyOfRange(packet, ZabbixProtocol.HEADER_SIZE, packet.length);
            byte[] decompressedPayload = decompressZlib(compressedPayload);
            assertArrayEquals(payloadBytes, decompressedPayload, "Decompressed payload should match original.");
        }

        @Test
        @DisplayName("Create packet with empty payload, no compression")
        void testCreatePacket_EmptyPayload_NoCompression() throws ZabbixProcessingException {
            String payload = "";
            byte[] packet = ZabbixProtocol.createPacket(payload, false);
            assertEquals(ZabbixProtocol.HEADER_SIZE, packet.length);
            ByteBuffer buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(0, buffer.getInt(5), "Data length should be 0 for empty payload.");
            assertEquals(0, buffer.getInt(9), "Reserved field should be 0.");
        }

        @Test
        @DisplayName("Create packet with empty payload, with compression")
        void testCreatePacket_EmptyPayload_WithCompression() throws ZabbixProcessingException, IOException, DataFormatException {
            String payload = "";
            byte[] packet = ZabbixProtocol.createPacket(payload, true);
            
            ByteBuffer buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals((byte)(ZabbixProtocol.FLAGS_PROTOCOL_VERSION | ZabbixProtocol.FLAGS_COMPRESSION), buffer.get(4));
            int compressedDataLength = buffer.getInt(5);
            assertEquals(0, buffer.getInt(9), "Uncompressed length should be 0.");
            assertEquals(ZabbixProtocol.HEADER_SIZE + compressedDataLength, packet.length);

            byte[] compressedPayload = Arrays.copyOfRange(packet, ZabbixProtocol.HEADER_SIZE, packet.length);
            byte[] decompressedPayload = decompressZlib(compressedPayload);
            assertEquals("", new String(decompressedPayload, StandardCharsets.UTF_8));
        }

        @Test
        @DisplayName("Create packet with JSON payload, no compression")
        void testCreatePacket_JsonPayload_NoCompression() throws ZabbixProcessingException {
            String jsonPayload = "{\"request\":\"sender data\",\"data\":[]}";
            byte[] payloadBytes = jsonPayload.getBytes(StandardCharsets.UTF_8);
            byte[] packet = ZabbixProtocol.createPacket(jsonPayload, false);

            assertEquals(ZabbixProtocol.HEADER_SIZE + payloadBytes.length, packet.length);
            ByteBuffer buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(payloadBytes.length, buffer.getInt(5));
            assertArrayEquals(payloadBytes, Arrays.copyOfRange(packet, ZabbixProtocol.HEADER_SIZE, packet.length));
        }

        @Test
        @DisplayName("Create packet with JSON payload, with compression")
        void testCreatePacket_JsonPayload_WithCompression() throws ZabbixProcessingException, IOException, DataFormatException {
            String jsonPayload = "{\"request\":\"sender data\",\"data\":[{\"host\":\"Test Host\",\"key\":\"item.key\",\"value\":\"123\"}]}";
            byte[] payloadBytes = jsonPayload.getBytes(StandardCharsets.UTF_8);
            byte[] packet = ZabbixProtocol.createPacket(jsonPayload, true);

            ByteBuffer buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals((byte)(ZabbixProtocol.FLAGS_PROTOCOL_VERSION | ZabbixProtocol.FLAGS_COMPRESSION), buffer.get(4));
            assertEquals(payloadBytes.length, buffer.getInt(9)); // Uncompressed length

            byte[] compressedPayload = Arrays.copyOfRange(packet, ZabbixProtocol.HEADER_SIZE, packet.length);
            byte[] decompressedPayload = decompressZlib(compressedPayload);
            assertEquals(jsonPayload, new String(decompressedPayload, StandardCharsets.UTF_8));
        }

        @Test
        @DisplayName("Create packet with null payload should throw IllegalArgumentException")
        void testCreatePacket_NullPayload_ThrowsException() {
            Exception e = assertThrows(IllegalArgumentException.class, () -> ZabbixProtocol.createPacket(null, false));
            assertEquals("Payload cannot be null.", e.getMessage());
        }
    }

    @Nested
    @DisplayName("parsePacket(InputStream inputStream) Tests")
    class ParsePacketTests {

        @Test
        @DisplayName("Parse valid packet with no compression")
        void testParsePacket_ValidPacket_NoCompression() throws ZabbixProcessingException, IOException {
            String payload = "Zabbix Sender Data";
            byte[] packet = ZabbixProtocol.createPacket(payload, false);
            InputStream inputStream = new ByteArrayInputStream(packet);
            String parsedPayload = ZabbixProtocol.parsePacket(inputStream);
            assertEquals(payload, parsedPayload);
        }

        @Test
        @DisplayName("Parse valid packet with compression")
        void testParsePacket_ValidPacket_WithCompression() throws ZabbixProcessingException, IOException {
            String payload = "Zabbix Sender Data with compression enabled for testing purposes.";
            byte[] packet = ZabbixProtocol.createPacket(payload, true);
            InputStream inputStream = new ByteArrayInputStream(packet);
            String parsedPayload = ZabbixProtocol.parsePacket(inputStream);
            assertEquals(payload, parsedPayload);
        }
        
        @Test
        @DisplayName("Parse valid empty payload packet with no compression")
        void testParsePacket_ValidEmptyPayload_NoCompression() throws ZabbixProcessingException, IOException {
            String payload = "";
            byte[] packet = ZabbixProtocol.createPacket(payload, false);
            InputStream inputStream = new ByteArrayInputStream(packet);
            String parsedPayload = ZabbixProtocol.parsePacket(inputStream);
            assertEquals(payload, parsedPayload);
        }

        @Test
        @DisplayName("Parse valid empty payload packet with compression")
        void testParsePacket_ValidEmptyPayload_WithCompression() throws ZabbixProcessingException, IOException {
            String payload = "";
            byte[] packet = ZabbixProtocol.createPacket(payload, true);
            InputStream inputStream = new ByteArrayInputStream(packet);
            String parsedPayload = ZabbixProtocol.parsePacket(inputStream);
            assertEquals(payload, parsedPayload);
        }

        @Test
        @DisplayName("Parse packet with stream too short for header")
        void testParsePacket_StreamTooShortForHeader() {
            byte[] shortPacket = {'Z', 'B', 'X', 'D', 0x01}; // Only 5 bytes
            InputStream inputStream = new ByteArrayInputStream(shortPacket);
            Exception e = assertThrows(ZabbixProcessingException.class, () -> ZabbixProtocol.parsePacket(inputStream));
            assertTrue(e.getMessage().contains("Failed to read complete Zabbix protocol header"));
        }

        @Test
        @DisplayName("Parse packet with invalid header prefix")
        void testParsePacket_InvalidHeaderPrefix() {
            byte[] header = "NOTZBXD".getBytes(StandardCharsets.US_ASCII); // Invalid prefix
            ByteBuffer packetBuffer = ByteBuffer.allocate(ZabbixProtocol.HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
            packetBuffer.put(header, 0, 4); // "NOTZ"
            packetBuffer.put((byte) ZabbixProtocol.FLAGS_PROTOCOL_VERSION);
            packetBuffer.putInt(0); // data length
            packetBuffer.putInt(0); // reserved
            InputStream inputStream = new ByteArrayInputStream(packetBuffer.array());
            Exception e = assertThrows(ZabbixProcessingException.class, () -> ZabbixProtocol.parsePacket(inputStream));
            assertTrue(e.getMessage().contains("Invalid Zabbix protocol header: Magic number 'ZBXD' not found"));
        }

        @Test
        @DisplayName("Parse packet with invalid flags (no version bit)")
        void testParsePacket_InvalidFlags_NoVersionBit() {
            ByteBuffer packetBuffer = ByteBuffer.allocate(ZabbixProtocol.HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
            packetBuffer.put(ZabbixProtocol.ZABBIX_HEADER_BYTES);
            packetBuffer.put((byte) 0x00); // Flags without version bit
            packetBuffer.putInt(0);
            packetBuffer.putInt(0);
            InputStream inputStream = new ByteArrayInputStream(packetBuffer.array());
            Exception e = assertThrows(ZabbixProcessingException.class, () -> ZabbixProtocol.parsePacket(inputStream));
            assertTrue(e.getMessage().contains("Invalid Zabbix protocol flags: version bit (0x01) not set"));
        }

        @Test
        @DisplayName("Parse packet with large packet flag (unsupported)")
        void testParsePacket_InvalidFlags_LargePacketNotSupported() {
            ByteBuffer packetBuffer = ByteBuffer.allocate(ZabbixProtocol.HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
            packetBuffer.put(ZabbixProtocol.ZABBIX_HEADER_BYTES);
            packetBuffer.put((byte) (ZabbixProtocol.FLAGS_PROTOCOL_VERSION | ZabbixProtocol.FLAGS_LARGE_PACKET));
            packetBuffer.putInt(0);
            packetBuffer.putInt(0);
            InputStream inputStream = new ByteArrayInputStream(packetBuffer.array());
            Exception e = assertThrows(ZabbixProcessingException.class, () -> ZabbixProtocol.parsePacket(inputStream));
            assertTrue(e.getMessage().contains("Large packet mode (flag 0x04) is not supported"));
        }

        @Test
        @DisplayName("Parse packet with corrupted compressed data")
        void testParsePacket_CorruptedCompressedData() {
            String payload = "This will be compressed, then corrupted.";
            byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
            
            // Create a correctly compressed payload first
            byte[] compressedPayload;
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                 DeflaterOutputStream dos = new DeflaterOutputStream(baos, new Deflater(Deflater.DEFAULT_COMPRESSION, false))) {
                dos.write(payloadBytes);
                dos.finish();
                compressedPayload = baos.toByteArray();
            } catch (IOException e) {
                fail("Setup for corrupted data test failed during compression.", e);
                return;
            }

            // Corrupt the compressed data (e.g., flip some bits or truncate)
            if (compressedPayload.length > 5) {
                compressedPayload[compressedPayload.length / 2]++; // Simple corruption
            } else {
                // If too short, just append garbage (though this might not always trigger DataFormatException)
                byte[] garbage = {1,2,3};
                byte[] newCorrupted = new byte[compressedPayload.length + garbage.length];
                System.arraycopy(compressedPayload, 0, newCorrupted, 0, compressedPayload.length);
                System.arraycopy(garbage,0, newCorrupted, compressedPayload.length, garbage.length);
                compressedPayload = newCorrupted;
            }


            ByteBuffer packetBuffer = ByteBuffer.allocate(ZabbixProtocol.HEADER_SIZE + compressedPayload.length).order(ByteOrder.LITTLE_ENDIAN);
            packetBuffer.put(ZabbixProtocol.ZABBIX_HEADER_BYTES);
            packetBuffer.put((byte) (ZabbixProtocol.FLAGS_PROTOCOL_VERSION | ZabbixProtocol.FLAGS_COMPRESSION));
            packetBuffer.putInt(compressedPayload.length); // Length of corrupted compressed data
            packetBuffer.putInt(payloadBytes.length);      // Original uncompressed length
            packetBuffer.put(compressedPayload);

            InputStream inputStream = new ByteArrayInputStream(packetBuffer.array());
            Exception e = assertThrows(ZabbixProcessingException.class, () -> ZabbixProtocol.parsePacket(inputStream));
            assertTrue(e.getMessage().contains("Failed to decompress Zabbix packet payload") || e.getMessage().contains("Decompression error"),
                       "Exception message should indicate decompression failure. Actual: " + e.getMessage());
        }

        @Test
        @DisplayName("Parse packet where payload is shorter than header indicates")
        void testParsePacket_PayloadShorterThanHeaderIndicates() {
            ByteBuffer packetBuffer = ByteBuffer.allocate(ZabbixProtocol.HEADER_SIZE + 5).order(ByteOrder.LITTLE_ENDIAN); // 5 bytes of actual payload
            packetBuffer.put(ZabbixProtocol.ZABBIX_HEADER_BYTES);
            packetBuffer.put((byte) ZabbixProtocol.FLAGS_PROTOCOL_VERSION);
            packetBuffer.putInt(10); // Header says 10 bytes of payload
            packetBuffer.putInt(0);
            packetBuffer.put("short".getBytes(StandardCharsets.UTF_8)); // Actual payload is 5 bytes

            InputStream inputStream = new ByteArrayInputStream(packetBuffer.array());
            Exception e = assertThrows(ZabbixProcessingException.class, () -> ZabbixProtocol.parsePacket(inputStream));
            assertTrue(e.getMessage().contains("Unexpected end of stream while reading payload"),
                       "Exception message should indicate insufficient payload data. Actual: " + e.getMessage());
        }
    }
}
