package io.zabbix4j.api.types;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Represents the response from a Zabbix Trapper operation.
 * <p>
 * This class aggregates statistics from Zabbix server responses, such as the number
 * of processed, failed, and total values, as well as the time spent by the server
 * processing the data. It can handle responses for multiple chunks of data.
 * </p>
 * This class is mutable.
 *
 * @author CSJ
 */
public class TrapperResponse {

    private int processed;
    private int failed;
    private int total;
    private double timeSpentSeconds;
    private int chunkNumber;
    private Map<Node, List<TrapperResponse>> details;

    private static final Pattern INFO_PATTERN = Pattern.compile(
            "processed: (\\d+); failed: (\\d+); total: (\\d+); seconds spent: ([\\d.]+)"
    );

    /**
     * Static inner class or record to hold parsed information from the Zabbix 'info' string.
     */
    public static class ParsedInfo {
        public final int processed;
        public final int failed;
        public final int total;
        public final double timeSpentSeconds;

        /**
         * Constructs a {@code ParsedInfo} instance.
         *
         * @param processed        Number of processed values.
         * @param failed           Number of failed values.
         * @param total            Total number of values.
         * @param timeSpentSeconds Time spent processing in seconds.
         */
        public ParsedInfo(int processed, int failed, int total, double timeSpentSeconds) {
            this.processed = processed;
            this.failed = failed;
            this.total = total;
            this.timeSpentSeconds = timeSpentSeconds;
        }

        @Override
        public String toString() {
            return "ParsedInfo{" +
                    "processed=" + processed +
                    ", failed=" + failed +
                    ", total=" + total +
                    ", timeSpentSeconds=" + timeSpentSeconds +
                    '}';
        }
    }

    /**
     * Default constructor. Initializes numeric fields to 0, chunkNumber to 1,
     * and details to an empty map.
     */
    public TrapperResponse() {
        this(1); // Default chunk number to 1
    }

    /**
     * Constructs a {@code TrapperResponse} with a specific chunk number.
     * Initializes numeric fields to 0 and details to an empty map.
     *
     * @param chunkNumber The chunk number this response pertains to.
     */
    public TrapperResponse(int chunkNumber) {
        this.processed = 0;
        this.failed = 0;
        this.total = 0;
        this.timeSpentSeconds = 0.0;
        this.chunkNumber = chunkNumber;
        this.details = new HashMap<>(); // Initialize details map
    }

    /**
     * Parses the Zabbix 'info' string to extract operational statistics.
     * <p>
     * Example infoString: "processed: 1; failed: 0; total: 1; seconds spent: 0.000123"
     * </p>
     *
     * @param infoString The 'info' string from the Zabbix response.
     * @return A {@link ParsedInfo} object containing the extracted values.
     * @throws IllegalArgumentException if the {@code infoString} is null, empty,
     *                                  or does not match the expected format.
     */
    public static ParsedInfo parseZabbixResponseInfo(String infoString) {
        if (infoString == null || infoString.trim().isEmpty()) {
            throw new IllegalArgumentException("Info string cannot be null or empty.");
        }

        Matcher matcher = INFO_PATTERN.matcher(infoString);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Invalid info string format: " + infoString);
        }

        try {
            int processed = Integer.parseInt(matcher.group(1));
            int failed = Integer.parseInt(matcher.group(2));
            int total = Integer.parseInt(matcher.group(3));
            double timeSpent = Double.parseDouble(matcher.group(4));
            return new ParsedInfo(processed, failed, total, timeSpent);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Error parsing numeric values from info string: " + infoString, e);
        }
    }

    /**
     * Adds the statistics from a Zabbix JSON response to this {@code TrapperResponse} instance.
     *
     * @param zabbixResponseJson A map representing the JSON response from Zabbix.
     *                           Expected to contain an 'info' field with the statistics string.
     * @param chunkNumber        The chunk number this response corresponds to.
     * @return This {@code TrapperResponse} instance for chaining.
     * @throws IllegalArgumentException if {@code zabbixResponseJson} is null,
     *                                  does not contain an 'info' field, or the 'info' field is not a string.
     */
    public TrapperResponse addResponse(Map<String, Object> zabbixResponseJson, int chunkNumber) {
        if (zabbixResponseJson == null) {
            throw new IllegalArgumentException("Zabbix response JSON map cannot be null.");
        }
        if (!zabbixResponseJson.containsKey("info")) {
            throw new IllegalArgumentException("Zabbix response JSON map must contain an 'info' field.");
        }
        Object infoObject = zabbixResponseJson.get("info");
        if (!(infoObject instanceof String)) {
            throw new IllegalArgumentException("'info' field in Zabbix response must be a String.");
        }

        String infoString = (String) infoObject;
        ParsedInfo parsed = parseZabbixResponseInfo(infoString);

        this.processed += parsed.processed;
        this.failed += parsed.failed;
        this.total += parsed.total;
        this.timeSpentSeconds += parsed.timeSpentSeconds;
        this.chunkNumber = chunkNumber; // Update with the latest chunk number processed

        return this;
    }

    /**
     * Gets the total number of processed values.
     * @return The number of processed values.
     */
    public int getProcessed() {
        return processed;
    }

    /**
     * Gets the total number of failed values.
     * @return The number of failed values.
     */
    public int getFailed() {
        return failed;
    }

    /**
     * Gets the total number of values (processed + failed).
     * @return The total number of values.
     */
    public int getTotal() {
        return total;
    }

    /**
     * Gets the total time spent by the Zabbix server processing the values, in seconds.
     * @return The time spent in seconds.
     */
    public double getTimeSpentSeconds() {
        return timeSpentSeconds;
    }

    /**
     * Gets the chunk number associated with this response.
     * If multiple responses are added, this reflects the latest chunk number.
     * @return The chunk number.
     */
    public int getChunkNumber() {
        return chunkNumber;
    }

    /**
     * Gets the detailed breakdown of responses, typically per node and per chunk.
     * <p>
     * The initial {@code addResponse} method does not populate this map. It is intended
     * for more complex scenarios where responses from multiple nodes or multiple chunks
     * per node need to be tracked individually.
     * </p>
     * @return A map where keys are {@link Node} objects and values are lists of
     *         {@code TrapperResponse} objects, or an empty map if not populated.
     */
    public Map<Node, List<TrapperResponse>> getDetails() {
        return details;
    }

    /**
     * Sets the details map.
     * @param details The map of detailed responses.
     */
    public void setDetails(Map<Node, List<TrapperResponse>> details) {
        this.details = details;
    }


    /**
     * Returns a JSON-like string representation of the main fields of this {@code TrapperResponse}.
     *
     * @return A string representation of the object.
     */
    @Override
    public String toString() {
        return "TrapperResponse{" +
                "processed=" + processed +
                ", failed=" + failed +
                ", total=" + total +
                ", timeSpentSeconds=" + String.format("%.6f", timeSpentSeconds) + // Format double for consistency
                ", chunkNumber=" + chunkNumber +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TrapperResponse that = (TrapperResponse) o;
        return processed == that.processed &&
                failed == that.failed &&
                total == that.total &&
                Double.compare(that.timeSpentSeconds, timeSpentSeconds) == 0 &&
                chunkNumber == that.chunkNumber &&
                Objects.equals(details, that.details);
    }

    @Override
    public int hashCode() {
        return Objects.hash(processed, failed, total, timeSpentSeconds, chunkNumber, details);
    }
}
