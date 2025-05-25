package io.zabbix4j.api.types;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ItemValue} and its {@link ItemValue.Builder}.
 *
 * @author CSJ
 */
class ItemValueTest {

    @Test
    void testBuilder_allFieldsSet() {
        ItemValue item = ItemValue.newBuilder()
                .host("Test Host")
                .key("test.key")
                .value("123")
                .clock(1678886400L)
                .ns(123456789)
                .build();

        assertEquals("Test Host", item.getHost());
        assertEquals("test.key", item.getKey());
        assertEquals("123", item.getValue());
        assertEquals(1678886400L, item.getClock());
        assertEquals(123456789, item.getNs());
    }

    @Test
    void testBuilder_mandatoryFieldsOnly() {
        ItemValue item = ItemValue.newBuilder()
                .host("Mandatory Host")
                .key("mandatory.key")
                .value("mandatory_value")
                .build();

        assertEquals("Mandatory Host", item.getHost());
        assertEquals("mandatory.key", item.getKey());
        assertEquals("mandatory_value", item.getValue());
        assertNull(item.getClock());
        assertNull(item.getNs());
    }

    static Stream<ItemValue.Builder> invalidBuilders_missingMandatoryFields() {
        return Stream.of(
                ItemValue.newBuilder().key("key").value("value"), // Missing host
                ItemValue.newBuilder().host("host").value("value"), // Missing key
                ItemValue.newBuilder().host("host").key("key") // Missing value
        );
    }

    @ParameterizedTest
    @MethodSource("invalidBuilders_missingMandatoryFields")
    void testBuilder_missingMandatoryField_throwsException(ItemValue.Builder builder) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, builder::build);
        assertTrue(exception.getMessage().contains("cannot be null or empty"));
    }

    @Test
    void testBuilder_emptyMandatoryField_throwsException() {
        assertThrows(IllegalArgumentException.class, () -> ItemValue.newBuilder().host("").key("k").value("v").build());
        assertThrows(IllegalArgumentException.class, () -> ItemValue.newBuilder().host("h").key("").value("v").build());
        assertThrows(IllegalArgumentException.class, () -> ItemValue.newBuilder().host("h").key("k").value("").build());
    }


    @Test
    void testToJsonMap_allFields() {
        ItemValue item = ItemValue.newBuilder()
                .host("MapHost")
                .key("map.key")
                .value("map_value")
                .clock(1678886401L)
                .ns(987654321)
                .build();

        Map<String, Object> jsonMap = item.toJsonMap();

        assertEquals(5, jsonMap.size());
        assertEquals("MapHost", jsonMap.get("host"));
        assertEquals("map.key", jsonMap.get("key"));
        assertEquals("map_value", jsonMap.get("value"));
        assertEquals(1678886401L, jsonMap.get("clock"));
        assertEquals(987654321, jsonMap.get("ns"));
    }

    @Test
    void testToJsonMap_mandatoryOnly() {
        ItemValue item = ItemValue.newBuilder()
                .host("MapHostMandatory")
                .key("map.key.mandatory")
                .value("map_value_mandatory")
                .build();

        Map<String, Object> jsonMap = item.toJsonMap();

        assertEquals(3, jsonMap.size());
        assertEquals("MapHostMandatory", jsonMap.get("host"));
        assertEquals("map.key.mandatory", jsonMap.get("key"));
        assertEquals("map_value_mandatory", jsonMap.get("value"));
        assertFalse(jsonMap.containsKey("clock"));
        assertFalse(jsonMap.containsKey("ns"));
    }

    @Test
    void testEqualsAndHashCode() {
        ItemValue item1 = ItemValue.newBuilder().host("h").key("k").value("v").clock(1L).ns(100).build();
        ItemValue item2 = ItemValue.newBuilder().host("h").key("k").value("v").clock(1L).ns(100).build();
        ItemValue item3 = ItemValue.newBuilder().host("h_diff").key("k").value("v").clock(1L).ns(100).build(); // Diff host
        ItemValue item4 = ItemValue.newBuilder().host("h").key("k_diff").value("v").clock(1L).ns(100).build(); // Diff key
        ItemValue item5 = ItemValue.newBuilder().host("h").key("k").value("v_diff").clock(1L).ns(100).build(); // Diff value
        ItemValue item6 = ItemValue.newBuilder().host("h").key("k").value("v").clock(2L).ns(100).build(); // Diff clock
        ItemValue item7 = ItemValue.newBuilder().host("h").key("k").value("v").clock(1L).ns(200).build(); // Diff ns
        ItemValue item8 = ItemValue.newBuilder().host("h").key("k").value("v").build(); // Missing clock/ns
        ItemValue item9 = ItemValue.newBuilder().host("h").key("k").value("v").build(); // Missing clock/ns

        // Reflexivity
        assertEquals(item1, item1);

        // Symmetry
        assertEquals(item1, item2);
        assertEquals(item2, item1);

        // Inequality
        assertNotEquals(item1, item3);
        assertNotEquals(item1, item4);
        assertNotEquals(item1, item5);
        assertNotEquals(item1, item6);
        assertNotEquals(item1, item7);
        assertNotEquals(item1, item8); // item1 has clock/ns, item8 does not

        // Equality for items with no optional fields
        assertEquals(item8, item9);

        // HashCode
        assertEquals(item1.hashCode(), item2.hashCode());
        assertNotEquals(item1.hashCode(), item3.hashCode());
        assertEquals(item8.hashCode(), item9.hashCode());

        // Test with null
        assertNotEquals(null, item1);
    }

    @Test
    void testToString() {
        ItemValue item = ItemValue.newBuilder()
                .host("ToStringHost")
                .key("tostring.key")
                .value("tostring_val")
                .clock(1000L)
                .build(); // ns is null

        String str = item.toString();
        assertNotNull(str);
        assertTrue(str.contains("ToStringHost"));
        assertTrue(str.contains("tostring.key"));
        assertTrue(str.contains("tostring_val"));
        assertTrue(str.contains("clock=1000"));
        assertFalse(str.contains("ns=")); // ns should not be in map if null
    }

    @Test
    void testBuilder_setOptionalToNull() {
         ItemValue item = ItemValue.newBuilder()
                .host("Test Host")
                .key("test.key")
                .value("123")
                .clock(null)
                .ns(null)
                .build();
        assertNull(item.getClock());
        assertNull(item.getNs());

        Map<String, Object> jsonMap = item.toJsonMap();
        assertFalse(jsonMap.containsKey("clock"));
        assertFalse(jsonMap.containsKey("ns"));
    }
}
