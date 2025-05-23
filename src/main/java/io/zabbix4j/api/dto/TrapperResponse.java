package io.zabbix4j.api.dto;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Represents the response from a Zabbix Sender after sending trapper items.
 * This class can parse the "info" string returned by the Zabbix server/proxy.
 *
 * @author ShortRoundDev
 */
public final class TrapperResponse {

    // Example info string: "processed: 1; failed: 0; total: 1; seconds spent: 0.000035"
    // Another example (older Zabbix): "Processed 1 Failed 0 Total 1 Seconds spent 0.000023"
    private static final Pattern INFO_PATTERN = Pattern.compile(
        "processed: (\\d+); failed: (\\d+); total: (\\d+); seconds spent: ([\\d.]+)",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern OLD_INFO_PATTERN = Pattern.compile(
        "Processed (\\d+) Failed (\\d+) Total (\\d+) Seconds spent ([\\d.]+)",
        Pattern.CASE_INSENSITIVE
    );


    private final int processed;
    private final int failed;
    private final int total;
    private final double timeSpentSeconds;
    private final Integer chunkIndex; // Optional, to identify which part of a larger payload this response refers to

    /**
     * Constructs a TrapperResponse.
     *
     * @param processed        Number of items successfully processed.
     * @param failed           Number of items that failed.
     * @param total            Total items in the request.
     * @param timeSpentSeconds Time spent processing the request in seconds.
     * @param chunkIndex       Optional index of the data chunk this response corresponds to.
     */
    public TrapperResponse(int processed, int failed, int total, double timeSpentSeconds, Integer chunkIndex) {
        this.processed = processed;
        this.failed = failed;
        this.total = total;
        this.timeSpentSeconds = timeSpentSeconds;
        this.chunkIndex = chunkIndex;
    }

    /**
     * Parses the "info" string from a Zabbix Sender response.
     *
     * @param infoString The info string (e.g., "processed: 1; failed: 0; total: 1; seconds spent: 0.000035").
     * @param chunkIndex Optional index for the data chunk this response corresponds to.
     * @return A new {@link TrapperResponse} instance.
     * @throws IllegalArgumentException if the infoString is null or cannot be parsed.
     */
    public static TrapperResponse parseInfoString(String infoString, Integer chunkIndex) {
        if (infoString == null || infoString.trim().isEmpty()) {
            throw new IllegalArgumentException("Info string cannot be null or empty.");
        }

        Matcher matcher = INFO_PATTERN.matcher(infoString);
        if (matcher.find()) {
            return new TrapperResponse(
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3)),
                Double.parseDouble(matcher.group(4)),
                chunkIndex
            );
        }

        Matcher oldMatcher = OLD_INFO_PATTERN.matcher(infoString);
        if (oldMatcher.find()) {
            return new TrapperResponse(
                Integer.parseInt(oldMatcher.group(1)),
                Integer.parseInt(oldMatcher.group(2)),
                Integer.parseInt(oldMatcher.group(3)),
                Double.parseDouble(oldMatcher.group(4)),
                chunkIndex
            );
        }

        throw new IllegalArgumentException("Could not parse Zabbix Sender info string: '" + infoString + "'");
    }

    /**
     * Parses the "info" string from a Zabbix Sender response without a chunk index.
     *
     * @param infoString The info string (e.g., "processed: 1; failed: 0; total: 1; seconds spent: 0.000035").
     * @return A new {@link TrapperResponse} instance.
     * @throws IllegalArgumentException if the infoString is null or cannot be parsed.
     */
    public static TrapperResponse parseInfoString(String infoString) {
        return parseInfoString(infoString, null);
    }

    /**
     * @return The number of items successfully processed.
     */
    public int getProcessed() {
        return processed;
    }

    /**
     * @return The number of items that failed.
     */
    public int getFailed() {
        return failed;
    }

    /**
     * @return The total number of items in the request.
     */
    public int getTotal() {
        return total;
    }

    /**
     * @return The time spent processing the request, in seconds.
     */
    public double getTimeSpentSeconds() {
        return timeSpentSeconds;
    }

    /**
     * @return The optional chunk index this response refers to. Can be null.
     */
    public Integer getChunkIndex() {
        return chunkIndex;
    }

    /**
     * Aggregates this response with another {@link TrapperResponse}.
     * This is useful when data is sent in multiple chunks.
     * The chunkIndex of the current object is preserved if not null, otherwise the otherResponse's chunkIndex is used.
     *
     * @param otherResponse The other {@link TrapperResponse} to add.
     * @return A new {@link TrapperResponse} instance representing the sum of both responses.
     */
    public TrapperResponse add(TrapperResponse otherResponse) {
        if (otherResponse == null) {
            return this; // Or throw IllegalArgumentException, depending on desired behavior
        }
        return new TrapperResponse(
            this.processed + otherResponse.processed,
            this.failed + otherResponse.failed,
            this.total + otherResponse.total,
            this.timeSpentSeconds + otherResponse.timeSpentSeconds,
            this.chunkIndex != null ? this.chunkIndex : otherResponse.chunkIndex // Simplistic way to handle chunkIndex
        );
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
               Objects.equals(chunkIndex, that.chunkIndex);
    }

    @Override
    public int hashCode() {
        return Objects.hash(processed, failed, total, timeSpentSeconds, chunkIndex);
    }

    @Override
    public String toString() {
        return "TrapperResponse{" +
               "processed=" + processed +
               ", failed=" + failed +
               ", total=" + total +
               ", timeSpentSeconds=" + timeSpentSeconds +
               (chunkIndex != null ? ", chunkIndex=" + chunkIndex : "") +
               '}';
    }
}
