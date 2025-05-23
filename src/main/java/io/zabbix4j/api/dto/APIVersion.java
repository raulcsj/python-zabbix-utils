package io.zabbix4j.api.dto;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Represents a Zabbix API version, allowing for parsing and comparison.
 * The version string is expected to be in the format "major.minor.patch" (e.g., "7.2.0").
 *
 * @author ShortRoundDev
 */
public class APIVersion implements Comparable<APIVersion> {

    private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?.*");

    private final String rawVersion;
    private final int major;
    private final int minor;
    private final int patch;

    /**
     * Constructs an APIVersion object by parsing a raw version string.
     *
     * @param rawVersion The version string (e.g., "7.2.0", "6.0.15").
     * @throws IllegalArgumentException if the rawVersion string is null or does not match the expected format.
     */
    public APIVersion(String rawVersion) {
        if (rawVersion == null || rawVersion.trim().isEmpty()) {
            throw new IllegalArgumentException("Raw version string cannot be null or empty.");
        }
        this.rawVersion = rawVersion.trim();
        Matcher matcher = VERSION_PATTERN.matcher(this.rawVersion);

        if (matcher.matches()) {
            this.major = Integer.parseInt(matcher.group(1));
            this.minor = Integer.parseInt(matcher.group(2));
            this.patch = matcher.group(3) != null ? Integer.parseInt(matcher.group(3)) : 0;
        } else {
            throw new IllegalArgumentException("Invalid API version format: '" + rawVersion +
                                               "'. Expected format is major.minor[.patch].");
        }
    }

    /**
     * @return The original raw version string.
     */
    public String getRawVersion() {
        return rawVersion;
    }

    /**
     * @return The major version component (e.g., 7 from "7.2.0").
     */
    public int getMajorVersion() {
        return major;
    }

    /**
     * @return The minor version component (e.g., 2 from "7.2.0").
     */
    public int getMinorVersion() {
        return minor;
    }

    /**
     * @return The patch version component (e.g., 0 from "7.2.0"). If not present in the raw string, defaults to 0.
     */
    public int getPatchVersion() {
        return patch;
    }

    /**
     * Checks if this version is considered an LTS (Long Term Support) release.
     * Zabbix LTS versions typically have a minor version of 0 (e.g., 6.0.x, 7.0.x).
     *
     * @return {@code true} if the minor version is 0, {@code false} otherwise.
     */
    public boolean isLts() {
        return this.minor == 0;
    }

    /**
     * Compares this APIVersion to another version string.
     *
     * @param otherVersionString The version string to compare against.
     * @return {@code true} if this version is equal to the other version, {@code false} otherwise.
     */
    public boolean isEqualTo(String otherVersionString) {
        try {
            APIVersion otherVersion = new APIVersion(otherVersionString);
            return this.compareTo(otherVersion) == 0;
        } catch (IllegalArgumentException e) {
            return false; // Or rethrow, depending on desired behavior for invalid input
        }
    }

    /**
     * Compares this APIVersion to another version represented as a float (major.minor).
     *
     * @param otherVersionFloat The version float to compare against (e.g., 7.2f).
     * @return {@code true} if this version's major.minor is equal to the other version, {@code false} otherwise.
     */
    public boolean isEqualTo(float otherVersionFloat) {
        float thisVersionFloat = Float.parseFloat(this.major + "." + this.minor);
        return Float.compare(thisVersionFloat, otherVersionFloat) == 0;
    }

    /**
     * Checks if this APIVersion is greater than another version string.
     *
     * @param otherVersionString The version string to compare against.
     * @return {@code true} if this version is greater than the other version, {@code false} otherwise.
     */
    public boolean isGreaterThan(String otherVersionString) {
        try {
            APIVersion otherVersion = new APIVersion(otherVersionString);
            return this.compareTo(otherVersion) > 0;
        } catch (IllegalArgumentException e) {
            return false; // Or rethrow
        }
    }

    /**
     * Checks if this APIVersion is greater than another version represented as a float (major.minor).
     *
     * @param otherVersionFloat The version float to compare against (e.g., 7.2f).
     * @return {@code true} if this version's major.minor is greater than the other version, {@code false} otherwise.
     */
    public boolean isGreaterThan(float otherVersionFloat) {
        float thisVersionFloat = Float.parseFloat(this.major + "." + this.minor);
        return thisVersionFloat > otherVersionFloat;
    }

    /**
     * Checks if this APIVersion is less than another version string.
     *
     * @param otherVersionString The version string to compare against.
     * @return {@code true} if this version is less than the other version, {@code false} otherwise.
     */
    public boolean isLessThan(String otherVersionString) {
        try {
            APIVersion otherVersion = new APIVersion(otherVersionString);
            return this.compareTo(otherVersion) < 0;
        } catch (IllegalArgumentException e) {
            return false; // Or rethrow
        }
    }

    /**
     * Checks if this APIVersion is less than another version represented as a float (major.minor).
     *
     * @param otherVersionFloat The version float to compare against (e.g., 7.2f).
     * @return {@code true} if this version's major.minor is less than the other version, {@code false} otherwise.
     */
    public boolean isLessThan(float otherVersionFloat) {
        float thisVersionFloat = Float.parseFloat(this.major + "." + this.minor);
        return thisVersionFloat < otherVersionFloat;
    }


    @Override
    public int compareTo(APIVersion other) {
        if (this.major != other.major) {
            return Integer.compare(this.major, other.major);
        }
        if (this.minor != other.minor) {
            return Integer.compare(this.minor, other.minor);
        }
        return Integer.compare(this.patch, other.patch);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        APIVersion that = (APIVersion) o;
        return major == that.major &&
               minor == that.minor &&
               patch == that.patch;
    }

    @Override
    public int hashCode() {
        return Objects.hash(major, minor, patch);
    }

    /**
     * @return The raw version string provided during construction.
     */
    @Override
    public String toString() {
        return rawVersion;
    }
}
