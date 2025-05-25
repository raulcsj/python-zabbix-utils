package io.zabbix4j.api.common;

import io.zabbix4j.api.exceptions.ProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Handles the Zabbix communication protocol for sending and receiving data.
 * <p>
 * This class provides methods to create Zabbix protocol packets (header + payload)
 * and parse responses received from a Zabbix server or agent. It supports
 * optional zlib compression.
 * </p>
 * This class is non-instantiable.
 *
 * @author CSJ
 */
public final class ZabbixProtocol {

    private static final Logger logger = LoggerFactory.getLogger(ZabbixProtocol.class);

    /**
     * Zabbix protocol header prefix "ZBXD".
     */
    public static final byte[] ZABBIX_HEADER_PREFIX = "ZBXD".getBytes(StandardCharsets.US_ASCII);

    /**
     * Total size of the Zabbix protocol header in bytes.
     * Header consists of: 4 bytes (prefix) + 1 byte (flags) + 4 bytes (datalen) + 4 bytes (reserved).
     */
    public static final int HEADER_SIZE = 13;

    /**
     * Flag indicating the protocol version (0x01). This flag must be present.
     */
    public static final byte FLAG_PROTOCOL_VERSION = 0x01;

    /**
     * Flag indicating that the payload is compressed using zlib (0x02).
     */
    public static final byte FLAG_COMPRESSION = 0x02;

    /**
     * Flag indicating a large packet (0x04). This implementation does not support large packets.
     */
    public static final byte FLAG_LARGE_PACKET = 0x04;


    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private ZabbixProtocol() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    /**
     * Reads exactly {@code numBytes} from the given {@code InputStream}.
     *
     * @param inputStream The stream to read from.
     * @param numBytes    The number of bytes to read.
     * @return A byte array containing the read bytes.
     * @throws IOException  if an I/O error occurs, or if the end of the stream
     *                      is reached before {@code numBytes} have been read (EOFException).
     */
    private static byte[] readFully(InputStream inputStream, int numBytes) throws IOException {
        if (numBytes < 0) {
            throw new IllegalArgumentException("Number of bytes to read cannot be negative.");
        }
        if (numBytes == 0) {
            return new byte[0];
        }
        byte[] buffer = new byte[numBytes];
        int bytesRead = 0;
        while (bytesRead < numBytes) {
            int count = inputStream.read(buffer, bytesRead, numBytes - bytesRead);
            if (count == -1) {
                throw new EOFException(String.format("End of stream reached after reading %d bytes, expected %d bytes.", bytesRead, numBytes));
            }
            bytesRead += count;
        }
        return buffer;
    }

    /**
     * Creates a Zabbix protocol packet (header + payload).
     *
     * @param payloadString The string payload to send (e.g., JSON for Sender, item key for Getter).
     * @param compress      Whether to compress the payload using zlib.
     * @return A byte array representing the full Zabbix packet.
     * @throws IOException         if an I/O error occurs during compression.
     * @throws ProcessingException if the payload is null/empty or an error occurs during packet creation.
     */
    public static byte[] createPacket(String payloadString, boolean compress) throws IOException, ProcessingException {
        if (payloadString == null || payloadString.isEmpty()) {
            throw new ProcessingException("Payload cannot be null or empty");
        }

        logger.debug("Preparing Zabbix packet. Payload (first 100 chars, masked if needed): '{}', Compression: {}",
                DataMaskingUtils.maskSecret(payloadString.substring(0, Math.min(payloadString.length(), 100))), compress);

        byte[] payloadBytes = payloadString.getBytes(StandardCharsets.UTF_8);
        int originalPayloadLength = payloadBytes.length;
        byte flags = FLAG_PROTOCOL_VERSION;
        int datalen;
        int reserved = 0;

        if (compress) {
            flags |= FLAG_COMPRESSION;
            reserved = originalPayloadLength;

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            // Deflater with 'true' for 'nowrap' means raw DEFLATE data, Zabbix expects zlib header/checksum.
            // Use DeflaterOutputStream which adds zlib headers/trailers by default.
            try (DeflaterOutputStream dos = new DeflaterOutputStream(baos)) {
                dos.write(payloadBytes);
            } // DeflaterOutputStream is closed here, which flushes and finishes compression.
            payloadBytes = baos.toByteArray();
            datalen = payloadBytes.length;
            logger.debug("Payload compressed. Original size: {}, Compressed size: {}", originalPayloadLength, datalen);
        } else {
            datalen = originalPayloadLength;
            // reserved remains 0
        }

        // Header construction
        ByteBuffer headerBuffer = ByteBuffer.allocate(HEADER_SIZE);
        headerBuffer.order(ByteOrder.LITTLE_ENDIAN);
        headerBuffer.put(ZABBIX_HEADER_PREFIX);
        headerBuffer.put(flags);
        headerBuffer.putInt(datalen);
        headerBuffer.putInt(reserved);
        byte[] header = headerBuffer.array();

        // Concatenate header and payload
        ByteBuffer packetBuffer = ByteBuffer.allocate(HEADER_SIZE + datalen);
        packetBuffer.put(header);
        packetBuffer.put(payloadBytes);
        byte[] fullPacket = packetBuffer.array();

        logger.debug("Zabbix packet created. Total size: {} bytes. Header: {}", fullPacket.length, Arrays.toString(header));
        // Avoid logging fullPacket if it's large or sensitive, header is usually fine.

        return fullPacket;
    }

    /**
     * Parses a synchronously received Zabbix packet from an InputStream.
     *
     * @param inputStream The InputStream to read the packet from.
     * @return The decoded payload as a String.
     * @throws IOException         if an I/O error occurs during reading or decompression.
     * @throws ProcessingException if the received packet is invalid (e.g., header mismatch, unsupported flags, decompression error).
     */
    public static String parseSynchronousPacket(InputStream inputStream) throws IOException, ProcessingException {
        logger.debug("Attempting to parse synchronous Zabbix packet from input stream.");

        byte[] headerBytes = readFully(inputStream, HEADER_SIZE);
        ByteBuffer headerBuffer = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN);

        byte[] prefixBytes = new byte[ZABBIX_HEADER_PREFIX.length];
        headerBuffer.get(prefixBytes);
        if (!Arrays.equals(prefixBytes, ZABBIX_HEADER_PREFIX)) {
            throw new ProcessingException("Invalid Zabbix protocol header received: Prefix mismatch. Expected: "
                    + Arrays.toString(ZABBIX_HEADER_PREFIX) + ", Got: " + Arrays.toString(prefixBytes));
        }

        byte flags = headerBuffer.get();
        int datalen = headerBuffer.getInt();
        int reserved = headerBuffer.getInt(); // Uncompressed size if compressed, otherwise 0 or not strictly defined by Zabbix for uncompressed.

        logger.debug("Received Zabbix packet header. Flags: 0x{}, Datalen: {}, Reserved: {}",
                String.format("%02X", flags), datalen, reserved);


        if ((flags & FLAG_PROTOCOL_VERSION) == 0) {
            throw new ProcessingException("Invalid Zabbix protocol flags: Missing protocol version flag (0x01). Flags: " + String.format("%02X", flags));
        }
        if ((flags & FLAG_LARGE_PACKET) != 0) {
            // Zabbix protocol defines datalen as uint64 if FLAG_LARGE_PACKET is set.
            // Our current datalen is int (uint32), so this check is more about protocol adherence.
            throw new ProcessingException("Large packet flag (0x04) received. Current implementation does not support large packets. Flags: " + String.format("%02X", flags));
        }
        if (datalen < 0) { // Should not happen if datalen is read as unsigned int, but Java int is signed.
             throw new ProcessingException("Invalid data length received: " + datalen + ". Must be non-negative.");
        }
        if (datalen == 0) { // Empty payload
            logger.debug("Received packet with empty payload (datalen=0).");
            return "";
        }


        byte[] responseBodyBytes = readFully(inputStream, datalen);
        byte[] finalPayloadBytes;

        if ((flags & FLAG_COMPRESSION) != 0) {
            logger.debug("Packet is compressed. Decompressing {} bytes. Expected uncompressed size (from reserved field): {}", datalen, reserved);
            ByteArrayOutputStream baosDecompressed = new ByteArrayOutputStream();
            try (InflaterInputStream iis = new InflaterInputStream(new java.io.ByteArrayInputStream(responseBodyBytes))) {
                byte[] buffer = new byte[1024];
                int count;
                while ((count = iis.read(buffer)) != -1) {
                    baosDecompressed.write(buffer, 0, count);
                }
            } catch (IOException e) {
                throw new ProcessingException("Failed to decompress Zabbix response payload.", e);
            }
            finalPayloadBytes = baosDecompressed.toByteArray();
            // Zabbix documentation for sender protocol (ZBXD\1) mentions `reserved` field is uncompressed data length.
            // We can optionally use it to verify if (reserved != 0 && finalPayloadBytes.length != reserved)
            if (reserved != 0 && finalPayloadBytes.length != reserved) {
                logger.warn("Decompressed payload size ({}) does not match 'reserved' field value ({}). Using actual decompressed size.",
                        finalPayloadBytes.length, reserved);
            }
            logger.debug("Payload decompressed. Original (compressed) size: {}, Decompressed size: {}", datalen, finalPayloadBytes.length);
        } else {
            finalPayloadBytes = responseBodyBytes;
        }

        String decodedPayload = new String(finalPayloadBytes, StandardCharsets.UTF_8);
        logger.debug("Received and parsed Zabbix packet. Payload (first 100 chars, masked if needed): '{}'",
                DataMaskingUtils.maskSecret(decodedPayload.substring(0, Math.min(decodedPayload.length(), 100))));

        return decodedPayload;
    }
}
