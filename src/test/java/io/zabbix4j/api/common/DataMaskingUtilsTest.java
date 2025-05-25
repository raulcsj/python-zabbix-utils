package io.zabbix4j.api.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link DataMaskingUtils}.
 *
 * @author CSJ
 */
class DataMaskingUtilsTest {

    private static final String HIDING_MASK = ZabbixApiConstants.HIDING_MASK; // "********"

    @Test
    void testMaskSecret_longString_showLen4() {
        String secret = "thisIsAVeryLongSecretString"; // Length 28
        String expected = "this" + HIDING_MASK + "ring";
        assertEquals(expected, DataMaskingUtils.maskSecret(secret, 4));
        assertEquals(expected, DataMaskingUtils.maskSecret(secret)); // Test default showLen
    }

    @Test
    void testMaskSecret_shortString_showLen4() {
        // Length 15. Mask (8) + 4*2 (8) = 16. String is shorter.
        String secret = "shortSecret1234";
        assertEquals(HIDING_MASK, DataMaskingUtils.maskSecret(secret, 4));
        assertEquals(HIDING_MASK, DataMaskingUtils.maskSecret(secret));
    }

    @Test
    void testMaskSecret_exactLengthForMasking() {
        // Length 16. Mask (8) + 4*2 (8) = 16. String is not longer.
        String secret = "secret1234567890"; // Length 16
        assertEquals(HIDING_MASK, DataMaskingUtils.maskSecret(secret, 4));
    }

    @Test
    void testMaskSecret_stringSlightlyLongerThanMaskPlusShowLen() {
        // Length 17. Mask(8) + 4*2(8) = 16. 17 > 16.
        String secret = "longEnoughSecret1"; // length 17
        String expected = "long" + HIDING_MASK + "ret1";
        assertEquals(expected, DataMaskingUtils.maskSecret(secret, 4));
    }


    @Test
    void testMaskSecret_showLenZero() {
        String secret = "anySecretValue";
        assertEquals(HIDING_MASK, DataMaskingUtils.maskSecret(secret, 0));
    }

    @Test
    void testMaskSecret_showLenGreaterThanHalfString() {
        String secret = "shorty"; // Length 6
        // showLen = 4. HIDING_MASK.length (8) + 4*2 (8) = 16. 6 <= 16
        assertEquals(HIDING_MASK, DataMaskingUtils.maskSecret(secret, 4));
    }

    @Test
    void testMaskSecret_showLenEqualToHalfString() {
        String secret = "evensecret"; // Length 10
        // showLen = 5. HIDING_MASK.length (8) + 5*2 (10) = 18. 10 <= 18
        assertEquals(HIDING_MASK, DataMaskingUtils.maskSecret(secret, 5));
    }

    @Test
    void testMaskSecret_nullInput() {
        assertNull(DataMaskingUtils.maskSecret(null, 4));
        assertNull(DataMaskingUtils.maskSecret(null));
    }

    @Test
    void testMaskSecret_emptyInput() {
        assertEquals("", DataMaskingUtils.maskSecret("", 4));
        assertEquals("", DataMaskingUtils.maskSecret(""));
    }

    @Test
    void testMaskSecret_stringSameLengthAsMask() {
        String secret = "12345678"; // Length 8
        assertEquals(HIDING_MASK, DataMaskingUtils.maskSecret(secret, 1)); // 8 <= 8 + 2
        assertEquals(HIDING_MASK, DataMaskingUtils.maskSecret(secret, 0));
    }

    @Test
    void testHidePrivateFields_simpleMap() {
        Map<String, Object> data = new HashMap<>();
        data.put(ZabbixApiConstants.FIELD_TOKEN, "sensitiveToken123");
        data.put(ZabbixApiConstants.FIELD_PASSWORD, "mysecretpassword");
        data.put("normalField", "normalValue");

        Map<String, Object> originalData = new HashMap<>(data); // For checking original map modification

        Map<String, Object> maskedData = DataMaskingUtils.hidePrivateFields(data);

        assertNotSame(data, maskedData, "Should return a new map instance.");
        assertEquals(originalData, data, "Original map should not be modified."); // Check original map

        assertEquals(DataMaskingUtils.maskSecret("sensitiveToken123"), maskedData.get(ZabbixApiConstants.FIELD_TOKEN));
        assertEquals(DataMaskingUtils.maskSecret("mysecretpassword"), maskedData.get(ZabbixApiConstants.FIELD_PASSWORD));
        assertEquals("normalValue", maskedData.get("normalField"));
    }

    @Test
    void testHidePrivateFields_nestedMap() {
        Map<String, Object> nestedMap = new HashMap<>();
        nestedMap.put(ZabbixApiConstants.FIELD_PASSWD, "nestedPass");
        nestedMap.put("otherNested", "value");

        Map<String, Object> data = new HashMap<>();
        data.put("outerField", "outerValue");
        data.put("nested", nestedMap);
        data.put(ZabbixApiConstants.FIELD_AUTH, "topLevelAuthToken");


        Map<String, Object> originalData = new HashMap<>(data);
        originalData.put("nested", new HashMap<>(nestedMap)); // Deep copy for comparison

        Map<String, Object> maskedData = DataMaskingUtils.hidePrivateFields(data);

        assertEquals(originalData, data, "Original map should not be modified.");

        assertEquals("outerValue", maskedData.get("outerField"));
        assertEquals(DataMaskingUtils.maskSecret("topLevelAuthToken"), maskedData.get(ZabbixApiConstants.FIELD_AUTH));

        @SuppressWarnings("unchecked")
        Map<String, Object> maskedNested = (Map<String, Object>) maskedData.get("nested");
        assertNotNull(maskedNested);
        assertEquals(DataMaskingUtils.maskSecret("nestedPass"), maskedNested.get(ZabbixApiConstants.FIELD_PASSWD));
        assertEquals("value", maskedNested.get("otherNested"));
    }

    @Test
    void testHidePrivateFields_listWithMaps() {
        Map<String, Object> mapInList1 = new HashMap<>();
        mapInList1.put(ZabbixApiConstants.FIELD_SESSIONID, "sessionInList1");
        mapInList1.put("id", 1);

        Map<String, Object> mapInList2 = new HashMap<>();
        mapInList2.put(ZabbixApiConstants.FIELD_TOKEN, "tokenInList2");
        mapInList2.put("id", 2);

        List<Object> list = Arrays.asList(mapInList1, mapInList2, "normalStringInList");

        Map<String, Object> data = new HashMap<>();
        data.put("myList", list);

        Map<String, Object> originalData = new HashMap<>(data);
        // Manual deep copy of list and its maps for comparison
        originalData.put("myList", Arrays.asList(new HashMap<>(mapInList1), new HashMap<>(mapInList2), "normalStringInList"));


        Map<String, Object> maskedData = DataMaskingUtils.hidePrivateFields(data);
        assertEquals(originalData, data, "Original map should not be modified.");


        @SuppressWarnings("unchecked")
        List<Object> maskedList = (List<Object>) maskedData.get("myList");
        assertNotNull(maskedList);
        assertEquals(3, maskedList.size());

        @SuppressWarnings("unchecked")
        Map<String, Object> maskedMapInList1 = (Map<String, Object>) maskedList.get(0);
        assertEquals(DataMaskingUtils.maskSecret("sessionInList1"), maskedMapInList1.get(ZabbixApiConstants.FIELD_SESSIONID));
        assertEquals(1, maskedMapInList1.get("id"));

        @SuppressWarnings("unchecked")
        Map<String, Object> maskedMapInList2 = (Map<String, Object>) maskedList.get(1);
        assertEquals(DataMaskingUtils.maskSecret("tokenInList2"), maskedMapInList2.get(ZabbixApiConstants.FIELD_TOKEN));
        assertEquals(2, maskedMapInList2.get("id"));

        assertEquals("normalStringInList", maskedList.get(2));
    }

    @Test
    void testHidePrivateFields_resultFieldMasking() {
        Map<String, Object> data = new HashMap<>();
        data.put(ZabbixApiConstants.FIELD_RESULT, "1234567890abcdef1234567890abcdef"); // 32-char hex
        data.put("otherResult", "notAHexTokenResult123");
        data.put("anotherResult", "shortHex1234"); // Not 32 chars

        Map<String, Object> maskedData = DataMaskingUtils.hidePrivateFields(data);

        assertEquals(DataMaskingUtils.maskSecret("1234567890abcdef1234567890abcdef"), maskedData.get(ZabbixApiConstants.FIELD_RESULT));
        assertEquals("notAHexTokenResult123", maskedData.get("otherResult"));
        assertEquals("shortHex1234", maskedData.get("anotherResult"));
    }

    @Test
    void testHidePrivateFields_variousDataTypes() {
        Map<String, Object> data = new HashMap<>();
        data.put("integerField", 123);
        data.put("booleanField", true);
        data.put(ZabbixApiConstants.FIELD_PASSWORD, "pass123");
        data.put("nullField", null);

        Map<String, Object> maskedData = DataMaskingUtils.hidePrivateFields(data);

        assertEquals(123, maskedData.get("integerField"));
        assertEquals(true, maskedData.get("booleanField"));
        assertEquals(DataMaskingUtils.maskSecret("pass123"), maskedData.get(ZabbixApiConstants.FIELD_PASSWORD));
        assertNull(maskedData.get("nullField"));
    }

    @Test
    void testHidePrivateFields_emptyMap() {
        Map<String, Object> data = Collections.emptyMap();
        Map<String, Object> maskedData = DataMaskingUtils.hidePrivateFields(data);
        assertTrue(maskedData.isEmpty());
        assertNotSame(data, maskedData);
    }

    @Test
    void testHidePrivateFields_nullMap() {
        Map<String, Object> maskedData = DataMaskingUtils.hidePrivateFields(null);
        assertNotNull(maskedData);
        assertTrue(maskedData.isEmpty());
    }

    @Test
    void testHidePrivateFieldsInJsonString_throwsUnsupportedOperationException() {
        String jsonString = "{\"token\": \"secretToken\"}";
        UnsupportedOperationException exception = assertThrows(
                UnsupportedOperationException.class,
                () -> DataMaskingUtils.hidePrivateFieldsInJsonString(jsonString)
        );
        assertTrue(exception.getMessage().contains("JSON parsing and serialization for data masking is not yet implemented"));
    }

    @Test
    void testPrivateConstructor() throws NoSuchMethodException {
        Constructor<DataMaskingUtils> constructor = DataMaskingUtils.class.getDeclaredConstructor();
        assertTrue(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers()));
        constructor.setAccessible(true);
        assertThrows(java.lang.reflect.InvocationTargetException.class, () -> {
            try {
                constructor.newInstance();
            } catch (java.lang.reflect.InvocationTargetException e) {
                if (e.getCause() instanceof UnsupportedOperationException) {
                    throw e; // Re-throw to be caught by assertThrows
                }
                throw new RuntimeException("Unexpected cause", e.getCause());
            }
        });
    }
}
