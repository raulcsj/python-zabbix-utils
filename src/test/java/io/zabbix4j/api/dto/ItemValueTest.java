package io.zabbix4j.api.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link ItemValue}.
 * @author ShortRoundDev
 */
class ItemValueTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testBuilderAndGetters_allFields() {
        long currentTime = System.currentTimeMillis() / 1000L;
        int nanos = 123456789;

        ItemValue item = ItemValue.builder()
                .host("Zabbix Server")
                .key("system.cpu.load[all,avg1]")
                .value("0.5")
                .clock(currentTime)
                .ns(nanos)
                .build();

        assertEquals("Zabbix Server", item.getHost());
        assertEquals("system.cpu.load[all,avg1]", item.getKey());
        assertEquals("0.5", item.getValue());
        assertEquals(currentTime, item.getClock());
        assertEquals(nanos, item.getNs());
    }

    @Test
    void testBuilderAndGetters_requiredFieldsOnly() {
        ItemValue item = ItemValue.builder()
                .host("Test Host")
                .key("test.key")
                .value("test_value")
                .build();

        assertEquals("Test Host", item.getHost());
        assertEquals("test.key", item.getKey());
        assertEquals("test_value", item.getValue());
        assertNull(item.getClock());
        assertNull(item.getNs());
    }

    @Test
    void testBuilder_nullOrEmptyRequiredFields_throwsException() {
        assertThrows(IllegalArgumentException.class, () -> ItemValue.builder().host(null).key("k").value("v").build());
        assertThrows(IllegalArgumentException.class, () -> ItemValue.builder().host("").key("k").value("v").build());
        assertThrows(IllegalArgumentException.class, () -> ItemValue.builder().host("h").key(null).value("v").build());
        assertThrows(IllegalArgumentException.class, () -> ItemValue.builder().host("h").key("").value("v").build());
        assertThrows(IllegalArgumentException.class, () -> ItemValue.builder().host("h").key("k").value(null).build());
        // Value can be empty string
        ItemValue item = ItemValue.builder().host("h").key("k").value("").build();
        assertEquals("", item.getValue());
    }

    @Test
    void testEqualsAndHashCode() {
        long time1 = System.currentTimeMillis() / 1000L;
        ItemValue item1 = ItemValue.builder().host("h1").key("k1").value("v1").clock(time1).ns(100).build();
        ItemValue item2 = ItemValue.builder().host("h1").key("k1").value("v1").clock(time1).ns(100).build();
        ItemValue item3 = ItemValue.builder().host("h2").key("k1").value("v1").clock(time1).ns(100).build(); // Different host
        ItemValue item4 = ItemValue.builder().host("h1").key("k2").value("v1").clock(time1).ns(100).build(); // Different key
        ItemValue item5 = ItemValue.builder().host("h1").key("k1").value("v2").clock(time1).ns(100).build(); // Different value
        ItemValue item6 = ItemValue.builder().host("h1").key("k1").value("v1").clock(time1 + 1).ns(100).build(); // Different clock
        ItemValue item7 = ItemValue.builder().host("h1").key("k1").value("v1").clock(time1).ns(200).build(); // Different ns
        ItemValue item8 = ItemValue.builder().host("h1").key("k1").value("v1").build(); // Missing clock/ns

        assertEquals(item1, item2);
        assertEquals(item1.hashCode(), item2.hashCode());

        assertNotEquals(item1, item3);
        assertNotEquals(item1, item4);
        assertNotEquals(item1, item5);
        assertNotEquals(item1, item6);
        assertNotEquals(item1, item7);
        assertNotEquals(item1, item8);
        assertNotEquals(item1, null);
        assertNotEquals(item1, new Object());
    }

    @Test
    void testToString() {
        ItemValue itemWithAll = ItemValue.builder().host("Host").key("key").value("val").clock(12345L).ns(6789).build();
        String strWithAll = itemWithAll.toString();
        assertTrue(strWithAll.contains("host='Host'"));
        assertTrue(strWithAll.contains("key='key'"));
        assertTrue(strWithAll.contains("value='val'"));
        assertTrue(strWithAll.contains("clock=12345"));
        assertTrue(strWithAll.contains("ns=6789"));

        ItemValue itemWithRequired = ItemValue.builder().host("Host").key("key").value("val").build();
        String strWithRequired = itemWithRequired.toString();
        assertTrue(strWithRequired.contains("host='Host'"));
        assertTrue(strWithRequired.contains("key='key'"));
        assertTrue(strWithRequired.contains("value='val'"));
        assertFalse(strWithRequired.contains("clock="));
        assertFalse(strWithRequired.contains("ns="));
    }

    @Test
    void testJsonSerialization() throws JsonProcessingException {
        long currentTime = System.currentTimeMillis() / 1000L;
        ItemValue item = ItemValue.builder()
                .host("Zabbix Server")
                .key("system.cpu.load[all,avg1]")
                .value("0.5")
                .clock(currentTime)
                .ns(12345)
                .build();

        // This is how Zabbix Sender protocol expects the 'data' part of its JSON request
        // { "host": "Zabbix Server", "key": "system.cpu.load[all,avg1]", "value": "0.5", "clock": currentTime, "ns": 12345 }
        String expectedJson = String.format(
                "{\"host\":\"%s\",\"key\":\"%s\",\"value\":\"%s\",\"clock\":%d,\"ns\":%d}",
                "Zabbix Server", "system.cpu.load[all,avg1]", "0.5", currentTime, 12345
        );

        // Note: Jackson serializes fields based on getters.
        // The ItemValue DTO itself is not directly annotated with @JsonProperty for specific field names,
        // but standard bean serialization should work. Let's verify the structure.
        // The field names in ItemValue (host, key, value, clock, ns) match common JSON conventions.

        String actualJson = objectMapper.writeValueAsString(item);

        // We can parse both and compare JsonNode objects for more robust comparison
        // but for this DTO, string comparison after confirming field names is okay.
        // The order of fields in JSON string is not guaranteed by Jackson,
        // so comparing JsonNode is better if order is not fixed.
        com.fasterxml.jackson.databind.JsonNode actualNode = objectMapper.readTree(actualJson);
        com.fasterxml.jackson.databind.JsonNode expectedNode = objectMapper.readTree(expectedJson);

        assertEquals(expectedNode, actualNode);
    }

     @Test
    void testJsonSerialization_requiredOnly() throws JsonProcessingException {
        ItemValue item = ItemValue.builder()
                .host("MinimalHost")
                .key("minimal.key")
                .value("minimal_value")
                .build();

        String expectedJson = String.format(
                "{\"host\":\"%s\",\"key\":\"%s\",\"value\":\"%s\",\"clock\":null,\"ns\":null}",
                "MinimalHost", "minimal.key", "minimal_value"
        );
         // By default, Jackson does not include null fields. If we want nulls, needs configuration.
         // Let's assume default behaviour: null fields are omitted.
        String expectedJsonNoNulls = String.format(
                "{\"host\":\"%s\",\"key\":\"%s\",\"value\":\"%s\"}",
                "MinimalHost", "minimal.key", "minimal_value"
        );


        String actualJson = objectMapper.writeValueAsString(item);
        com.fasterxml.jackson.databind.JsonNode actualNode = objectMapper.readTree(actualJson);
        com.fasterxml.jackson.databind.JsonNode expectedNode = objectMapper.readTree(expectedJsonNoNulls);
        assertEquals(expectedNode, actualNode);
    }
}
