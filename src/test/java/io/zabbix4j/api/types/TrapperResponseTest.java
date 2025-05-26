package io.zabbix4j.api.types;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@link TrapperResponse} class.
 *
 * @author CSJ
 */
class TrapperResponseTest {

    @Nested
    @DisplayName("Default Constructor and Initial State Tests")
    class ConstructorTests {
        @Test
        @DisplayName("Default constructor should initialize all fields to zero/default")
        void testDefaultConstructorInitializesFieldsCorrectly() {
            TrapperResponse response = new TrapperResponse();
            assertEquals(0, response.getProcessed(), "Processed should be initialized to 0.");
            assertEquals(0, response.getFailed(), "Failed should be initialized to 0.");
            assertEquals(0, response.getTotal(), "Total should be initialized to 0.");
            assertEquals(0.0, response.getTimeSpent(), 0.00001, "TimeSpent should be initialized to 0.0.");
            assertEquals(0, response.getChunkCount(), "ChunkCount should be initialized to 0.");
        }
    }

    @Nested
    @DisplayName("parseAndAdd(String responseInfoString) Method Tests")
    class ParseAndAddTests {

        @Test
        @DisplayName("parseAndAdd with valid full string should update fields correctly")
        void testParseAndAdd_ValidFullString() {
            TrapperResponse response = new TrapperResponse();
            response.parseAndAdd("processed: 10; failed: 2; total: 12; seconds spent: 0.00345");
            assertEquals(10, response.getProcessed());
            assertEquals(2, response.getFailed());
            assertEquals(12, response.getTotal());
            assertEquals(0.00345, response.getTimeSpent(), 0.000001);
            assertEquals(1, response.getChunkCount());
        }

        @Test
        @DisplayName("Multiple calls to parseAndAdd should aggregate values")
        void testParseAndAdd_MultipleCallsAggregateValues() {
            TrapperResponse response = new TrapperResponse();
            response.parseAndAdd("processed: 5; failed: 1; total: 6; seconds spent: 0.1");
            response.parseAndAdd("processed: 10; failed: 2; total: 12; seconds spent: 0.05");

            assertEquals(15, response.getProcessed(), "Processed should be aggregated.");
            assertEquals(3, response.getFailed(), "Failed should be aggregated.");
            assertEquals(18, response.getTotal(), "Total should be aggregated.");
            assertEquals(0.15, response.getTimeSpent(), 0.000001, "TimeSpent should be aggregated.");
            assertEquals(2, response.getChunkCount(), "ChunkCount should be incremented for each successful parse.");
        }

        @Test
        @DisplayName("parseAndAdd should handle variations in case and spacing")
        void testParseAndAdd_VariationsInCaseAndSpacing() {
            TrapperResponse response = new TrapperResponse();
            response.parseAndAdd("Processed:  5;Failed:1; Total:   6;   Seconds spent:  0.123  ");
            assertEquals(5, response.getProcessed());
            assertEquals(1, response.getFailed());
            assertEquals(6, response.getTotal());
            assertEquals(0.123, response.getTimeSpent(), 0.000001);
            assertEquals(1, response.getChunkCount());
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "failed: 2; total: 12; seconds spent: 0.00345", // Missing processed
            "processed: 10; total: 12; seconds spent: 0.00345", // Missing failed
            "processed: 10; failed: 2; seconds spent: 0.00345", // Missing total
            "processed: 10; failed: 2; total: 12" // Missing seconds spent
        })
        @DisplayName("parseAndAdd should throw IllegalArgumentException for strings missing essential parts")
        void testParseAndAdd_MissingParts_ThrowsException(String incompleteString) {
            TrapperResponse response = new TrapperResponse();
            Exception e = assertThrows(IllegalArgumentException.class, () -> response.parseAndAdd(incompleteString));
            assertTrue(e.getMessage().startsWith("Could not find"), "Exception message should indicate missing part for: " + incompleteString);
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "processed: ten; failed: 2; total: 12; seconds spent: 0.00345", // Malformed processed
            "processed: 10; failed: two; total: 12; seconds spent: 0.00345", // Malformed failed
            "processed: 10; failed: 2; total: twelve; seconds spent: 0.00345", // Malformed total
            "processed: 10; failed: 2; total: 12; seconds spent: zero.point.one" // Malformed time
        })
        @DisplayName("parseAndAdd should throw IllegalArgumentException for malformed numbers")
        void testParseAndAdd_MalformedNumber_ThrowsException(String malformedString) {
            TrapperResponse response = new TrapperResponse();
            Exception e = assertThrows(IllegalArgumentException.class, () -> response.parseAndAdd(malformedString));
            assertTrue(e.getMessage().contains("Invalid number format for"), "Exception message should indicate number format error for: " + malformedString);
        }

        @Test
        @DisplayName("parseAndAdd should throw IllegalArgumentException for completely invalid string")
        void testParseAndAdd_CompletelyInvalidString_ThrowsException() {
            TrapperResponse response = new TrapperResponse();
            String invalid = "this is not a zabbix response";
            Exception e = assertThrows(IllegalArgumentException.class, () -> response.parseAndAdd(invalid));
            assertTrue(e.getMessage().startsWith("Could not find"), "Exception message should indicate missing part for invalid string.");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("parseAndAdd should throw IllegalArgumentException for null or empty input")
        void testParseAndAdd_NullOrEmptyInput_ThrowsException(String input) {
            TrapperResponse response = new TrapperResponse();
            Exception e = assertThrows(IllegalArgumentException.class, () -> response.parseAndAdd(input));
            assertEquals("Response info string cannot be null or empty.", e.getMessage());
        }

        @Test
        @DisplayName("parseAndAdd should increment chunkCount correctly on successful parses")
        void testParseAndAdd_ChunkCountIncrementation() {
            TrapperResponse response = new TrapperResponse();
            assertEquals(0, response.getChunkCount());
            response.parseAndAdd("processed: 1; failed: 0; total: 1; seconds spent: 0.01");
            assertEquals(1, response.getChunkCount());
            response.parseAndAdd("PROCESSED: 2; FAILED: 0; TOTAL: 2; SECONDS SPENT: 0.02");
            assertEquals(2, response.getChunkCount());
        }
    }

    @Nested
    @DisplayName("toString() Method Tests")
    class ToStringTests {
        @Test
        @DisplayName("toString() should return accurate string representation")
        void testToStringMethod() {
            TrapperResponse response = new TrapperResponse();
            response.parseAndAdd("processed: 10; failed: 2; total: 12; seconds spent: 0.1234567");
            // Default double to string can be variable, so we check parts or use String.format if specific precision is in toString()
            String expected = "TrapperResponse{processed=10, failed=2, total=12, timeSpent=0.12346, chunks=1}"; // timeSpent formatted to 5 decimal places by default in TrapperResponse
            assertEquals(expected, response.toString());

            TrapperResponse response2 = new TrapperResponse();
            assertEquals("TrapperResponse{processed=0, failed=0, total=0, timeSpent=0.00000, chunks=0}", response2.toString());
        }
    }

    @Nested
    @DisplayName("equals() and hashCode() Tests")
    class EqualsAndHashCodeTests {
        @Test
        @DisplayName("equals() and hashCode() basic contract")
        void testEqualsAndHashCode() {
            TrapperResponse r1 = new TrapperResponse();
            r1.parseAndAdd("processed: 10; failed: 1; total: 11; seconds spent: 0.1");

            TrapperResponse r2 = new TrapperResponse();
            r2.parseAndAdd("processed: 10; failed: 1; total: 11; seconds spent: 0.1");

            TrapperResponse r3 = new TrapperResponse();
            r3.parseAndAdd("processed: 20; failed: 2; total: 22; seconds spent: 0.2");

            TrapperResponse r4_initial = new TrapperResponse();

            // Reflexivity
            assertEquals(r1, r1);

            // Symmetry
            assertEquals(r1, r2);
            assertEquals(r2, r1);

            // Transitivity (implicit if r1=r2 and r2=r1)

            // Inequality
            assertNotEquals(r1, r3);
            assertNotEquals(r1, r4_initial);
            assertNotEquals(r1, null);
            assertNotEquals(r1, new Object());

            // HashCode
            assertEquals(r1.hashCode(), r2.hashCode(), "Equal objects must have equal hash codes.");
            // Not strictly required, but good if non-equal objects have different hash codes
            // assertNotEquals(r1.hashCode(), r3.hashCode());
        }
    }
}
