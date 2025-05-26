package io.zabbix4j.api.types;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Represents an aggregated response from Zabbix sender, typically after sending trapper items.
 * This class parses the "info" string from Zabbix sender's output and accumulates
 * the processed, failed, total counts, and time spent across multiple responses or chunks.
 * This class is not designed to be thread-safe for concurrent modifications via {@code parseAndAdd}
 * without external synchronization.
 *
 * @author Your Name
 */
public final class TrapperResponse {

    private int processed;
    private int failed;
    private int total;
    private double timeSpent;
    private int chunkCount; // Number of responses/chunks aggregated

    // Pattern to capture values from strings like "processed: 10; failed: 2; total: 12; seconds spent: 0.00345"
    // It's case-insensitive for keys and flexible with spacing.
    private static final Pattern PROCESSED_PATTERN = Pattern.compile("[Pp]rocessed:\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FAILED_PATTERN = Pattern.compile("[Ff]ailed:\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TOTAL_PATTERN = Pattern.compile("[Tt]otal:\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TIME_SPENT_PATTERN = Pattern.compile("[Ss]econds spent:\\s*(\\d+\\.\\d+)", Pattern.CASE_INSENSITIVE);


    /**
     * Constructs a {@code TrapperResponse} object with all statistics initialized to zero.
     * The chunk count is initialized to 0.
     */
    public TrapperResponse() {
        this.processed = 0;
        this.failed = 0;
        this.total = 0;
        this.timeSpent = 0.0;
        this.chunkCount = 0;
    }

    /**
     * Parses a Zabbix sender response information string and adds the extracted values
     * to the current aggregated statistics.
     * The chunk count is incremented by one after successfully parsing and adding the data.
     * <p>
     * Example response string: "processed: 10; failed: 2; total: 12; seconds spent: 0.00345"
     *
     * @param responseInfoString The Zabbix sender response info string.
     * @throws IllegalArgumentException if {@code responseInfoString} is null, empty,
     *                                  or if any of the essential parts (processed, failed, total, time spent)
     *                                  cannot be parsed.
     */
    public void parseAndAdd(String responseInfoString) {
        if (responseInfoString == null || responseInfoString.trim().isEmpty()) {
            throw new IllegalArgumentException("Response info string cannot be null or empty.");
        }

        int parsedProcessed = extractIntValue(PROCESSED_PATTERN, responseInfoString, "processed");
        int parsedFailed = extractIntValue(FAILED_PATTERN, responseInfoString, "failed");
        int parsedTotal = extractIntValue(TOTAL_PATTERN, responseInfoString, "total");
        double parsedTimeSpent = extractDoubleValue(TIME_SPENT_PATTERN, responseInfoString, "seconds spent");

        this.processed += parsedProcessed;
        this.failed += parsedFailed;
        this.total += parsedTotal;
        this.timeSpent += parsedTimeSpent;
        this.chunkCount++;
    }

    private int extractIntValue(Pattern pattern, String source, String fieldName) {
        Matcher matcher = pattern.matcher(source);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid number format for '" + fieldName + "' in response: " + source, e);
            }
        }
        throw new IllegalArgumentException("Could not find '" + fieldName + "' in response: " + source);
    }

    private double extractDoubleValue(Pattern pattern, String source, String fieldName) {
        Matcher matcher = pattern.matcher(source);
        if (matcher.find()) {
            try {
                return Double.parseDouble(matcher.group(1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid number format for '" + fieldName + "' in response: " + source, e);
            }
        }
        throw new IllegalArgumentException("Could not find '" + fieldName + "' in response: " + source);
    }

    /**
     * Returns the total number of items processed successfully across all aggregated responses.
     *
     * @return The total count of processed items.
     */
    public int getProcessed() {
        return processed;
    }

    /**
     * Returns the total number of items that failed to process across all aggregated responses.
     *
     * @return The total count of failed items.
     */
    public int getFailed() {
        return failed;
    }

    /**
     * Returns the total number of items that were attempted across all aggregated responses.
     *
     * @return The total count of items.
     */
    public int getTotal() {
        return total;
    }

    /**
     * Returns the total time spent in seconds for processing across all aggregated responses.
     *
     * @return The total time spent in seconds.
     */
    public double getTimeSpent() {
        return timeSpent;
    }

    /**
     * Returns the number of response chunks that have been parsed and aggregated by this instance.
     *
     * @return The count of aggregated chunks/responses.
     */
    public int getChunkCount() {
        return chunkCount;
    }

    /**
     * Returns a string representation of this {@code TrapperResponse} object.
     *
     * @return A string summarizing the aggregated trapper response statistics.
     */
    @Override
    public String toString() {
        return "TrapperResponse{" +
               "processed=" + processed +
               ", failed=" + failed +
               ", total=" + total +
               ", timeSpent=" + String.format("%.5f", timeSpent) + // Format for consistent output
               ", chunks=" + chunkCount +
               '}';
    }

    /**
     * Compares this {@code TrapperResponse} with the specified object for equality.
     * All fields (processed, failed, total, timeSpent, chunkCount) must match.
     *
     * @param o The object to compare with.
     * @return {@code true} if the objects are equal, {@code false} otherwise.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TrapperResponse that = (TrapperResponse) o;
        return processed == that.processed &&
               failed == that.failed &&
               total == that.total &&
               Double.compare(that.timeSpent, timeSpent) == 0 &&
               chunkCount == that.chunkCount;
    }

    /**
     * Returns the hash code for this {@code TrapperResponse}.
     * The hash code is based on all fields.
     *
     * @return The hash code value for this object.
     */
    @Override
    public int hashCode() {
        return Objects.hash(processed, failed, total, timeSpent, chunkCount);
    }
}
