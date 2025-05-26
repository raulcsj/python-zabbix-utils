package io.zabbix4j.api.types;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Represents a Zabbix API version, providing methods for parsing, comparison, and accessing version components.
 * The version string is expected in the format "X.Y.Z" (e.g., "7.2.0").
 * This class is immutable.
 *
 * @author Your Name
 */
public final class ApiVersion implements Comparable<ApiVersion> {

    private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)");

    private final String raw;
    private final int majorVersionPart;
    private final int minorVersionPart;
    private final int patchVersionPart;

    /**
     * Constructs an {@code ApiVersion} object by parsing a version string.
     *
     * @param rawVersion The version string in "X.Y.Z" format (e.g., "7.2.0", "6.0.15").
     * @throws IllegalArgumentException if the {@code rawVersion} string format is invalid or if any version part is negative.
     */
    public ApiVersion(String rawVersion) {
        if (rawVersion == null || rawVersion.trim().isEmpty()) {
            throw new IllegalArgumentException("Version string cannot be null or empty.");
        }

        Matcher matcher = VERSION_PATTERN.matcher(rawVersion.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                "Invalid API version format. Expected format is X.Y.Z, where X, Y, and Z are non-negative integers. Got: "
                    + rawVersion);
        }

        try {
            this.majorVersionPart = Integer.parseInt(matcher.group(1));
            this.minorVersionPart = Integer.parseInt(matcher.group(2));
            this.patchVersionPart = Integer.parseInt(matcher.group(3));

            if (this.majorVersionPart < 0 || this.minorVersionPart < 0 || this.patchVersionPart < 0) {
                throw new IllegalArgumentException("Version parts cannot be negative. Got: " + rawVersion);
            }

        } catch (NumberFormatException e) {
            // This should ideally not happen due to the regex, but as a safeguard:
            throw new IllegalArgumentException(
                "Invalid number format in version string. Expected integers. Got: " + rawVersion, e);
        }
        this.raw = rawVersion.trim();
    }

    /**
     * Returns the original raw version string.
     *
     * @return The raw version string (e.g., "7.2.0").
     */
    public String getRaw() {
        return raw;
    }

    /**
     * Returns the major and minor version parts as a float.
     * For example, for "7.2.0", this method returns 7.2f.
     *
     * @return The major version in X.Y format.
     */
    public float getMajor() {
        return Float.parseFloat(majorVersionPart + "." + minorVersionPart);
    }

    /**
     * Returns the patch version part (Z).
     * For example, for "7.2.0", this method returns 0.
     *
     * @return The patch version number.
     */
    public int getPatch() {
        return patchVersionPart;
    }

    /**
     * Checks if this API version is a Long-Term Support (LTS) release.
     * An LTS release is identified by having a minor version part of 0 (e.g., "7.0.x").
     *
     * @return {@code true} if the minor version part is 0, {@code false} otherwise.
     */
    public boolean isLts() {
        return minorVersionPart == 0;
    }

    /**
     * Returns the raw version string.
     *
     * @return The raw version string.
     */
    @Override
    public String toString() {
        return raw;
    }

    /**
     * Compares this {@code ApiVersion} with the specified object for equality.
     * The comparison is based on the full raw version string.
     *
     * @param o The object to compare with.
     * @return {@code true} if the specified object is an {@code ApiVersion} and its raw version string is equal to this object's raw version string, {@code false} otherwise.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ApiVersion that = (ApiVersion) o;
        return Objects.equals(raw, that.raw);
    }

    /**
     * Returns the hash code for this {@code ApiVersion}.
     * The hash code is based on the raw version string.
     *
     * @return The hash code value for this object.
     */
    @Override
    public int hashCode() {
        return Objects.hash(raw);
    }

    /**
     * Compares this {@code ApiVersion} with another {@code ApiVersion} object.
     * The comparison is lexicographical: first by major version, then by minor version, and finally by patch version.
     *
     * @param other The {@code ApiVersion} to be compared.
     * @return A negative integer, zero, or a positive integer as this version is less than, equal to, or greater than the specified version.
     */
    @Override
    public int compareTo(ApiVersion other) {
        if (other == null) {
            // Consistent with Comparable contract, throw NullPointerException if other is null.
            throw new NullPointerException("Cannot compare ApiVersion to null.");
        }
        int majorDiff = Integer.compare(this.majorVersionPart, other.majorVersionPart);
        if (majorDiff != 0) {
            return majorDiff;
        }
        int minorDiff = Integer.compare(this.minorVersionPart, other.minorVersionPart);
        if (minorDiff != 0) {
            return minorDiff;
        }
        return Integer.compare(this.patchVersionPart, other.patchVersionPart);
    }

    /**
     * Checks if this API version is equal to the version specified by the string.
     *
     * @param versionString The version string to compare against (e.g., "7.2.0").
     * @return {@code true} if this version is equal to the parsed {@code versionString}, {@code false} otherwise.
     * @throws IllegalArgumentException if {@code versionString} is invalid.
     */
    public boolean isEqualTo(String versionString) {
        return this.compareTo(new ApiVersion(versionString)) == 0;
    }

    /**
     * Checks if this API version is greater than the version specified by the string.
     *
     * @param versionString The version string to compare against (e.g., "7.2.0").
     * @return {@code true} if this version is greater than the parsed {@code versionString}, {@code false} otherwise.
     * @throws IllegalArgumentException if {@code versionString} is invalid.
     */
    public boolean isGreaterThan(String versionString) {
        return this.compareTo(new ApiVersion(versionString)) > 0;
    }

    /**
     * Checks if this API version is less than the version specified by the string.
     *
     * @param versionString The version string to compare against (e.g., "7.2.0").
     * @return {@code true} if this version is less than the parsed {@code versionString}, {@code false} otherwise.
     * @throws IllegalArgumentException if {@code versionString} is invalid.
     */
    public boolean isLessThan(String versionString) {
        return this.compareTo(new ApiVersion(versionString)) < 0;
    }

    /**
     * Checks if the major.minor part of this API version is equal to the specified float value.
     * For example, if this version is "7.0.15", {@code isEqualTo(7.0f)} would return {@code true}.
     *
     * @param majorVersionFloat The major.minor version float to compare against (e.g., 7.0f).
     * @return {@code true} if the major.minor part of this version is equal to {@code majorVersionFloat}, {@code false} otherwise.
     */
    public boolean isEqualTo(float majorVersionFloat) {
        return Float.compare(this.getMajor(), majorVersionFloat) == 0;
    }

    /**
     * Checks if the major.minor part of this API version is greater than the specified float value.
     *
     * @param majorVersionFloat The major.minor version float to compare against (e.g., 6.4f).
     * @return {@code true} if the major.minor part of this version is greater than {@code majorVersionFloat}, {@code false} otherwise.
     */
    public boolean isGreaterThan(float majorVersionFloat) {
        return this.getMajor() > majorVersionFloat;
    }

    /**
     * Checks if the major.minor part of this API version is less than the specified float value.
     *
     * @param majorVersionFloat The major.minor version float to compare against (e.g., 7.2f).
     * @return {@code true} if the major.minor part of this version is less than {@code majorVersionFloat}, {@code false} otherwise.
     */
    public boolean isLessThan(float majorVersionFloat) {
        return this.getMajor() < majorVersionFloat;
    }
}
