package io.zabbix4j.api.utils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ZabbixUtils}.
 * @author ShortRoundDev
 */
class ZabbixUtilsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @ParameterizedTest
    @CsvSource({
            "http://example.com/zabbix, http://example.com/zabbix/api_jsonrpc.php",
            "https://example.com, https://example.com/api_jsonrpc.php",
            "http://localhost/zabbix/, http://localhost/zabbix/api_jsonrpc.php", // Trailing slash
            "https://192.168.1.1/api_jsonrpc.php, https://192.168.1.1/api_jsonrpc.php", // Already correct
            "http://user:pass@host.com/zabbix, http://user:pass@host.com/zabbix/api_jsonrpc.php"
    })
    void testCheckUrl_valid(String inputUrl, String expectedUrl) {
        assertEquals(expectedUrl, ZabbixUtils.checkUrl(inputUrl));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "ftp://example.com", "example.com/zabbix"})
    void testCheckUrl_invalid(String invalidUrl) {
        assertThrows(IllegalArgumentException.class, () -> ZabbixUtils.checkUrl(invalidUrl));
    }

    @Test
    void testCheckUrl_null() {
        assertThrows(IllegalArgumentException.class, () -> ZabbixUtils.checkUrl(null));
    }

    @ParameterizedTest
    @CsvSource({
            "mysecretpassword, 2, my********rd",
            "short, 2, ********", // Too short to show ends
            "verylongsecrettoken, 4, very********oken",
            "password123, 0, ********", // ShowLen 0 means mask all
            "secure, 3, ********" // showLen * 2 >= length
    })
    void testMaskSecret(String secret, int showLen, String expectedMasked) {
        assertEquals(expectedMasked, ZabbixUtils.maskSecret(secret, showLen));
    }

    @Test
    void testMaskSecret_nullInput() {
        assertEquals(ZabbixUtils.HIDING_MASK, ZabbixUtils.maskSecret(null, 2));
    }

    @Test
    void testHidePrivate_simpleMap_directKeys() {
        Map<String, Object> data = new HashMap<>();
        data.put("username", "user");
        data.put("password", "secret123");
        data.put("token", "a_very_long_token_string_that_should_be_masked");
        data.put("auth", "another_long_auth_string_for_masking");
        data.put("sessionid", "session_id_value_to_mask");
        data.put("passwd", "userpass");
        data.put("secret", "a_secret_value");
        data.put("normal_key", "normal_value");

        Map<String, Object> originalDataCopy = objectMapper.convertValue(data, new TypeReference<HashMap<String, Object>>() {});

        Map<String, Object> hidden = ZabbixUtils.hidePrivate(data);

        assertNotSame(data, hidden, "Should return a new map instance (deep copy).");
        assertEquals(originalDataCopy, data, "Original map should not be modified."); // Verify original is unchanged

        assertEquals("user", hidden.get("username"));
        assertEquals(ZabbixUtils.HIDING_MASK, hidden.get("password"));
        assertEquals(ZabbixUtils.HIDING_MASK, hidden.get("token"));
        assertEquals(ZabbixUtils.HIDING_MASK, hidden.get("auth"));
        assertEquals(ZabbixUtils.HIDING_MASK, hidden.get("sessionid"));
        assertEquals(ZabbixUtils.HIDING_MASK, hidden.get("passwd"));
        assertEquals(ZabbixUtils.HIDING_MASK, hidden.get("secret"));
        assertEquals("normal_value", hidden.get("normal_key"));
    }

    @Test
    void testHidePrivate_sensitiveKeyNamePattern() {
        Map<String, Object> data = new HashMap<>();
        data.put("snmp_community", "public_community_string");
        data.put("SNMP_AUTH_PASSPHRASE", "authpass123");
        data.put("some_other_key", "value");
        data.put("mytokenkey", "tokenValue"); // Matches ".*token.*" in SENSITIVE_KEY_NAME_PATTERN

        Map<String, Object> hidden = ZabbixUtils.hidePrivate(data);
        assertEquals(ZabbixUtils.HIDING_MASK, hidden.get("snmp_community"));
        assertEquals(ZabbixUtils.HIDING_MASK, hidden.get("SNMP_AUTH_PASSPHRASE"));
        assertEquals("value", hidden.get("some_other_key"));
        assertEquals(ZabbixUtils.HIDING_MASK, hidden.get("mytokenkey"));
    }

    @Test
    void testHidePrivate_credentialsField() {
        Map<String, Object> data = new HashMap<>();
        // The SENSITIVE_FIELD_PATTERNS for "credentials" is:
        // Pattern.compile(".*(password|secret|token|key|passwd).*", Pattern.CASE_INSENSITIVE)
        // This means if the key is "credentials", its string value will be masked if the value matches this.
        // However, the primary logic in hidePrivate masks based on key first.
        // If "credentials" itself is in SENSITIVE_FIELD_PATTERNS, its value should be masked
        // if the *value* matches its associated pattern.

        data.put("credentials", "this_is_a_password_value"); // Value matches pattern for "credentials"
        data.put("other_credentials", "some_other_value"); // Key doesn't match "credentials"

        Map<String, Object> hidden = ZabbixUtils.hidePrivate(data);
        assertEquals(ZabbixUtils.HIDING_MASK, hidden.get("credentials"));
        assertEquals("some_other_value", hidden.get("other_credentials")); // Not masked by key
    }


    @Test
    void testHidePrivate_nestedMap() {
        Map<String, Object> nested = new HashMap<>();
        nested.put("service_token", "nested_token_value");
        nested.put("config_value", 123);

        Map<String, Object> data = new HashMap<>();
        data.put("main_password", "main_pass");
        data.put("nested_config", nested);
        data.put("user", "test_user");

        Map<String, Object> hidden = ZabbixUtils.hidePrivate(data);

        assertEquals(ZabbixUtils.HIDING_MASK, hidden.get("main_password"));
        assertEquals("test_user", hidden.get("user"));
        assertNotNull(hidden.get("nested_config"));
        assertTrue(hidden.get("nested_config") instanceof Map);

        @SuppressWarnings("unchecked")
        Map<String, Object> hiddenNested = (Map<String, Object>) hidden.get("nested_config");
        assertEquals(ZabbixUtils.HIDING_MASK, hiddenNested.get("service_token"));
        assertEquals(123, hiddenNested.get("config_value"));

        // Ensure original nested map is not modified
        assertEquals("nested_token_value", nested.get("service_token"));
    }

    @Test
    void testHidePrivate_listContainingMaps() {
        Map<String, Object> item1 = new HashMap<>();
        item1.put("api_key", "key_for_item1");
        item1.put("id", 1);

        Map<String, Object> item2 = new HashMap<>();
        item2.put("password", "pass_for_item2");
        item2.put("id", 2);

        List<Object> list = Arrays.asList(item1, "string_in_list", item2);

        Map<String, Object> data = new HashMap<>();
        data.put("items", list);
        data.put("top_secret", "shhh");

        Map<String, Object> hidden = ZabbixUtils.hidePrivate(data);
        assertEquals(ZabbixUtils.HIDING_MASK, hidden.get("top_secret"));

        @SuppressWarnings("unchecked")
        List<Object> hiddenList = (List<Object>) hidden.get("items");
        assertNotNull(hiddenList);
        assertEquals(3, hiddenList.size());

        assertTrue(hiddenList.get(0) instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> hiddenItem1 = (Map<String, Object>) hiddenList.get(0);
        // "api_key" should be masked by SENSITIVE_KEY_NAME_PATTERN (.*key.*)
        assertEquals(ZabbixUtils.HIDING_MASK, hiddenItem1.get("api_key"));
        assertEquals(1, hiddenItem1.get("id"));

        assertEquals("string_in_list", hiddenList.get(1)); // Plain strings in list are not masked by default

        assertTrue(hiddenList.get(2) instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> hiddenItem2 = (Map<String, Object>) hiddenList.get(2);
        assertEquals(ZabbixUtils.HIDING_MASK, hiddenItem2.get("password"));
        assertEquals(2, hiddenItem2.get("id"));

        // Ensure original list items are not modified
        assertEquals("key_for_item1", item1.get("api_key"));
        assertEquals("pass_for_item2", item2.get("password"));
    }
    
    @Test
    void testHidePrivate_listContainingSensitiveStrings_underSensitiveKey() {
        // This tests if a list of strings itself is replaced if the key holding the list is sensitive.
        // The current hidePrivate logic primarily masks string *values* of sensitive keys,
        // or string values of keys matching SENSITIVE_KEY_NAME_PATTERN.
        // It does not currently replace entire lists or mask individual plain strings within a list
        // UNLESS that list is the direct value of a key like "password".
        // Example: data.put("password", Arrays.asList("secret1", "secret2")) -> this won't be masked
        // because `value instanceof String` check fails.
        // This test confirms current behavior.
        Map<String, Object> data = new HashMap<>();
        List<String> secretsList = Arrays.asList("secret_A", "secret_B");
        data.put("secrets", secretsList); // "secrets" matches SENSITIVE_KEY_NAME_PATTERN (.*secret.*)
        data.put("auth_tokens", Arrays.asList("token1", "token2")); // "auth_tokens" matches SENSITIVE_KEY_NAME_PATTERN

        Map<String, Object> hidden = ZabbixUtils.hidePrivate(data);

        // Based on current ZabbixUtils.hidePrivate:
        // if (currentValue instanceof String) { ... }
        // else if (!masked && currentValue instanceof Map) { ... }
        // else if (!masked && currentValue instanceof List) { process list items IF THEY ARE MAPS }
        // So, the list itself under "secrets" or "auth_tokens" will not be replaced by HIDING_MASK.
        // Individual string elements inside are also not masked because they are not direct values of sensitive keys.
        assertEquals(secretsList, hidden.get("secrets"));
        assertEquals(Arrays.asList("token1", "token2"), hidden.get("auth_tokens"));
    }


    @Test
    void testHidePrivate_emptyMap() {
        Map<String, Object> data = new HashMap<>();
        Map<String, Object> hidden = ZabbixUtils.hidePrivate(data);
        assertTrue(hidden.isEmpty());
        assertNotSame(data, hidden);
    }

    @Test
    void testHidePrivate_nullInput() {
        Map<String, Object> hidden = ZabbixUtils.hidePrivate(null);
        assertNotNull(hidden);
        assertTrue(hidden.isEmpty());
    }

    @Test
    void testHidePrivate_noSensitiveKeys() {
        Map<String, Object> data = new HashMap<>();
        data.put("key1", "value1");
        data.put("key2", 123);
        Map<String, Object> nested = new HashMap<>();
        nested.put("key3", "value3");
        data.put("key4", nested);

        Map<String, Object> originalDataCopy = objectMapper.convertValue(data, new TypeReference<HashMap<String, Object>>() {});
        Map<String, Object> hidden = ZabbixUtils.hidePrivate(data);

        assertEquals(originalDataCopy, hidden, "Map with no sensitive keys should remain unchanged content-wise.");
        assertNotSame(data, hidden, "Should still be a deep copy.");
    }
}
