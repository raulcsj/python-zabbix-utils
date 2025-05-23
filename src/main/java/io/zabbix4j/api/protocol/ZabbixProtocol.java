package io.zabbix4j.api.protocol;

import io.zabbix4j.api.exception.CommunicationException;
import io.zabbix4j.api.exception.ProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import java.util.zip.DataFormatException;

/**
 * Handles the Zabbix Sender and Zabbix Agent communication protocol.
 * This includes creating packets to send data and parsing responses from Zabbix.
 *
 * @author ShortRoundDev
 */
public final class ZabbixProtocol {

    private static final Logger logger = LoggerFactory.getLogger(ZabbixProtocol.class);

    /**
     * Zabbix protocol header identifier "ZBXD".
     */
    public static final byte[] ZABBIX_HEADER_SIGNATURE = "ZBXD".getBytes(StandardCharsets.US_ASCII);

    /**
     * Total size of the Zabbix protocol header in bytes.
     * Header: "ZBXD" (4 bytes) + flags (1 byte) + dataLength (4 bytes) + reserved (4 bytes) = 13 bytes.
     */
    public static final int HEADER_SIZE = 13;

    /**
     * Flag indicating the Zabbix protocol format. Must be set.
     */
    public static final byte FLAG_ZABBIX_PROTOCOL = 0x01;

    /**
     * Flag indicating that the payload is compressed.
     */
    public static final byte FLAG_COMPRESSION = 0x02;

    /**
     * Flag indicating a large packet (not supported by this implementation).
     */
    public static final byte FLAG_LARGE_PACKET = 0x04; // Not currently handled beyond detection

    private ZabbixProtocol() {
        // Private constructor to prevent instantiation
    }

    /**
     * Creates a Zabbix protocol packet.
     *
     * @param payloadString The string payload (JSON for sender, item key for agent getter).
     * @param compress      Whether to compress the payload.
     * @return A byte array representing the complete Zabbix packet (header + payload).
     * @throws ProcessingException if there's an error during packet creation (e.g., compression).
     */
    public static byte[] createPacket(String payloadString, boolean compress) throws ProcessingException {
        if (payloadString == null) {
            payloadString = ""; // Ensure non-null payload
        }
        logger.debug("Creating Zabbix packet. Payload (first 200 chars): '{}', Compress: {}",
                payloadString.substring(0, Math.min(payloadString.length(), 200)), compress);

        byte[] payloadBytes = payloadString.getBytes(StandardCharsets.UTF_8);
        int uncompressedLength = payloadBytes.length;
        byte flags = FLAG_ZABBIX_PROTOCOL;
        int reserved = 0;

        if (compress) {
            flags |= FLAG_COMPRESSION;
            reserved = uncompressedLength; // Store original length in reserved field
            Deflater deflater = new Deflater(); // Uses ZLIB format by default
            deflater.setInput(payloadBytes);
            deflater.finish();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            while (!deflater.finished()) {
                int count = deflater.deflate(buffer);
                baos.write(buffer, 0, count);
            }
            deflater.end();
            payloadBytes = baos.toByteArray();
            logger.debug("Compressed payload. Original size: {}, Compressed size: {}", uncompressedLength, payloadBytes.length);
        }

        int dataLength = payloadBytes.length;

        // Check for large packet not by payload size but by fitting lengths into 4 bytes
        // This check is more about the protocol's own dataLength/reserved fields
        if ((dataLength & 0xFFFFFFFF00000000L) != 0 || (reserved & 0xFFFFFFFF00000000L) != 0) {
            // This condition implies dataLength or reserved exceed what a 4-byte unsigned int can hold.
            // Java ints are signed, but we are mimicking unsigned 32-bit int behavior for protocol.
            // The ByteBuffer putInt correctly handles this by taking the lower 32 bits.
            // FLAG_LARGE_PACKET is more about Zabbix internal >2GB limits.
            // For now, we don't set FLAG_LARGE_PACKET, assuming payloads are reasonably sized.
            // Zabbix server might set it on response if it needs to.
        }


        ByteBuffer headerBuffer = ByteBuffer.allocate(HEADER_SIZE);
        headerBuffer.order(ByteOrder.LITTLE_ENDIAN);
        headerBuffer.put(ZABBIX_HEADER_SIGNATURE);
        headerBuffer.put(flags);
        headerBuffer.putInt(dataLength); // Length of (possibly compressed) data
        headerBuffer.putInt(reserved);   // Uncompressed data length if compressed, else 0

        byte[] header = headerBuffer.array();

        ByteBuffer packetBuffer = ByteBuffer.allocate(HEADER_SIZE + dataLength);
        packetBuffer.put(header);
        packetBuffer.put(payloadBytes);

        byte[] finalPacket = packetBuffer.array();
        logger.debug("Zabbix packet created. Total size: {}. Header: {}", finalPacket.length, Arrays.toString(header));
        return finalPacket;
    }

    /**
     * Parses a Zabbix protocol response from an InputStream.
     *
     * @param inputStream The InputStream to read the Zabbix response from.
     * @return The (decompressed, if applicable) UTF-8 payload string.
     * @throws CommunicationException if there's an I/O error or unexpected EOF.
     * @throws ProcessingException    if the packet is malformed, uses unsupported features (like large packets),
     *                                or if there's an error during decompression/decoding.
     */
    public static String parseResponse(InputStream inputStream) throws CommunicationException, ProcessingException {
        if (inputStream == null) {
            throw new IllegalArgumentException("InputStream cannot be null.");
        }
        try {
            DataInputStream dis = new DataInputStream(inputStream);
            byte[] headerBytes = new byte[HEADER_SIZE];
            dis.readFully(headerBytes); // Throws EOFException if not enough bytes

            logger.debug("Received Zabbix response header: {}", Arrays.toString(headerBytes));

            ByteBuffer headerBuffer = ByteBuffer.wrap(headerBytes);
            headerBuffer.order(ByteOrder.LITTLE_ENDIAN);

            byte[] signature = new byte[ZABBIX_HEADER_SIGNATURE.length];
            headerBuffer.get(signature);
            if (!Arrays.equals(signature, ZABBIX_HEADER_SIGNATURE)) {
                throw new ProcessingException("Invalid Zabbix header signature. Expected '" +
                        new String(ZABBIX_HEADER_SIGNATURE, StandardCharsets.US_ASCII) + "', got '" +
                        new String(signature, StandardCharsets.US_ASCII) + "'.");
            }

            byte flags = headerBuffer.get();
            int dataLength = headerBuffer.getInt();
            int reservedLength = headerBuffer.getInt(); // Uncompressed length if compressed

            if ((flags & FLAG_ZABBIX_PROTOCOL) == 0) {
                throw new ProcessingException("Packet does not conform to Zabbix protocol (flag 0x01 not set). Flags: " + String.format("0x%02X", flags));
            }

            if ((flags & FLAG_LARGE_PACKET) != 0) {
                // According to zbx_comms_get_data_len and zbx_comms_recv_data in Zabbix C code,
                // if FLAG_LARGE_PACKET is set, dataLength contains the lower 32 bits
                // and reservedLength contains the higher 32 bits of the actual data length.
                // This client currently does not support packets this large.
                throw new ProcessingException("Received Zabbix packet with LARGE_PACKET flag set. This client does not support >4GB payloads.");
            }

            if (dataLength < 0) { // Should not happen if read as unsigned, but Java ints are signed.
                throw new ProcessingException("Invalid data length in Zabbix packet: " + dataLength);
            }
            if (dataLength > (1024 * 1024 * 128)) { // Sanity check: 128MB, Zabbix default is 128MB limit
                 logger.warn("Received Zabbix packet with data length: {} bytes. This may be large.", dataLength);
            }


            byte[] payloadBytes = new byte[dataLength];
            dis.readFully(payloadBytes); // Throws EOFException if not enough bytes

            logger.debug("Received Zabbix response payload. Size: {}. Compressed: {}",
                    payloadBytes.length, (flags & FLAG_COMPRESSION) != 0);


            if ((flags & FLAG_COMPRESSION) != 0) {
                Inflater inflater = new Inflater(); // Uses ZLIB format by default
                inflater.setInput(payloadBytes, 0, dataLength);

                int finalSize = reservedLength; // This is the uncompressed size
                if (finalSize == 0 && dataLength > 0) {
                    // If reservedLength is 0 but data is compressed, it's problematic.
                    // Zabbix C code (comms.c) seems to allow decompressing to a max buffer
                    // if reservedLength is 0. For robustness, try with a reasonable max.
                    // However, standard behavior is reservedLength should be correct.
                    logger.warn("Compressed Zabbix packet has reservedLength=0. Decompression might be unreliable.");
                    // Attempt to decompress into a buffer that's a multiple of original, up to a limit.
                    finalSize = dataLength * 5; // Guess, up to a max
                    if (finalSize > (1024 * 1024 * 128)) finalSize = 1024 * 1024 * 128;
                }
                 if (finalSize <=0 && dataLength >0) { // If still no valid finalSize, and there is data, this is an issue
                    throw new ProcessingException("Cannot determine uncompressed size for compressed Zabbix data. reservedLength=" + reservedLength);
                }


                ByteArrayOutputStream baos = new ByteArrayOutputStream(finalSize > 0 ? finalSize : dataLength * 2); // Initial capacity
                byte[] buffer = new byte[1024];
                try {
                    while (!inflater.finished() && inflater.getBytesRead() < dataLength) {
                        if (inflater.needsInput() && inflater.getBytesRead() < dataLength) {
                             // Should not happen if all data is provided via setInput initially
                            throw new ProcessingException("Inflater needs input unexpectedly during Zabbix payload decompression.");
                        }
                        int count = inflater.inflate(buffer);
                        if (count == 0 && inflater.needsDictionary()) {
                             throw new ProcessingException("Zabbix payload decompression failed: needs dictionary.");
                        }
                        if (count == 0 && inflater.finished()) break; // Successfully finished
                        if (count == 0) { // No bytes inflated, not finished, not needs dict/input
                            throw new ProcessingException("Zabbix payload decompression stalled or error.");
                        }
                        baos.write(buffer, 0, count);
                    }
                } catch (DataFormatException e) {
                    throw new ProcessingException("Failed to decompress Zabbix payload: " + e.getMessage(), e);
                } finally {
                    inflater.end();
                }
                payloadBytes = baos.toByteArray();
                logger.debug("Decompressed payload. Original (compressed) size: {}, Decompressed size: {}", dataLength, payloadBytes.length);

                if (reservedLength > 0 && payloadBytes.length != reservedLength) {
                     logger.warn("Decompressed Zabbix payload size ({}) does not match expected uncompressed size ({}).",
                             payloadBytes.length, reservedLength);
                     // This might not be fatal, but it's worth noting.
                }
            }

            String responseString = new String(payloadBytes, StandardCharsets.UTF_8);
            logger.debug("Parsed Zabbix response. Payload (first 200 chars): '{}'",
                    responseString.substring(0, Math.min(responseString.length(), 200)));
            return responseString;

        } catch (IOException e) {
            throw new CommunicationException("Error reading Zabbix response: " + e.getMessage(), e);
        }
    }
}
