package io.zabbix4j.api.types;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@link ItemValue} class.
 *
 * @author CSJ
 */
class ItemValueTest {

    @Nested
    @DisplayName("Constructor and Getter Tests")
    class ConstructorAndGetterTests {

        @Test
        @DisplayName("Constructor without timestamp should set fields correctly")
        void testConstructorWithoutTimestamp_ValidInputs() {
            ItemValue item = new ItemValue("TestHost", "item.key", "some_value");
            assertNotNull(item, "ItemValue object should be created.");
            assertEquals("TestHost", item.getHost(), "Host should match constructor argument.");
            assertEquals("item.key", item.getKey(), "Key should match constructor argument.");
            assertEquals("some_value", item.getValue(), "Value should match constructor argument.");
            assertNull(item.getClock(), "Clock should be null when not provided.");
            assertNull(item.getNs(), "Ns should be null when not provided.");
        }

        @Test
        @DisplayName("Constructor with timestamp should set all fields correctly")
        void testConstructorWithTimestamp_ValidInputs_TimestampPresent() {
            long clock = System.currentTimeMillis() / 1000L;
            int ns = 123456789;
            ItemValue item = new ItemValue("TestHost", "item.key", "value123", clock, ns);
            assertNotNull(item, "ItemValue object should be created with timestamp.");
            assertEquals("TestHost", item.getHost());
            assertEquals("item.key", item.getKey());
            assertEquals("value123", item.getValue());
            assertEquals(clock, item.getClock(), "Clock should match constructor argument.");
            assertEquals(ns, item.getNs(), "Ns should match constructor argument.");
        }

        @Test
        @DisplayName("Constructor with timestamp should allow null clock and ns")
        void testConstructorWithTimestamp_ValidInputs_TimestampNull() {
            ItemValue item = new ItemValue("TestHost", "item.key", "value123", null, null);
            assertNotNull(item, "ItemValue object should be created with null timestamp.");
            assertEquals("TestHost", item.getHost());
            assertEquals("item.key", item.getKey());
            assertEquals("value123", item.getValue());
            assertNull(item.getClock(), "Clock should be null if passed as null.");
            assertNull(item.getNs(), "Ns should be null if passed as null.");
        }
        
        @Test
        @DisplayName("Constructor should allow empty string for value")
        void testConstructor_EmptyValueAllowed() {
            ItemValue item = new ItemValue("TestHost", "item.key", "");
            assertNotNull(item);
            assertEquals("", item.getValue(), "Empty string should be a valid value.");
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {"", "  "})
        @DisplayName("Constructor should throw IllegalArgumentException for invalid host")
        void testConstructor_InvalidHost(String invalidHost) {
            Exception e = assertThrows(IllegalArgumentException.class, () -> new ItemValue(invalidHost, "item.key", "value"));
            assertEquals("Host cannot be null or empty.", e.getMessage());
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {"", "  "})
        @DisplayName("Constructor should throw IllegalArgumentException for invalid key")
        void testConstructor_InvalidKey(String invalidKey) {
            Exception e = assertThrows(IllegalArgumentException.class, () -> new ItemValue("TestHost", invalidKey, "value"));
            assertEquals("Key cannot be null or empty.", e.getMessage());
        }

        @Test
        @DisplayName("Constructor should throw IllegalArgumentException for null value")
        void testConstructor_InvalidValue_Null() {
            Exception e = assertThrows(IllegalArgumentException.class, () -> new ItemValue("TestHost", "item.key", null));
            assertEquals("Value cannot be null.", e.getMessage());
        }
    }

    @Nested
    @DisplayName("toMap() Method Tests")
    class ToMapTests {

        @Test
        @DisplayName("toMap() without timestamp should not include clock and ns")
        void testToMap_WithoutTimestamp() {
            ItemValue item = new ItemValue("HostA", "key1", "val1");
            Map<String, Object> map = item.toMap();

            assertEquals(3, map.size(), "Map size should be 3 without timestamp.");
            assertEquals("HostA", map.get("host"));
            assertEquals("key1", map.get("key"));
            assertEquals("val1", map.get("value"));
            assertFalse(map.containsKey("clock"), "Map should not contain 'clock'.");
            assertFalse(map.containsKey("ns"), "Map should not contain 'ns'.");
        }

        @Test
        @DisplayName("toMap() with timestamp should include clock and ns")
        void testToMap_WithTimestamp() {
            long clockVal = 1678886400L;
            int nsVal = 500000000;
            ItemValue item = new ItemValue("HostB", "key2", "val2", clockVal, nsVal);
            Map<String, Object> map = item.toMap();

            assertEquals(5, map.size(), "Map size should be 5 with timestamp.");
            assertEquals("HostB", map.get("host"));
            assertEquals("key2", map.get("key"));
            assertEquals("val2", map.get("value"));
            assertEquals(clockVal, map.get("clock"));
            assertEquals(nsVal, map.get("ns"));
        }

        @Test
        @DisplayName("toMap() with only clock set should include clock but not ns")
        void testToMap_WithClockOnly() {
            long clockVal = 1678886400L;
            ItemValue item = new ItemValue("HostC", "key3", "val3", clockVal, null);
            Map<String, Object> map = item.toMap();

            assertEquals(4, map.size(), "Map size should be 4 with clock only.");
            assertTrue(map.containsKey("clock"), "Map should contain 'clock'.");
            assertFalse(map.containsKey("ns"), "Map should not contain 'ns'.");
            assertEquals(clockVal, map.get("clock"));
        }
    }

    @Nested
    @DisplayName("toString() Method Tests")
    class ToStringTests {

        @Test
        @DisplayName("toString() without timestamp should produce correct JSON-like string")
        void testToString_WithoutTimestamp() {
            ItemValue item = new ItemValue("MyHost", "my.key", "my_value");
            String expected = "{\"host\":\"MyHost\",\"key\":\"my.key\",\"value\":\"my_value\"}";
            assertEquals(expected, item.toString());
        }

        @Test
        @DisplayName("toString() with timestamp should produce correct JSON-like string")
        void testToString_WithTimestamp() {
            long ঘড়ি = 1609459200L; // Example clock
            int ন্যানো = 123000000;  // Example ns
            ItemValue item = new ItemValue("MyHost", "my.key", "my_value", ঘড়ি, ন্যানো);
            String expected = "{\"host\":\"MyHost\",\"key\":\"my.key\",\"value\":\"my_value\",\"clock\":" + ঘড়ি + ",\"ns\":" + ন্যানো + "}";
            assertEquals(expected, item.toString());
        }
        
        @Test
        @DisplayName("toString() with empty value should represent it correctly")
        void testToString_EmptyValue() {
            ItemValue item = new ItemValue("MyHost", "my.key", "");
            String expected = "{\"host\":\"MyHost\",\"key\":\"my.key\",\"value\":\"\"}";
            assertEquals(expected, item.toString());
        }
    }

    @Nested
    @DisplayName("equals() and hashCode() Tests")
    class EqualsAndHashCodeTests {
        ItemValue item1a = new ItemValue("Host1", "key1", "val1", 100L, 10);
        ItemValue item1b = new ItemValue("Host1", "key1", "val1", 100L, 10);
        ItemValue item2_diffHost = new ItemValue("Host2", "key1", "val1", 100L, 10);
        ItemValue item3_diffKey = new ItemValue("Host1", "key2", "val1", 100L, 10);
        ItemValue item4_diffValue = new ItemValue("Host1", "key1", "val2", 100L, 10);
        ItemValue item5_diffClock = new ItemValue("Host1", "key1", "val1", 200L, 10);
        ItemValue item6_diffNs = new ItemValue("Host1", "key1", "val1", 100L, 20);
        ItemValue item7_nullClockNs = new ItemValue("Host1", "key1", "val1");
        ItemValue item8_nullClockNsCopy = new ItemValue("Host1", "key1", "val1", null, null);


        @Test
        @DisplayName("equals() basic contract")
        void testEquals() {
            assertEquals(item1a, item1a, "An object must be equal to itself.");
            assertEquals(item1a, item1b, "Objects with identical fields must be equal.");
            assertNotEquals(item1a, item2_diffHost, "Objects with different host must not be equal.");
            assertNotEquals(item1a, item3_diffKey, "Objects with different key must not be equal.");
            assertNotEquals(item1a, item4_diffValue, "Objects with different value must not be equal.");
            assertNotEquals(item1a, item5_diffClock, "Objects with different clock must not be equal.");
            assertNotEquals(item1a, item6_diffNs, "Objects with different ns must not be equal.");
            assertNotEquals(item1a, item7_nullClockNs, "Object with timestamp should not equal object without.");
            assertEquals(item7_nullClockNs, item8_nullClockNsCopy, "Objects with null clock/ns should be equal.");
            assertNotEquals(item1a, null, "An object must not be equal to null.");
            assertNotEquals(item1a, "AString", "An object must not be equal to an object of a different type.");
        }

        @Test
        @DisplayName("hashCode() consistency and equality")
        void testHashCode() {
            assertEquals(item1a.hashCode(), item1b.hashCode(), "Equal objects must have equal hash codes.");
            assertEquals(item7_nullClockNs.hashCode(), item8_nullClockNsCopy.hashCode(), "Equal objects with null timestamps must have equal hash codes.");
            // It's not strictly required for non-equal objects to have different hash codes, but good if they do.
            // We mainly test that equal objects have equal hash codes.
        }
    }
}
