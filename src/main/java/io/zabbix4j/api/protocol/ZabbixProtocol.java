package io.zabbix4j.api.protocol;

import io.zabbix4j.api.exception.ZabbixProcessingException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Utility class for creating and interpreting Zabbix communication protocol packets.
 * This class provides methods to construct packets according to the Zabbix sender protocol.
 * It cannot be instantiated.
 *
 * @author Your Name
 */
public final class ZabbixProtocol {

    /**
     * Zabbix protocol header identifier ("ZBXD").
     */
    public static final byte[] ZABBIX_HEADER_BYTES = "ZBXD".getBytes(StandardCharsets.US_ASCII);

    /**
     * Flag indicating Zabbix communications protocol (version 1).
     */
    public static final int FLAGS_PROTOCOL_VERSION = 0x01;

    /**
     * Flag indicating that the packet data is compressed.
     */
    public static final int FLAGS_COMPRESSION = 0x02;

    /**
     * Flag indicating a large packet format. This library does not support sending large packets.
     * The Zabbix protocol supports data lengths up to 2^32 - 1 bytes.
     * A "large packet" flag (0x04) would extend this with another 4 bytes for length,
     * but this is typically not needed and not implemented here for sending.
     */
    public static final int FLAGS_LARGE_PACKET = 0x04;

    /**
     * Total size of the Zabbix protocol header in bytes.
     * Consists of:
     * <ul>
     *     <li>4 bytes for "ZBXD" prefix.</li>
     *     <li>1 byte for flags.</li>
     *     <li>4 bytes for data length (compressed or uncompressed).</li>
     *     <li>4 bytes for reserved field (used for uncompressed data length if compression is enabled).</li>
     * </ul>
     */
    public static final int HEADER_SIZE = 13; // ZABBIX_HEADER_BYTES.length (4) + 1 (flags) + 4 (data len) + 4 (reserved/uncompressed len)

    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private ZabbixProtocol() {
    }

    /**
     * Creates a Zabbix protocol packet from a payload string.
     * The payload is encoded using UTF-8. If compression is enabled,
     * the payload is compressed using zlib.
     *
     * @param payload     The string data to be sent. The caller is responsible for any
     *                    JSON serialization if the payload needs to be a JSON object.
     * @param compression {@code true} to compress the payload, {@code false} otherwise.
     * @return A byte array representing the complete Zabbix protocol packet.
     * @throws IllegalArgumentException if the payload is null.
     * @throws ZabbixProcessingException if an error occurs during UTF-8 encoding or compression.
     */
    public static byte[] createPacket(String payload, boolean compression) throws ZabbixProcessingException {
        if (payload == null) {
            throw new IllegalArgumentException("Payload cannot be null.");
        }

        byte[] requestDataBytes;
        try {
            requestDataBytes = payload.getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            // This should not happen with UTF-8, but as a safeguard
            throw new ZabbixProcessingException("Failed to encode payload to UTF-8.", e);
        }

        int uncompressedDataLen = requestDataBytes.length;
        byte currentFlags;
        int dataLenForHeader;
        int reservedForHeader;
        byte[] dataToSend = requestDataBytes; // Initially assume no compression

        if (compression) {
            currentFlags = (byte) (FLAGS_PROTOCOL_VERSION | FLAGS_COMPRESSION);
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                 DeflaterOutputStream dos = new DeflaterOutputStream(baos, new Deflater(Deflater.DEFAULT_COMPRESSION, false))) { // false for no zlib header
                dos.write(requestDataBytes);
                dos.finish(); // Ensure all data is compressed
                dataToSend = baos.toByteArray();
            } catch (IOException e) {
                throw new ZabbixProcessingException("Failed to compress payload.", e);
            }
            dataLenForHeader = dataToSend.length;
            reservedForHeader = uncompressedDataLen;
        } else {
            currentFlags = (byte) FLAGS_PROTOCOL_VERSION;
            dataLenForHeader = uncompressedDataLen;
            reservedForHeader = 0;
        }

        // Header: 4 bytes "ZBXD" + 1 byte flags + 4 bytes data length + 4 bytes reserved
        ByteBuffer headerBuffer = ByteBuffer.allocate(HEADER_SIZE);
        headerBuffer.order(ByteOrder.LITTLE_ENDIAN);

        headerBuffer.put(ZABBIX_HEADER_BYTES); // Bytes 0-3: "ZBXD"
        headerBuffer.put(currentFlags);          // Byte 4: Flags
        headerBuffer.putInt(dataLenForHeader);   // Bytes 5-8: Data length
        headerBuffer.putInt(reservedForHeader);  // Bytes 9-12: Reserved/Uncompressed length

        byte[] headerBytes = headerBuffer.array();

        // Concatenate header and data
        ByteBuffer packetBuffer = ByteBuffer.allocate(HEADER_SIZE + dataToSend.length);
        packetBuffer.put(headerBytes);
        packetBuffer.put(dataToSend);

        return packetBuffer.array();
    }

    /**
     * Parses a Zabbix protocol packet from an InputStream.
     * This method reads the header, validates it, reads the payload,
     * and decompresses it if necessary. The payload is then converted to a UTF-8 string.
     * The provided InputStream is not closed by this method.
     *
     * @param inputStream The input stream to read the Zabbix packet from.
     * @return The (decompressed) payload as a UTF-8 string.
     * @throws IOException if an I/O error occurs when reading from the stream.
     * @throws ZabbixProcessingException if a Zabbix protocol error occurs (e.g., invalid header,
     *                                   unsupported flags, decompression failure) or if an
     *                                   IOException occurs during processing.
     */
    public static String parsePacket(InputStream inputStream) throws IOException, ZabbixProcessingException {
        byte[] headerBytes = new byte[HEADER_SIZE];
        int bytesRead = inputStream.read(headerBytes, 0, HEADER_SIZE);

        if (bytesRead < HEADER_SIZE) {
            throw new ZabbixProcessingException("Failed to read complete Zabbix protocol header. Expected "
                                                + HEADER_SIZE + " bytes, got " + bytesRead + ".");
        }

        // Validate "ZBXD" prefix
        if (!Arrays.equals(Arrays.copyOfRange(headerBytes, 0, ZABBIX_HEADER_BYTES.length), ZABBIX_HEADER_BYTES)) {
            throw new ZabbixProcessingException("Invalid Zabbix protocol header: Magic number 'ZBXD' not found.");
        }

        ByteBuffer headerBuffer = ByteBuffer.wrap(headerBytes);
        headerBuffer.order(ByteOrder.LITTLE_ENDIAN);
        // Skip "ZBXD"
        headerBuffer.position(ZABBIX_HEADER_BYTES.length);

        byte flags = headerBuffer.get();
        int dataLength = headerBuffer.getInt();
        // int reservedLength = headerBuffer.getInt(); // Unused if not compressed, or used for expected uncompressed size

        // Check flags
        if ((flags & FLAGS_PROTOCOL_VERSION) == 0) {
            throw new ZabbixProcessingException("Invalid Zabbix protocol flags: version bit (0x01) not set. Flags: " + String.format("0x%02X", flags));
        }
        if ((flags & FLAGS_LARGE_PACKET) != 0) {
            throw new ZabbixProcessingException("Large packet mode (flag 0x04) is not supported by this library. Flags: " + String.format("0x%02X", flags));
        }

        // Read payload data
        if (dataLength < 0) { // Check for negative data length, which can indicate issues
            throw new ZabbixProcessingException("Invalid data length in Zabbix header: " + dataLength);
        }
        if (dataLength == 0) { // Handle empty payload case
             return "";
        }

        byte[] payloadData = new byte[dataLength];
        int totalPayloadBytesRead = 0;
        while (totalPayloadBytesRead < dataLength) {
            int payloadBytesRead = inputStream.read(payloadData, totalPayloadBytesRead, dataLength - totalPayloadBytesRead);
            if (payloadBytesRead == -1) { // EOF
                throw new ZabbixProcessingException("Unexpected end of stream while reading payload. Expected "
                                                    + dataLength + " bytes, got " + totalPayloadBytesRead + ".");
            }
            totalPayloadBytesRead += payloadBytesRead;
        }


        byte[] finalPayloadBytes;
        boolean isCompressed = (flags & FLAGS_COMPRESSION) != 0;

        if (isCompressed) {
            try (ByteArrayInputStream bais = new ByteArrayInputStream(payloadData);
                 InflaterInputStream iis = new InflaterInputStream(bais);
                 ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                
                byte[] buffer = new byte[1024];
                int len;
                while ((len = iis.read(buffer)) > 0) {
                    baos.write(buffer, 0, len);
                }
                finalPayloadBytes = baos.toByteArray();
            } catch (IOException e) {
                throw new ZabbixProcessingException("Failed to decompress Zabbix packet payload.", e);
            }
        } else {
            finalPayloadBytes = payloadData;
        }

        try {
            return new String(finalPayloadBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Should not happen with UTF-8
            throw new ZabbixProcessingException("Failed to decode payload to UTF-8 string.", e);
        }
    }
}
