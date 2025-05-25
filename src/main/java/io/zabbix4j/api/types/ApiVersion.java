package io.zabbix4j.api.types;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Represents a Zabbix API version.
 * <p>
 * This class parses a version string (e.g., "6.0.12") into its major, minor, and patch components.
 * It provides methods for accessing these components and comparing versions.
 * The class is immutable.
 * </p>
 *
 * @author CSJ
 */
public final class ApiVersion implements Comparable<ApiVersion> {

    private static final Pattern VERSION_PATTERN = Pattern.compile("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?$");

    private final String rawVersionString;
    private final int major;
    private final int minor;
    private final int patch;

    /**
     * Constructs an {@code ApiVersion} instance from a version string.
     *
     * @param apiVersionString The version string (e.g., "6.0.12", "7.0").
     * @throws IllegalArgumentException if the version string is malformed.
     */
    public ApiVersion(String apiVersionString) {
        if (apiVersionString == null || apiVersionString.trim().isEmpty()) {
            throw new IllegalArgumentException("API version string cannot be null or empty.");
        }
        this.rawVersionString = apiVersionString.trim();

        Matcher matcher = VERSION_PATTERN.matcher(this.rawVersionString);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "Unable to parse version of Zabbix API: '" + apiVersionString
                            + "'. Default 'X.Y.Z' or 'X.Y' format is expected (e.g., 7.0.0 or 7.0)."
            );
        }

        try {
            this.major = Integer.parseInt(matcher.group(1));
            this.minor = Integer.parseInt(matcher.group(2));
            // Patch is optional, defaults to 0 if not present
            this.patch = matcher.group(3) != null ? Integer.parseInt(matcher.group(3)) : 0;
        } catch (NumberFormatException e) {
            // This should ideally not happen if regex matches, but as a safeguard
            throw new IllegalArgumentException(
                    "Invalid number format in version string: '" + apiVersionString + "'.", e
            );
        }
    }

    /**
     * Gets the major version component.
     * For "6.0.12", this would be 6.
     *
     * @return The major version number.
     */
    public int getMajorVersion() {
        return major;
    }

    /**
     * Gets the minor version component.
     * For "6.0.12", this would be 0.
     * For "7.2.0", this would be 2.
     *
     * @return The minor version number.
     */
    public int getMinorVersion() {
        return minor;
    }

    /**
     * Gets the patch version component.
     * For "6.0.12", this would be 12.
     * For "7.2.0" or "7.2", this would be 0.
     *
     * @return The patch version number.
     */
    public int getPatchVersion() {
        return patch;
    }

    /**
     * Returns the major and minor version as a double.
     * For "6.0.12", this returns {@code 6.0}.
     * For "7.2.5", this returns {@code 7.2}.
     *
     * @return The major version in "major.minor" format as a double.
     */
    public double getMajor() {
        // This creates a double like 6.0, 7.2.
        // Note: potential precision issues with double for very large minor numbers,
        // but for typical versioning, this should be fine.
        return Double.parseDouble(major + "." + minor);
    }

    /**
     * Returns the minor part of the version, which was referred to as `__second` in the Python equivalent.
     * For "6.0.12", this returns {@code 0}.
     * For "7.2.0", this returns {@code 2}.
     *
     * @return The minor version number.
     */
    public int getMinor() {
        return minor;
    }

    /**
     * Returns the patch part of the version, which was referred to as `__third` in the Python equivalent.
     * For "6.0.12", this returns {@code 12}.
     * For "7.0.0" or "7.0", this returns {@code 0}.
     *
     * @return The patch version number.
     */
    public int getPatch() {
        return patch;
    }


    /**
     * Checks if this version is an LTS (Long Term Support) release.
     * LTS versions are typically those where the minor version is 0 (e.g., "6.0.x", "7.0.x").
     *
     * @return {@code true} if this is an LTS version, {@code false} otherwise.
     */
    public boolean isLts() {
        return this.minor == 0;
    }

    /**
     * Returns the original raw version string.
     *
     * @return The raw version string (e.g., "6.0.12").
     */
    @Override
    public String toString() {
        return rawVersionString;
    }

    /**
     * Compares this {@code ApiVersion} with the specified object for equality.
     *
     * @param o The object to be compared for equality with this {@code ApiVersion}.
     * @return {@code true} if the specified object is equal to this {@code ApiVersion}.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ApiVersion that = (ApiVersion) o;
        return major == that.major &&
                minor == that.minor &&
                patch == that.patch;
    }

    /**
     * Returns the hash code value for this {@code ApiVersion}.
     *
     * @return The hash code value for this {@code ApiVersion}.
     */
    @Override
    public int hashCode() {
        return Objects.hash(major, minor, patch);
    }

    /**
     * Compares this {@code ApiVersion} with another {@code ApiVersion} object.
     * Comparison is done by major version, then minor version, then patch version.
     *
     * @param other The {@code ApiVersion} to be compared.
     * @return A negative integer, zero, or a positive integer as this version
     * is less than, equal to, or greater than the specified version.
     */
    @Override
    public int compareTo(ApiVersion other) {
        if (this.major != other.major) {
            return Integer.compare(this.major, other.major);
        }
        if (this.minor != other.minor) {
            return Integer.compare(this.minor, other.minor);
        }
        return Integer.compare(this.patch, other.patch);
    }

    // Comparison helper methods

    /**
     * Checks if this API version is equal to the given major version representation.
     * For example, if this version is "6.0.12", {@code isEqualTo(6.0)} will be true.
     * If this version is "6.1.0", {@code isEqualTo(6.0)} will be false.
     *
     * @param majorVersionDouble A double representing the major version (e.g., 6.0, 7.2).
     * @return {@code true} if the major and minor components match the double, {@code false} otherwise.
     */
    public boolean isEqualTo(double majorVersionDouble) {
        // Be cautious with double comparisons.
        // This reconstructs the double from our parsed major/minor.
        return this.getMajor() == majorVersionDouble;
    }

    /**
     * Checks if this API version is greater than the given major version representation.
     * For example, if this version is "6.1.0", {@code isGreaterThan(6.0)} will be true.
     *
     * @param majorVersionDouble A double representing the major version (e.g., 6.0, 7.2).
     * @return {@code true} if this version is greater than the specified major.minor version.
     */
    public boolean isGreaterThan(double majorVersionDouble) {
        return this.getMajor() > majorVersionDouble;
    }

    /**
     * Checks if this API version is less than the given major version representation.
     * For example, if this version is "5.4.0", {@code isLessThan(6.0)} will be true.
     *
     * @param majorVersionDouble A double representing the major version (e.g., 6.0, 7.2).
     * @return {@code true} if this version is less than the specified major.minor version.
     */
    public boolean isLessThan(double majorVersionDouble) {
        return this.getMajor() < majorVersionDouble;
    }

    /**
     * Checks if this API version is equal to the version represented by the full version string.
     * Parses the string and compares all components (major, minor, patch).
     *
     * @param fullVersionString The full version string (e.g., "6.0.12").
     * @return {@code true} if this version is equal to the one parsed from the string.
     * @throws IllegalArgumentException if the provided string is malformed.
     */
    public boolean isEqualTo(String fullVersionString) {
        return this.equals(new ApiVersion(fullVersionString));
    }

    /**
     * Checks if this API version is greater than the version represented by the full version string.
     * Parses the string and compares.
     *
     * @param fullVersionString The full version string (e.g., "6.0.12").
     * @return {@code true} if this version is greater than the one parsed from the string.
     * @throws IllegalArgumentException if the provided string is malformed.
     */
    public boolean isGreaterThan(String fullVersionString) {
        return this.compareTo(new ApiVersion(fullVersionString)) > 0;
    }

    /**
     * Checks if this API version is less than the version represented by the full version string.
     * Parses the string and compares.
     *
     * @param fullVersionString The full version string (e.g., "6.0.12").
     * @return {@code true} if this version is less than the one parsed from the string.
     * @throws IllegalArgumentException if the provided string is malformed.
     */
    public boolean isLessThan(String fullVersionString) {
        return this.compareTo(new ApiVersion(fullVersionString)) < 0;
    }
}
