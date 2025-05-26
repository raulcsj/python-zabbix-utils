package io.zabbix4j.api.types;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@link ApiVersion} class.
 *
 * @author CSJ
 */
class ApiVersionTest {

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Test constructor with valid version strings")
        void testValidVersionStrings() {
            assertNotNull(new ApiVersion("5.0.0"), "Valid version 5.0.0 should be accepted.");
            assertNotNull(new ApiVersion("6.4.12"), "Valid version 6.4.12 should be accepted.");
            assertNotNull(new ApiVersion("7.0.0"), "Valid version 7.0.0 should be accepted.");
            assertNotNull(new ApiVersion("10.20.30"), "Valid version 10.20.30 should be accepted.");
        }

        @ParameterizedTest
        @ValueSource(strings = {"5.0", "6.a.0", "bad", "7.0.0.1", "-1.0.0", "1.-2.0", "1.0.-3", "1.2.beta"})
        @DisplayName("Test constructor with invalid version string formats")
        void testInvalidVersionStringFormats(String invalidVersion) {
            Exception exception = assertThrows(IllegalArgumentException.class, () -> new ApiVersion(invalidVersion),
                    "Invalid version string '" + invalidVersion + "' should throw IllegalArgumentException.");
            assertTrue(exception.getMessage().contains("Invalid API version format") || exception.getMessage().contains("Version parts cannot be negative"),
                       "Exception message should indicate format or negative part error for " + invalidVersion);
        }

        @Test
        @DisplayName("Test constructor with null version string")
        void testNullVersionString() {
            Exception exception = assertThrows(IllegalArgumentException.class, () -> new ApiVersion(null),
                    "Null version string should throw IllegalArgumentException.");
            assertEquals("Version string cannot be null or empty.", exception.getMessage(), "Check exception message for null input.");
        }

        @Test
        @DisplayName("Test constructor with empty version string")
        void testEmptyVersionString() {
            Exception exception = assertThrows(IllegalArgumentException.class, () -> new ApiVersion(""),
                    "Empty version string should throw IllegalArgumentException.");
            assertEquals("Version string cannot be null or empty.", exception.getMessage(), "Check exception message for empty input.");
        }

        @Test
        @DisplayName("Test constructor with blank version string")
        void testBlankVersionString() {
            Exception exception = assertThrows(IllegalArgumentException.class, () -> new ApiVersion("   "),
                    "Blank version string should throw IllegalArgumentException.");
            assertEquals("Version string cannot be null or empty.", exception.getMessage(), "Check exception message for blank input.");
        }

        @Test
        @DisplayName("Test constructor with leading zeros in version parts")
        void testVersionStringsWithLeadingZeros() {
            ApiVersion version = new ApiVersion("05.00.01");
            assertEquals("05.00.01", version.getRaw(), "Raw version should be preserved.");
            assertEquals(5.0f, version.getMajor(), "Major version should parse correctly.");
            assertEquals(1, version.getPatch(), "Patch version should parse correctly.");
        }
    }

    @Nested
    @DisplayName("Getter Method Tests")
    class GetterTests {
        private final ApiVersion version = new ApiVersion("6.4.12");
        private final ApiVersion versionWithZeros = new ApiVersion("07.00.05");


        @Test
        @DisplayName("getRaw() should return the original string")
        void testGetRaw() {
            assertEquals("6.4.12", version.getRaw(), "getRaw() should return the exact input string.");
            assertEquals("07.00.05", versionWithZeros.getRaw(), "getRaw() should preserve leading zeros in output.");
        }

        @Test
        @DisplayName("getMajor() should return correct float value")
        void testGetMajor() {
            assertEquals(6.4f, version.getMajor(), 0.001f, "Major version for 6.4.12 should be 6.4f.");
            assertEquals(7.0f, versionWithZeros.getMajor(), 0.001f, "Major version for 07.00.05 should be 7.0f.");
        }

        @Test
        @DisplayName("getPatch() should return correct integer patch version")
        void testGetPatch() {
            assertEquals(12, version.getPatch(), "Patch version for 6.4.12 should be 12.");
            assertEquals(5, versionWithZeros.getPatch(), "Patch version for 07.00.05 should be 5.");
        }
    }

    @Nested
    @DisplayName("isLts() Method Tests")
    class IsLtsTests {
        @ParameterizedTest
        @ValueSource(strings = {"5.0.0", "6.0.10", "7.0.0", "10.0.1"})
        @DisplayName("isLts() should return true for .0.x versions")
        void testIsLtsTrue(String ltsVersion) {
            assertTrue(new ApiVersion(ltsVersion).isLts(), ltsVersion + " should be considered an LTS version.");
        }

        @ParameterizedTest
        @ValueSource(strings = {"5.2.0", "6.4.1", "7.1.0"})
        @DisplayName("isLts() should return false for non .0.x versions")
        void testIsLtsFalse(String nonLtsVersion) {
            assertFalse(new ApiVersion(nonLtsVersion).isLts(), nonLtsVersion + " should not be considered an LTS version.");
        }
    }

    @Test
    @DisplayName("toString() should return the raw version string")
    void testToStringMethod() {
        String raw = "5.4.3";
        ApiVersion version = new ApiVersion(raw);
        assertEquals(raw, version.toString(), "toString() should be identical to getRaw().");
    }

    @Nested
    @DisplayName("equals() and hashCode() Tests")
    class EqualsAndHashCodeTests {
        private final ApiVersion v1 = new ApiVersion("6.0.5");
        private final ApiVersion v2 = new ApiVersion("6.0.5");
        private final ApiVersion v3 = new ApiVersion("6.0.10");
        private final ApiVersion v4 = new ApiVersion("06.00.05"); // Different raw, same numeric value

        @Test
        @DisplayName("equals() basic contract")
        void testEquals() {
            assertEquals(v1, v1, "An object must be equal to itself.");
            assertEquals(v1, v2, "Objects with the same raw version string must be equal.");
            assertNotEquals(v1, v3, "Objects with different raw version strings must not be equal.");
            assertNotEquals(v1, null, "An object must not be equal to null.");
            assertNotEquals(v1, "6.0.5", "An object must not be equal to an object of a different type.");
            assertNotEquals(v1, v4, "Objects with different raw (even if numerically same) are not equal by current 'raw' based equals.");
        }

        @Test
        @DisplayName("hashCode() consistency and equality")
        void testHashCode() {
            assertEquals(v1.hashCode(), v2.hashCode(), "Equal objects must have equal hash codes.");
            // Note: v1 and v4 are not equal by v1.equals(v4) because 'raw' differs.
            // Thus, their hashCodes are also expected to differ if 'raw' is the basis.
            assertNotEquals(v1.hashCode(), v4.hashCode(), "v1 and v4 have different raw strings, so hash codes should differ.");
        }
    }

    @Nested
    @DisplayName("String-based Comparison Method Tests")
    class StringComparisonTests {
        private final ApiVersion v_5_0_0 = new ApiVersion("5.0.0");
        private final ApiVersion v_5_2_0 = new ApiVersion("5.2.0");
        private final ApiVersion v_6_0_0 = new ApiVersion("6.0.0");
        private final ApiVersion v_6_0_5 = new ApiVersion("6.0.5");
        private final ApiVersion v_6_0_10 = new ApiVersion("6.0.10");

        @Test
        @DisplayName("compareTo() should correctly order versions")
        void testCompareTo() {
            assertTrue(v_5_0_0.compareTo(v_5_0_0) == 0, "5.0.0 should be equal to 5.0.0.");
            assertTrue(v_5_0_0.compareTo(v_5_2_0) < 0, "5.0.0 should be less than 5.2.0.");
            assertTrue(v_5_2_0.compareTo(v_5_0_0) > 0, "5.2.0 should be greater than 5.0.0.");
            assertTrue(v_5_2_0.compareTo(v_6_0_0) < 0, "5.2.0 should be less than 6.0.0.");
            assertTrue(v_6_0_0.compareTo(v_5_2_0) > 0, "6.0.0 should be greater than 5.2.0.");
            assertTrue(v_6_0_5.compareTo(v_6_0_10) < 0, "6.0.5 should be less than 6.0.10.");
            assertTrue(v_6_0_10.compareTo(v_6_0_5) > 0, "6.0.10 should be greater than 6.0.5.");
        }

        @Test
        @DisplayName("compareTo() should throw NullPointerException when comparing with null")
        void testCompareToNull() {
            assertThrows(NullPointerException.class, () -> v_5_0_0.compareTo(null), "Comparing to null should throw NullPointerException.");
        }

        @Test
        @DisplayName("isEqualTo(String) tests")
        void testIsEqualToWithString() {
            assertTrue(v_6_0_5.isEqualTo("6.0.5"), "6.0.5 should be equal to string '6.0.5'.");
            assertFalse(v_6_0_5.isEqualTo("6.0.4"), "6.0.5 should not be equal to string '6.0.4'.");
        }

        @Test
        @DisplayName("isGreaterThan(String) tests")
        void testIsGreaterThanWithString() {
            assertTrue(v_6_0_5.isGreaterThan("6.0.4"), "6.0.5 should be greater than string '6.0.4'.");
            assertFalse(v_6_0_5.isGreaterThan("6.0.5"), "6.0.5 should not be greater than string '6.0.5'.");
            assertFalse(v_6_0_5.isGreaterThan("6.0.6"), "6.0.5 should not be greater than string '6.0.6'.");
        }

        @Test
        @DisplayName("isLessThan(String) tests")
        void testIsLessThanWithString() {
            assertTrue(v_6_0_5.isLessThan("6.0.6"), "6.0.5 should be less than string '6.0.6'.");
            assertFalse(v_6_0_5.isLessThan("6.0.5"), "6.0.5 should not be less than string '6.0.5'.");
            assertFalse(v_6_0_5.isLessThan("6.0.4"), "6.0.5 should not be less than string '6.0.4'.");
        }

        @Test
        @DisplayName("String comparison methods with invalid string format should throw IllegalArgumentException")
        void testStringComparisonWithInvalidString() {
            String invalidFormat = "invalid";
            assertThrows(IllegalArgumentException.class, () -> v_5_0_0.isEqualTo(invalidFormat), "isEqualTo with invalid string should throw.");
            assertThrows(IllegalArgumentException.class, () -> v_5_0_0.isGreaterThan(invalidFormat), "isGreaterThan with invalid string should throw.");
            assertThrows(IllegalArgumentException.class, () -> v_5_0_0.isLessThan(invalidFormat), "isLessThan with invalid string should throw.");
        }
    }

    @Nested
    @DisplayName("Float-based Major Version Comparison Method Tests")
    class FloatComparisonTests {
        private final ApiVersion v_5_0_0 = new ApiVersion("5.0.0");
        private final ApiVersion v_5_2_10 = new ApiVersion("5.2.10");
        private final ApiVersion v_6_4_0 = new ApiVersion("6.4.0");

        @Test
        @DisplayName("isEqualTo(float) tests")
        void testIsEqualToWithFloat() {
            assertTrue(v_5_0_0.isEqualTo(5.0f), "5.0.0 major (5.0f) should be equal to 5.0f.");
            assertTrue(v_5_2_10.isEqualTo(5.2f), "5.2.10 major (5.2f) should be equal to 5.2f.");
            assertFalse(v_5_0_0.isEqualTo(5.1f), "5.0.0 major (5.0f) should not be equal to 5.1f.");
        }

        @Test
        @DisplayName("isGreaterThan(float) tests")
        void testIsGreaterThanWithFloat() {
            assertTrue(v_6_4_0.isGreaterThan(6.0f), "6.4.0 major (6.4f) should be greater than 6.0f.");
            assertTrue(v_5_2_10.isGreaterThan(5.0f), "5.2.10 major (5.2f) should be greater than 5.0f.");
            assertFalse(v_5_0_0.isGreaterThan(5.0f), "5.0.0 major (5.0f) should not be greater than 5.0f.");
            assertFalse(v_5_0_0.isGreaterThan(5.1f), "5.0.0 major (5.0f) should not be greater than 5.1f.");
        }

        @Test
        @DisplayName("isLessThan(float) tests")
        void testIsLessThanWithFloat() {
            assertTrue(v_5_0_0.isLessThan(5.1f), "5.0.0 major (5.0f) should be less than 5.1f.");
            assertTrue(v_5_2_10.isLessThan(6.0f), "5.2.10 major (5.2f) should be less than 6.0f.");
            assertFalse(v_6_4_0.isLessThan(6.4f), "6.4.0 major (6.4f) should not be less than 6.4f.");
            assertFalse(v_6_4_0.isLessThan(6.0f), "6.4.0 major (6.4f) should not be less than 6.0f.");
        }
    }
}
