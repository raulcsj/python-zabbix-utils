package io.zabbix4j.api.types;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ApiVersion}.
 *
 * @author CSJ
 */
class ApiVersionTest {

    @Test
    void testValidVersionParsing_full() {
        ApiVersion version = new ApiVersion("6.0.12");
        assertEquals(6, version.getMajorVersion());
        assertEquals(0, version.getMinorVersion());
        assertEquals(12, version.getPatchVersion());
        assertEquals(6.0, version.getMajor());
        assertEquals(0, version.getMinor());
        assertEquals(12, version.getPatch());
        assertEquals("6.0.12", version.toString());
    }

    @Test
    void testValidVersionParsing_majorMinorOnly() {
        ApiVersion version = new ApiVersion("7.2");
        assertEquals(7, version.getMajorVersion());
        assertEquals(2, version.getMinorVersion());
        assertEquals(0, version.getPatchVersion()); // Patch defaults to 0
        assertEquals(7.2, version.getMajor());
        assertEquals(2, version.getMinor());
        assertEquals(0, version.getPatch());
        assertEquals("7.2", version.toString());
    }

    @Test
    void testValidVersionParsing_withZeroPatch() {
        ApiVersion version = new ApiVersion("5.4.0");
        assertEquals(5, version.getMajorVersion());
        assertEquals(4, version.getMinorVersion());
        assertEquals(0, version.getPatchVersion());
        assertEquals(5.4, version.getMajor());
        assertEquals(4, version.getMinor());
        assertEquals(0, version.getPatch());
        assertEquals("5.4.0", version.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"6.0.12", "7.0", "5.4.0", "  6.2.1  "})
    void testToString_returnsRawString(String rawVersion) {
        ApiVersion version = new ApiVersion(rawVersion);
        assertEquals(rawVersion.trim(), version.toString());
    }

    @Test
    void testIsLts() {
        assertTrue(new ApiVersion("6.0.12").isLts());
        assertTrue(new ApiVersion("7.0").isLts());
        assertFalse(new ApiVersion("6.2.5").isLts());
        assertFalse(new ApiVersion("5.4.0").isLts());
    }

    @Test
    void testConstructor_nullInput() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> new ApiVersion(null));
        assertEquals("API version string cannot be null or empty.", exception.getMessage());
    }

    @Test
    void testConstructor_emptyInput() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> new ApiVersion(""));
        assertEquals("API version string cannot be null or empty.", exception.getMessage());
    }

    @Test
    void testConstructor_blankInput() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> new ApiVersion("   "));
        assertEquals("API version string cannot be null or empty.", exception.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"6", "6.0.", "6.a.1", "a.b.c", "6.0.12extra", "6.0.12.3"})
    void testConstructor_invalidFormat(String invalidVersion) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> new ApiVersion(invalidVersion));
        assertTrue(exception.getMessage().startsWith("Unable to parse version of Zabbix API: '" + invalidVersion + "'"));
    }

    @Test
    void testEqualsAndHashCode() {
        ApiVersion v1 = new ApiVersion("6.0.12");
        ApiVersion v2 = new ApiVersion("6.0.12");
        ApiVersion v3 = new ApiVersion("6.0.13");
        ApiVersion v4 = new ApiVersion("6.2.0");
        ApiVersion v5 = new ApiVersion("7.0.0");
        ApiVersion v6 = new ApiVersion("7.0"); // Should be equal to 7.0.0

        // Reflexivity
        assertEquals(v1, v1);

        // Symmetry
        assertEquals(v1, v2);
        assertEquals(v2, v1);

        // Transitivity (implicit via other checks)

        // Consistency (implicit)

        // Non-nullity
        assertNotEquals(null, v1);

        // Different objects
        assertEquals(v1, v2);
        assertNotEquals(v1, v3);
        assertNotEquals(v1, v4);
        assertNotEquals(v1, v5);

        // HashCode
        assertEquals(v1.hashCode(), v2.hashCode());
        assertNotEquals(v1.hashCode(), v3.hashCode());

        // Test with version that defaults patch to 0
        ApiVersion v7_0_0 = new ApiVersion("7.0.0");
        assertEquals(v6, v7_0_0);
        assertEquals(v6.hashCode(), v7_0_0.hashCode());
    }

    @Test
    void testCompareTo() {
        ApiVersion v6_0_12 = new ApiVersion("6.0.12");
        ApiVersion v6_0_13 = new ApiVersion("6.0.13");
        ApiVersion v6_2_0 = new ApiVersion("6.2.0");
        ApiVersion v7_0_0 = new ApiVersion("7.0.0");
        ApiVersion v7_0_0_alt = new ApiVersion("7.0"); // Equivalent to 7.0.0

        assertTrue(v6_0_12.compareTo(v6_0_13) < 0);
        assertTrue(v6_0_13.compareTo(v6_0_12) > 0);
        assertTrue(v6_0_12.compareTo(v6_0_12) == 0);

        assertTrue(v6_0_13.compareTo(v6_2_0) < 0);
        assertTrue(v6_2_0.compareTo(v6_0_13) > 0);

        assertTrue(v6_2_0.compareTo(v7_0_0) < 0);
        assertTrue(v7_0_0.compareTo(v6_2_0) > 0);

        assertEquals(0, v7_0_0.compareTo(v7_0_0_alt));
        assertEquals(0, v7_0_0_alt.compareTo(v7_0_0));
    }

    @ParameterizedTest
    @CsvSource({
            "6.0.12, 6.0, true",
            "6.0.12, 6.1, false",
            "7.2.0, 7.2, true",
            "7.2, 7.2, true",
            "5.4.3, 5.4, true"
    })
    void testIsEqualTo_double(String versionStr, double targetDouble, boolean expected) {
        ApiVersion version = new ApiVersion(versionStr);
        assertEquals(expected, version.isEqualTo(targetDouble));
    }

    @ParameterizedTest
    @CsvSource({
            "6.0.12, 6.0.12, true",
            "6.0.12, 6.0.13, false",
            "7.0, 7.0.0, true", // "7.0" is parsed as 7.0.0
            "7.0.0, 7.0, true"
    })
    void testIsEqualTo_string(String versionStr, String targetString, boolean expected) {
        ApiVersion version = new ApiVersion(versionStr);
        assertEquals(expected, version.isEqualTo(targetString));
    }

     @Test
    void testIsEqualTo_string_invalidFormat() {
        ApiVersion version = new ApiVersion("6.0.0");
        assertThrows(IllegalArgumentException.class, () -> version.isEqualTo("invalid"));
    }

    @ParameterizedTest
    @CsvSource({
            "6.0.12, 6.0, false", // 6.0 is not > 6.0
            "6.1.0, 6.0, true",
            "7.0.0, 6.4, true",
            "5.4.0, 6.0, false"
    })
    void testIsGreaterThan_double(String versionStr, double targetDouble, boolean expected) {
        ApiVersion version = new ApiVersion(versionStr);
        assertEquals(expected, version.isGreaterThan(targetDouble));
    }

    @ParameterizedTest
    @CsvSource({
            "6.0.13, 6.0.12, true",
            "6.0.12, 6.0.12, false",
            "7.0.1, 7.0.0, true",
            "7.0, 6.4.15, true"
    })
    void testIsGreaterThan_string(String versionStr, String targetString, boolean expected) {
        ApiVersion version = new ApiVersion(versionStr);
        assertEquals(expected, version.isGreaterThan(targetString));
    }

    @Test
    void testIsGreaterThan_string_invalidFormat() {
        ApiVersion version = new ApiVersion("6.0.0");
        assertThrows(IllegalArgumentException.class, () -> version.isGreaterThan("invalid"));
    }

    @ParameterizedTest
    @CsvSource({
            "6.0.12, 6.1, true",
            "6.0.0, 6.0, false", // 6.0 is not < 6.0
            "5.4.0, 6.0, true",
            "7.0.0, 7.2, true"
    })
    void testIsLessThan_double(String versionStr, double targetDouble, boolean expected) {
        ApiVersion version = new ApiVersion(versionStr);
        assertEquals(expected, version.isLessThan(targetDouble));
    }

    @ParameterizedTest
    @CsvSource({
            "6.0.12, 6.0.13, true",
            "6.0.12, 6.0.12, false",
            "7.0.0, 7.0.1, true",
            "6.4.15, 7.0, true"
    })
    void testIsLessThan_string(String versionStr, String targetString, boolean expected) {
        ApiVersion version = new ApiVersion(versionStr);
        assertEquals(expected, version.isLessThan(targetString));
    }

   @Test
    void testIsLessThan_string_invalidFormat() {
        ApiVersion version = new ApiVersion("6.0.0");
        assertThrows(IllegalArgumentException.class, () -> version.isLessThan("invalid"));
    }

    @Test
    void testGetters() {
        ApiVersion version = new ApiVersion("6.0.12");
        assertEquals(6, version.getMajorVersion());
        assertEquals(0, version.getMinorVersion());
        assertEquals(12, version.getPatchVersion());
        assertEquals(6.0, version.getMajor(), 0.001); // double comparison
        assertEquals(0, version.getMinor());
        assertEquals(12, version.getPatch());
        assertNotNull(version.toString());
    }
}
