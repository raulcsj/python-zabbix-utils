package io.zabbix4j.api.types;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TrapperResponse}.
 *
 * @author CSJ
 */
class TrapperResponseTest {

    @Test
    void testDefaultConstructor() {
        TrapperResponse response = new TrapperResponse();
        assertEquals(0, response.getProcessed());
        assertEquals(0, response.getFailed());
        assertEquals(0, response.getTotal());
        assertEquals(0.0, response.getTimeSpentSeconds());
        assertEquals(1, response.getChunkNumber()); // Default chunk number
        assertNotNull(response.getDetails());
        assertTrue(response.getDetails().isEmpty());
    }

    @Test
    void testConstructorWithChunkNumber() {
        TrapperResponse response = new TrapperResponse(5);
        assertEquals(5, response.getChunkNumber());
        // Other fields should be default
        assertEquals(0, response.getProcessed());
        assertTrue(response.getDetails().isEmpty());
    }

    @Test
    void testParseZabbixResponseInfo_valid() {
        String info = "processed: 10; failed: 1; total: 11; seconds spent: 0.00123";
        TrapperResponse.ParsedInfo parsed = TrapperResponse.parseZabbixResponseInfo(info);
        assertEquals(10, parsed.processed);
        assertEquals(1, parsed.failed);
        assertEquals(11, parsed.total);
        assertEquals(0.00123, parsed.timeSpentSeconds);
    }

    @Test
    void testParseZabbixResponseInfo_valid_differentValues() {
        String info = "processed: 0; failed: 5; total: 5; seconds spent: 1.2";
        TrapperResponse.ParsedInfo parsed = TrapperResponse.parseZabbixResponseInfo(info);
        assertEquals(0, parsed.processed);
        assertEquals(5, parsed.failed);
        assertEquals(5, parsed.total);
        assertEquals(1.2, parsed.timeSpentSeconds);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "processed: ; failed: 1; total: 1; seconds spent: 0.1", // Missing value
            "processed: 1, failed: 1; total: 1; seconds spent: 0.1", // Comma instead of semicolon
            "processed: 1; failed: 1; total: 1; seconds_spent: 0.1", // Wrong keyword
            "invalid string format",
            "processed: Ten; failed: One; total: Eleven; seconds spent: ZeroPointOne" // Non-numeric
    })
    void testParseZabbixResponseInfo_invalidFormat_throwsException(String invalidInfo) {
        assertThrows(IllegalArgumentException.class, () -> TrapperResponse.parseZabbixResponseInfo(invalidInfo));
    }

    @Test
    void testParseZabbixResponseInfo_nullOrEmpty_throwsException() {
        assertThrows(IllegalArgumentException.class, () -> TrapperResponse.parseZabbixResponseInfo(null));
        assertThrows(IllegalArgumentException.class, () -> TrapperResponse.parseZabbixResponseInfo(""));
        assertThrows(IllegalArgumentException.class, () -> TrapperResponse.parseZabbixResponseInfo("  "));
    }

    @Test
    void testAddResponse_single() {
        TrapperResponse response = new TrapperResponse();
        Map<String, Object> zabbixJson = new HashMap<>();
        zabbixJson.put("info", "processed: 5; failed: 2; total: 7; seconds spent: 0.05");

        response.addResponse(zabbixJson, 1);

        assertEquals(5, response.getProcessed());
        assertEquals(2, response.getFailed());
        assertEquals(7, response.getTotal());
        assertEquals(0.05, response.getTimeSpentSeconds());
        assertEquals(1, response.getChunkNumber());
    }

    @Test
    void testAddResponse_multiple() {
        TrapperResponse response = new TrapperResponse();
        Map<String, Object> zabbixJson1 = Map.of("info", "processed: 5; failed: 2; total: 7; seconds spent: 0.05");
        Map<String, Object> zabbixJson2 = Map.of("info", "processed: 10; failed: 0; total: 10; seconds spent: 0.10");

        response.addResponse(zabbixJson1, 1);
        response.addResponse(zabbixJson2, 2); // Chunk number updates

        assertEquals(15, response.getProcessed()); // 5 + 10
        assertEquals(2, response.getFailed());    // 2 + 0
        assertEquals(17, response.getTotal());    // 7 + 10
        assertEquals(0.15, response.getTimeSpentSeconds(), 0.00001); // 0.05 + 0.10
        assertEquals(2, response.getChunkNumber()); // Last chunk number
    }

    @Test
    void testAddResponse_invalidJsonMap_throwsException() {
        TrapperResponse response = new TrapperResponse();
        Map<String, Object> badJson1 = new HashMap<>(); // Missing "info"
        Map<String, Object> badJson2 = Map.of("info", 123); // "info" not a string

        assertThrows(IllegalArgumentException.class, () -> response.addResponse(null, 1));
        assertThrows(IllegalArgumentException.class, () -> response.addResponse(badJson1, 1));
        assertThrows(IllegalArgumentException.class, () -> response.addResponse(badJson2, 1));
    }

    @Test
    void testSetAndGetDetails() {
        TrapperResponse response = new TrapperResponse();
        Map<Node, List<TrapperResponse>> details = new HashMap<>();
        Node node1 = new Node("host1", 10051);
        TrapperResponse detailResponse1 = new TrapperResponse(1);
        detailResponse1.addResponse(Map.of("info", "processed: 1; failed: 0; total: 1; seconds spent: 0.01"), 1);

        details.put(node1, Collections.singletonList(detailResponse1));

        response.setDetails(details);
        assertEquals(details, response.getDetails());
        assertFalse(response.getDetails().isEmpty());
        assertEquals(1, response.getDetails().get(node1).size());
    }

    @Test
    void testEqualsAndHashCode() {
        TrapperResponse r1 = new TrapperResponse(1);
        r1.addResponse(Map.of("info", "processed: 1; failed: 0; total: 1; seconds spent: 0.01"), 1);

        TrapperResponse r2 = new TrapperResponse(1);
        r2.addResponse(Map.of("info", "processed: 1; failed: 0; total: 1; seconds spent: 0.01"), 1);

        TrapperResponse r3 = new TrapperResponse(2); // Different chunk number
        r3.addResponse(Map.of("info", "processed: 1; failed: 0; total: 1; seconds spent: 0.01"), 2);

        TrapperResponse r4 = new TrapperResponse(1); // Different processed count
        r4.addResponse(Map.of("info", "processed: 2; failed: 0; total: 2; seconds spent: 0.01"), 1);

        // Reflexivity
        assertEquals(r1, r1);

        // Symmetry
        assertEquals(r1, r2);
        assertEquals(r2, r1);

        // Inequality
        assertNotEquals(r1, r3);
        assertNotEquals(r1, r4);

        // HashCode
        assertEquals(r1.hashCode(), r2.hashCode());
        assertNotEquals(r1.hashCode(), r3.hashCode());

        // Test with details
        Map<Node, List<TrapperResponse>> details1 = new HashMap<>();
        details1.put(new Node("n1", 10051), Collections.singletonList(new TrapperResponse(1)));
        r1.setDetails(details1);

        Map<Node, List<TrapperResponse>> details2 = new HashMap<>();
        details2.put(new Node("n1", 10051), Collections.singletonList(new TrapperResponse(1)));
        r2.setDetails(details2); // r2 now has same details as r1

        Map<Node, List<TrapperResponse>> details3 = new HashMap<>();
        details3.put(new Node("n2", 10051), Collections.singletonList(new TrapperResponse(1))); // Different node in details
        TrapperResponse r5 = new TrapperResponse(1);
        r5.addResponse(Map.of("info", "processed: 1; failed: 0; total: 1; seconds spent: 0.01"), 1);
        r5.setDetails(details3);


        assertEquals(r1, r2); // Should still be equal as details are equal
        assertEquals(r1.hashCode(), r2.hashCode());
        assertNotEquals(r1, r5); // r1 and r5 have different details

        // Test with null
        assertNotEquals(null, r1);
    }

    @Test
    void testToString() {
        TrapperResponse response = new TrapperResponse();
        response.addResponse(Map.of("info", "processed: 10; failed: 2; total: 12; seconds spent: 0.1234567"), 1);
        String str = response.toString();
        assertNotNull(str);
        assertTrue(str.contains("processed=10"));
        assertTrue(str.contains("failed=2"));
        assertTrue(str.contains("total=12"));
        assertTrue(str.contains("timeSpentSeconds=0.123457")); // Check formatting
        assertTrue(str.contains("chunkNumber=1"));
    }

    @Test
    void testParsedInfoToString() {
        TrapperResponse.ParsedInfo parsedInfo = new TrapperResponse.ParsedInfo(1,2,3,0.5);
        String str = parsedInfo.toString();
        assertTrue(str.contains("processed=1"));
        assertTrue(str.contains("failed=2"));
        assertTrue(str.contains("total=3"));
        assertTrue(str.contains("timeSpentSeconds=0.5"));
    }
}
