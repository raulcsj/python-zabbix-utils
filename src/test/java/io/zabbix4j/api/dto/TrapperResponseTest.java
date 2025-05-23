package io.zabbix4j.api.dto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TrapperResponse}.
 * @author ShortRoundDev
 */
class TrapperResponseTest {

    @Test
    void testConstructorAndGetters() {
        TrapperResponse response = new TrapperResponse(10, 2, 12, 0.005, 1);
        assertEquals(10, response.getProcessed());
        assertEquals(2, response.getFailed());
        assertEquals(12, response.getTotal());
        assertEquals(0.005, response.getTimeSpentSeconds(), 0.00001);
        assertEquals(1, response.getChunkIndex());
    }

    @Test
    void testConstructorAndGetters_nullChunkIndex() {
        TrapperResponse response = new TrapperResponse(5, 0, 5, 0.002, null);
        assertEquals(5, response.getProcessed());
        assertEquals(0, response.getFailed());
        assertEquals(5, response.getTotal());
        assertEquals(0.002, response.getTimeSpentSeconds(), 0.00001);
        assertNull(response.getChunkIndex());
    }

    @ParameterizedTest
    @CsvSource({
            "'processed: 10; failed: 2; total: 12; seconds spent: 0.00123', 1, 10, 2, 12, 0.00123",
            "'Processed 5 Failed 0 Total 5 Seconds spent 0.00045', 2, 5, 0, 5, 0.00045", // Old format
            "'processed: 1; failed: 0; total: 1; seconds spent: 0.000035', null, 1, 0, 1, 0.000035"
    })
    void testParseInfoString_valid(String info, Integer chunkIndex, int expectedProcessed, int expectedFailed, int expectedTotal, double expectedTime) {
        TrapperResponse response = TrapperResponse.parseInfoString(info, chunkIndex);
        assertEquals(expectedProcessed, response.getProcessed());
        assertEquals(expectedFailed, response.getFailed());
        assertEquals(expectedTotal, response.getTotal());
        assertEquals(expectedTime, response.getTimeSpentSeconds(), 0.0000001);
        assertEquals(chunkIndex, response.getChunkIndex());
    }

    @ParameterizedTest
    @CsvSource({
            "'processed: 10; failed: 2; total: 12; seconds spent: 0.00123', 10, 2, 12, 0.00123",
            "'Processed 5 Failed 0 Total 5 Seconds spent 0.00045', 5, 0, 5, 0.00045", // Old format
    })
    void testParseInfoString_valid_noChunkIndexMethod(String info, int expectedProcessed, int expectedFailed, int expectedTotal, double expectedTime) {
        TrapperResponse response = TrapperResponse.parseInfoString(info); // Call the overload
        assertEquals(expectedProcessed, response.getProcessed());
        assertEquals(expectedFailed, response.getFailed());
        assertEquals(expectedTotal, response.getTotal());
        assertEquals(expectedTime, response.getTimeSpentSeconds(), 0.0000001);
        assertNull(response.getChunkIndex()); // Should be null
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "invalid format",
            "processed: 10; failed: 2; total: 12;", // Missing time
            "p: 1; f: 0; t: 1; s: 0.1", // Different keywords
            ""
    })
    void testParseInfoString_invalid(String invalidInfo) {
        assertThrows(IllegalArgumentException.class, () -> TrapperResponse.parseInfoString(invalidInfo, 1));
    }

    @Test
    void testParseInfoString_nullInfo() {
        assertThrows(IllegalArgumentException.class, () -> TrapperResponse.parseInfoString(null, 1));
    }

    @Test
    void testAdd() {
        TrapperResponse r1 = new TrapperResponse(10, 1, 11, 0.1, 1);
        TrapperResponse r2 = new TrapperResponse(5, 2, 7, 0.05, 2);
        TrapperResponse sum = r1.add(r2);

        assertEquals(15, sum.getProcessed());
        assertEquals(3, sum.getFailed());
        assertEquals(18, sum.getTotal());
        assertEquals(0.15, sum.getTimeSpentSeconds(), 0.00001);
        assertEquals(1, sum.getChunkIndex()); // r1's chunkIndex is preserved

        TrapperResponse r3 = new TrapperResponse(3, 0, 3, 0.02, null);
        TrapperResponse sum2 = r3.add(r2); // r3.chunkIndex is null
        assertEquals(2, sum2.getChunkIndex()); // r2's chunkIndex is used

        TrapperResponse sum3 = r1.add(null); // Adding null should return original
        assertEquals(r1, sum3);
    }

    @Test
    void testEqualsAndHashCode() {
        TrapperResponse r1 = new TrapperResponse(10, 1, 11, 0.1, 1);
        TrapperResponse r2 = new TrapperResponse(10, 1, 11, 0.1, 1);
        TrapperResponse r3 = new TrapperResponse(10, 1, 11, 0.1, 2); // Different chunkIndex
        TrapperResponse r4 = new TrapperResponse(5, 1, 11, 0.1, 1);  // Different processed
        TrapperResponse r5 = new TrapperResponse(10, 1, 11, 0.2, 1);  // Different time

        assertEquals(r1, r2);
        assertEquals(r1.hashCode(), r2.hashCode());

        assertNotEquals(r1, r3);
        assertNotEquals(r1, r4);
        assertNotEquals(r1, r5);
        assertNotEquals(r1, null);
        assertNotEquals(r1, new Object());
    }

    @Test
    void testToString() {
        TrapperResponse responseWithChunk = new TrapperResponse(10, 2, 12, 0.005, 1);
        String strWithChunk = responseWithChunk.toString();
        assertTrue(strWithChunk.contains("processed=10"));
        assertTrue(strWithChunk.contains("failed=2"));
        assertTrue(strWithChunk.contains("total=12"));
        assertTrue(strWithChunk.contains("timeSpentSeconds=0.005"));
        assertTrue(strWithChunk.contains("chunkIndex=1"));

        TrapperResponse responseNoChunk = new TrapperResponse(5, 0, 5, 0.002, null);
        String strNoChunk = responseNoChunk.toString();
        assertTrue(strNoChunk.contains("processed=5"));
        assertFalse(strNoChunk.contains("chunkIndex="));
    }
}
