package io.zabbix4j.api.dto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link APIVersion}.
 * @author ShortRoundDev
 */
class APIVersionTest {

    @Test
    void testConstructor_validVersions() {
        APIVersion v1 = new APIVersion("5.4.0");
        assertEquals("5.4.0", v1.getRawVersion());
        assertEquals(5, v1.getMajorVersion());
        assertEquals(4, v1.getMinorVersion());
        assertEquals(0, v1.getPatchVersion());

        APIVersion v2 = new APIVersion("7.0.15rc1"); // Patch can have non-numeric parts, parsed up to non-digit
        assertEquals("7.0.15rc1", v2.getRawVersion());
        assertEquals(7, v2.getMajorVersion());
        assertEquals(0, v2.getMinorVersion());
        assertEquals(15, v2.getPatchVersion()); // Regex stops at 'r'

        APIVersion v3 = new APIVersion("6.0"); // Missing patch
        assertEquals("6.0", v3.getRawVersion());
        assertEquals(6, v3.getMajorVersion());
        assertEquals(0, v3.getMinorVersion());
        assertEquals(0, v3.getPatchVersion()); // Defaults to 0
    }

    @ParameterizedTest
    @ValueSource(strings = {"badformat", "5", "5.", "5.4.", "a.b.c", "", " ", "6.0alpha"})
    void testConstructor_invalidVersions(String invalidVersion) {
        assertThrows(IllegalArgumentException.class, () -> new APIVersion(invalidVersion));
    }

    @Test
    void testConstructor_nullVersion() {
        assertThrows(IllegalArgumentException.class, () -> new APIVersion(null));
    }

    @Test
    void testIsLts() {
        assertTrue(new APIVersion("6.0.12").isLts());
        assertTrue(new APIVersion("7.0.0").isLts());
        assertFalse(new APIVersion("5.4.0").isLts());
        assertFalse(new APIVersion("7.2.1").isLts());
    }

    @ParameterizedTest
    @CsvSource({
            "6.0.5, 6.0.5, true",
            "6.0.5, 6.0.4, false",
            "6.0.5, 6.0, true", // String comparison, patch defaults to 0
            "7.0, 7.0.0, true"
    })
    void testIsEqualTo_string(String v1Str, String v2Str, boolean expected) {
        APIVersion v1 = new APIVersion(v1Str);
        assertEquals(expected, v1.isEqualTo(v2Str));
    }

    @Test
    void testIsEqualTo_string_invalidFormat() {
        APIVersion v1 = new APIVersion("6.0.5");
        assertFalse(v1.isEqualTo("invalid")); // Should not throw, just return false
    }

    @ParameterizedTest
    @CsvSource({
            "6.0.5, 6.0f, true",
            "6.0.5, 6.2f, false",
            "7.0.0, 7.0f, true",
            "5.4.10, 5.4f, true"
    })
    void testIsEqualTo_float(String vStr, float vFloat, boolean expected) {
        APIVersion v = new APIVersion(vStr);
        assertEquals(expected, v.isEqualTo(vFloat));
    }

    @ParameterizedTest
    @CsvSource({
            "6.0.5, 6.0.4, true",
            "6.0.5, 6.0.5, false",
            "6.0.5, 6.0.6, false",
            "7.0.0, 6.4.0, true",
            "6.2.0, 6.0.15, true"
    })
    void testIsGreaterThan_string(String v1Str, String v2Str, boolean expected) {
        APIVersion v1 = new APIVersion(v1Str);
        assertEquals(expected, v1.isGreaterThan(v2Str));
    }

    @Test
    void testIsGreaterThan_string_invalidFormat() {
        APIVersion v1 = new APIVersion("6.0.5");
        assertFalse(v1.isGreaterThan("invalid"));
    }

    @ParameterizedTest
    @CsvSource({
            "6.0.5, 5.4f, true",
            "6.0.5, 6.0f, false", // Major.minor are equal
            "6.0.5, 6.2f, false",
            "7.0.0, 6.4f, true"
    })
    void testIsGreaterThan_float(String vStr, float vFloat, boolean expected) {
        APIVersion v = new APIVersion(vStr);
        assertEquals(expected, v.isGreaterThan(vFloat));
    }


    @ParameterizedTest
    @CsvSource({
            "6.0.4, 6.0.5, true",
            "6.0.5, 6.0.5, false",
            "6.0.6, 6.0.5, false",
            "6.4.0, 7.0.0, true",
            "6.0.15, 6.2.0, true"
    })
    void testIsLessThan_string(String v1Str, String v2Str, boolean expected) {
        APIVersion v1 = new APIVersion(v1Str);
        assertEquals(expected, v1.isLessThan(v2Str));
    }

     @Test
    void testIsLessThan_string_invalidFormat() {
        APIVersion v1 = new APIVersion("6.0.5");
        assertFalse(v1.isLessThan("invalid"));
    }

    @ParameterizedTest
    @CsvSource({
            "5.4.0, 6.0f, true",
            "6.0.0, 6.0f, false", // Major.minor are equal
            "6.2.0, 6.0f, false",
            "6.4.0, 7.0f, true"
    })
    void testIsLessThan_float(String vStr, float vFloat, boolean expected) {
        APIVersion v = new APIVersion(vStr);
        assertEquals(expected, v.isLessThan(vFloat));
    }


    @Test
    void testEqualsAndHashCode() {
        APIVersion v1 = new APIVersion("5.4.0");
        APIVersion v2 = new APIVersion("5.4.0");
        APIVersion v3 = new APIVersion("5.4.1");
        APIVersion v4 = new APIVersion("5.2.0");
        APIVersion v5 = new APIVersion("6.4.0");

        assertEquals(v1, v2);
        assertEquals(v1.hashCode(), v2.hashCode());

        assertNotEquals(v1, v3);
        assertNotEquals(v1.hashCode(), v3.hashCode()); // Hashcodes can collide, but unlikely for these simple changes
        assertNotEquals(v1, v4);
        assertNotEquals(v1, v5);
        assertNotEquals(v1, null);
        assertNotEquals(v1, "5.4.0"); // Different type
    }

    @Test
    void testToString() {
        String versionStr = "7.0.1alpha";
        APIVersion v = new APIVersion(versionStr);
        assertEquals(versionStr, v.toString());
    }

    @Test
    void testCompareTo() {
        APIVersion v_5_4_0 = new APIVersion("5.4.0");
        APIVersion v_5_4_0_dup = new APIVersion("5.4.0");
        APIVersion v_5_4_1 = new APIVersion("5.4.1");
        APIVersion v_5_2_0 = new APIVersion("5.2.0");
        APIVersion v_6_0_0 = new APIVersion("6.0.0");

        assertEquals(0, v_5_4_0.compareTo(v_5_4_0_dup));
        assertTrue(v_5_4_0.compareTo(v_5_4_1) < 0);
        assertTrue(v_5_4_1.compareTo(v_5_4_0) > 0);
        assertTrue(v_5_4_0.compareTo(v_5_2_0) > 0);
        assertTrue(v_5_2_0.compareTo(v_5_4_0) < 0);
        assertTrue(v_5_4_0.compareTo(v_6_0_0) < 0);
        assertTrue(v_6_0_0.compareTo(v_5_4_0) > 0);
    }
}
