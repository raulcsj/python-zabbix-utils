package io.zabbix4j.api.types;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@link AgentResponse} class.
 *
 * @author CSJ
 */
class AgentResponseTest {

    @Nested
    @DisplayName("Constructor and Getter Tests")
    class ConstructorAndGetterTests {

        @Test
        @DisplayName("Constructor with normal value string should set fields correctly")
        void testConstructor_NormalValue() {
            String raw = "123.45";
            AgentResponse response = new AgentResponse(raw);
            assertEquals(raw, response.getRawResponse(), "Raw response should match input.");
            assertEquals(raw, response.getValue(), "Value should be the input string.");
            assertNull(response.getError(), "Error should be null for normal value.");
            assertFalse(response.hasError(), "hasError() should be false for normal value.");
        }

        @Test
        @DisplayName("Constructor with another normal value string")
        void testConstructor_AnotherNormalValue() {
            String raw = "some value";
            AgentResponse response = new AgentResponse(raw);
            assertEquals(raw, response.getRawResponse());
            assertEquals(raw, response.getValue());
            assertNull(response.getError());
            assertFalse(response.hasError());
        }

        @Test
        @DisplayName("Constructor with 'ZBX_NOTSUPPORTED' string")
        void testConstructor_ZbxNotSupportedSimple() {
            String raw = "ZBX_NOTSUPPORTED";
            AgentResponse response = new AgentResponse(raw);
            assertEquals(raw, response.getRawResponse());
            assertNull(response.getValue(), "Value should be null for ZBX_NOTSUPPORTED.");
            assertEquals("Not supported by Zabbix Agent", response.getError(), "Error message should be default for ZBX_NOTSUPPORTED.");
            assertTrue(response.hasError(), "hasError() should be true.");
        }

        @Test
        @DisplayName("Constructor with 'ZBX_NOTSUPPORTED\\0Detailed error message'")
        void testConstructor_ZbxNotSupportedWithDetails() {
            String details = "Detailed error message about why it's not supported.";
            String raw = "ZBX_NOTSUPPORTED\0" + details;
            AgentResponse response = new AgentResponse(raw);
            assertEquals(raw, response.getRawResponse());
            assertNull(response.getValue(), "Value should be null.");
            assertEquals(details, response.getError(), "Error message should be the details provided after null char.");
            assertTrue(response.hasError());
        }
        
        @Test
        @DisplayName("Constructor with 'ZBX_NOTSUPPORTED\\0' (empty details)")
        void testConstructor_ZbxNotSupportedWithEmptyDetails() {
            String raw = "ZBX_NOTSUPPORTED\0";
            AgentResponse response = new AgentResponse(raw);
            assertEquals(raw, response.getRawResponse());
            assertNull(response.getValue(), "Value should be null.");
            assertEquals("", response.getError(), "Error message should be empty string if only null char follows ZBX_NOTSUPPORTED.");
            assertTrue(response.hasError());
        }


        @Test
        @DisplayName("Constructor with value string containing a null character suffix")
        void testConstructor_ValueWithNullCharacterSuffix() {
            String valuePart = "actual_value";
            String extraPart = "extra data after null";
            String raw = valuePart + "\0" + extraPart;
            AgentResponse response = new AgentResponse(raw);
            assertEquals(raw, response.getRawResponse());
            assertEquals(valuePart, response.getValue(), "Value should be the part before the null character.");
            assertNull(response.getError(), "Error should be null.");
            assertFalse(response.hasError());
        }

        @Test
        @DisplayName("Constructor with null rawResponse should throw IllegalArgumentException")
        void testConstructor_NullRawResponse_ThrowsException() {
            Exception e = assertThrows(IllegalArgumentException.class, () -> new AgentResponse(null));
            assertEquals("Raw response cannot be null.", e.getMessage());
        }

        @Test
        @DisplayName("Constructor with empty string rawResponse")
        void testConstructor_EmptyRawResponse() {
            String raw = "";
            AgentResponse response = new AgentResponse(raw);
            assertEquals(raw, response.getRawResponse());
            assertEquals("", response.getValue(), "Value should be empty string for empty input.");
            assertNull(response.getError(), "Error should be null for empty input.");
            assertFalse(response.hasError());
        }
    }

    @Nested
    @DisplayName("hasError() Method Tests")
    class HasErrorTests {
        @Test
        @DisplayName("hasError() should return true when error is present")
        void testHasError_TrueWhenErrorPresent() {
            AgentResponse response = new AgentResponse("ZBX_NOTSUPPORTED");
            assertTrue(response.hasError(), "hasError should be true if error field is not null.");
        }

        @Test
        @DisplayName("hasError() should return false when error is not present (value is present)")
        void testHasError_FalseWhenNoError() {
            AgentResponse response = new AgentResponse("some_value");
            assertFalse(response.hasError(), "hasError should be false if error field is null.");
        }
    }

    @Nested
    @DisplayName("toString() Method Tests")
    class ToStringTests {
        @Test
        @DisplayName("toString() for response with value")
        void testToStringMethod_WithValue() {
            AgentResponse response = new AgentResponse("my_data_value");
            String expected = "AgentResponse{value='my_data_value', error='null'}";
            assertEquals(expected, response.toString());
        }

        @Test
        @DisplayName("toString() for response with error")
        void testToStringMethod_WithError() {
            AgentResponse response = new AgentResponse("ZBX_NOTSUPPORTED\0Custom error here");
            String expected = "AgentResponse{value='null', error='Custom error here'}";
            assertEquals(expected, response.toString());
        }

        @Test
        @DisplayName("toString() for response with default ZBX_NOTSUPPORTED error")
        void testToStringMethod_WithDefaultError() {
            AgentResponse response = new AgentResponse("ZBX_NOTSUPPORTED");
            String expected = "AgentResponse{value='null', error='Not supported by Zabbix Agent'}";
            assertEquals(expected, response.toString());
        }
    }

    @Nested
    @DisplayName("equals() and hashCode() Tests")
    class EqualsAndHashCodeTests {
        AgentResponse r1a = new AgentResponse("value1");
        AgentResponse r1b = new AgentResponse("value1"); // Equal to r1a

        AgentResponse r2_error = new AgentResponse("ZBX_NOTSUPPORTED");
        AgentResponse r2b_error = new AgentResponse("ZBX_NOTSUPPORTED"); // Equal to r2_error

        AgentResponse r3_diffValue = new AgentResponse("value2");
        AgentResponse r4_diffError = new AgentResponse("ZBX_NOTSUPPORTED\0Different error");
        AgentResponse r5_valueWithNull = new AgentResponse("value1\0extra");


        @Test
        @DisplayName("equals() basic contract")
        void testEquals() {
            assertEquals(r1a, r1a, "An object must be equal to itself.");
            assertEquals(r1a, r1b, "Objects with same raw, value, and error must be equal.");
            assertEquals(r2_error, r2b_error, "Error objects with same state must be equal.");

            assertNotEquals(r1a, r2_error, "Value response should not equal error response.");
            assertNotEquals(r1a, r3_diffValue, "Responses with different values must not be equal.");
            assertNotEquals(r2_error, r4_diffError, "Error responses with different error messages must not be equal.");
            assertNotEquals(r1a, r5_valueWithNull, "Response for 'value1' should not equal response for 'value1\\0extra'.");


            assertNotEquals(r1a, null, "An object must not be equal to null.");
            assertNotEquals(r1a, "AString", "An object must not be equal to an object of a different type.");
        }

        @Test
        @DisplayName("hashCode() consistency and equality")
        void testHashCode() {
            assertEquals(r1a.hashCode(), r1b.hashCode(), "Equal objects (value) must have equal hash codes.");
            assertEquals(r2_error.hashCode(), r2b_error.hashCode(), "Equal objects (error) must have equal hash codes.");

            // Not strictly required for non-equal objects to have different hash codes,
            // but good if they do.
            assertNotEquals(r1a.hashCode(), r2_error.hashCode(), "Hash codes for value vs error response should ideally differ.");
        }
    }
}
