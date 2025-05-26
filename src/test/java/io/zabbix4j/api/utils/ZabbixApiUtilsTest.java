package io.zabbix4j.api.utils;

import org.json.JSONArray; // For hidePrivate tests if JSONObjects/Arrays are part of map values
import org.json.JSONObject; // For hidePrivate tests
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse; // For hidePrivate tests

/**
 * Unit tests for the {@link ZabbixApiUtils} class.
 *
 * @author CSJ
 */
class ZabbixApiUtilsTest {

    @Nested
    @DisplayName("checkUrl(String url) Tests")
    class CheckUrlTests {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"  "}) // Blank string
        @DisplayName("checkUrl should throw IllegalArgumentException for null, empty, or blank URL")
        void testCheckUrl_NullEmptyOrBlank_ThrowsException(String invalidUrl) {
            Exception e = assertThrows(IllegalArgumentException.class, () -> ZabbixApiUtils.checkUrl(invalidUrl));
            assertEquals("URL cannot be null or empty.", e.getMessage());
        }

        @Test
        @DisplayName("checkUrl should return correct URL if already well-formed")
        void testCheckUrl_AlreadyCorrect() {
            String url = "http://server/zabbix/api_jsonrpc.php";
            assertEquals(url, ZabbixApiUtils.checkUrl(url));
            String httpsUrl = "https://server.domain.com/api_jsonrpc.php";
            assertEquals(httpsUrl, ZabbixApiUtils.checkUrl(httpsUrl));
        }

        @Test
        @DisplayName("checkUrl should prepend 'http://' if scheme is missing")
        void testCheckUrl_MissingScheme() {
            assertEquals("http://server/zabbix/api_jsonrpc.php", ZabbixApiUtils.checkUrl("server/zabbix/api_jsonrpc.php"));
            assertEquals("http://server.domain/api_jsonrpc.php", ZabbixApiUtils.checkUrl("server.domain/api_jsonrpc.php"));
        }

        @Test
        @DisplayName("checkUrl should keep 'https://' if scheme is present")
        void testCheckUrl_HttpsSchemePresent() {
            assertEquals("https://server/secure/api_jsonrpc.php", ZabbixApiUtils.checkUrl("https://server/secure/api_jsonrpc.php"));
        }

        @Test
        @DisplayName("checkUrl should append '/api_jsonrpc.php' if path is missing")
        void testCheckUrl_MissingPathSuffix() {
            assertEquals("http://server/zabbix/api_jsonrpc.php", ZabbixApiUtils.checkUrl("http://server/zabbix"));
        }

        @Test
        @DisplayName("checkUrl should append 'api_jsonrpc.php' if path ends with slash")
        void testCheckUrl_EndsWithSlash() {
            assertEquals("http://server/zabbix/api_jsonrpc.php", ZabbixApiUtils.checkUrl("http://server/zabbix/"));
        }

        @Test
        @DisplayName("checkUrl should handle URL that is just a server name")
        void testCheckUrl_ServerNameOnly() {
            assertEquals("http://zabbixserver/api_jsonrpc.php", ZabbixApiUtils.checkUrl("zabbixserver"));
        }
        
        @Test
        @DisplayName("checkUrl should handle URL that is just a server name with trailing slash")
        void testCheckUrl_ServerNameOnlyWithSlash() {
            assertEquals("http://zabbixserver/api_jsonrpc.php", ZabbixApiUtils.checkUrl("zabbixserver/"));
        }

        @Test
        @DisplayName("checkUrl should handle URL with port number")
        void testCheckUrl_WithPort() {
            assertEquals("http://server:8080/api_jsonrpc.php", ZabbixApiUtils.checkUrl("server:8080"));
            assertEquals("https://server.domain:4433/zabbix/api_jsonrpc.php", ZabbixApiUtils.checkUrl("https://server.domain:4433/zabbix/"));
        }
    }

    @Nested
    @DisplayName("maskSecret(String secret, int showLen) Tests")
    class MaskSecretTests {
        private final String MASK = ZabbixApiUtils.HIDING_MASK; // "********"

        @Test
        @DisplayName("maskSecret with null secret should return null")
        void testMaskSecret_NullInput() {
            assertNull(ZabbixApiUtils.maskSecret(null, 4));
        }

        @Test
        @DisplayName("maskSecret with empty secret should return the mask")
        void testMaskSecret_EmptyInput() {
            assertEquals(MASK, ZabbixApiUtils.maskSecret("", 4));
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 1, 2, 3, 4}) // showLen values
        @DisplayName("maskSecret with secret too short should return the mask")
        void testMaskSecret_SecretTooShort(int showLen) {
            // MASK length is 8. If secret.length() <= 8 + 2 * showLen
            String shortSecret = "secret"; // length 6
            if (6 <= MASK.length() + 2 * showLen || showLen == 0) { // simplified condition for this specific test
                 assertEquals(MASK, ZabbixApiUtils.maskSecret(shortSecret, showLen), "Secret '" + shortSecret + "' with showLen=" + showLen);
            }

            String slightlyLonger = "123456789"; // length 9
            if (9 <= MASK.length() + 2 * showLen || showLen == 0) {
                 assertEquals(MASK, ZabbixApiUtils.maskSecret(slightlyLonger, showLen), "Secret '" + slightlyLonger + "' with showLen=" + showLen);
            }
        }
        
        @Test
        @DisplayName("maskSecret with showLen = 0 should return the mask")
        void testMaskSecret_ShowLenZero() {
            assertEquals(MASK, ZabbixApiUtils.maskSecret("a_very_long_secret_token", 0));
        }

        @Test
        @DisplayName("maskSecret with typical secret and showLen = 4")
        void testMaskSecret_TypicalCase() {
            String secret = "a_very_long_secret_token_that_is_quite_long"; // length 44
            String expected = "a_ve" + MASK + "long";
            assertEquals(expected, ZabbixApiUtils.maskSecret(secret, 4));
        }
        
        @Test
        @DisplayName("maskSecret with typical secret (32 chars) and showLen = 4")
        void testMaskSecret_32CharToken() {
            String secret = "0123456789abcdef0123456789abcdef"; // length 32
            String expected = "0123" + MASK + "cdef";
            assertEquals(expected, ZabbixApiUtils.maskSecret(secret, 4));
        }


        @Test
        @DisplayName("maskSecret with showLen large enough that no masking occurs (or minimal)")
        void testMaskSecret_ShowLenTooLarge() {
            String secret = "short_token"; // length 11
            // if showLen * 2 + MASK.length() >= secret.length(), it should return MASK
            // showLen = 2: 2*2 + 8 = 12. 11 <= 12, so returns MASK
            assertEquals(MASK, ZabbixApiUtils.maskSecret(secret, 2));
            // showLen = 1: 2*1 + 8 = 10. 11 > 10, so it masks
            assertEquals("s" + MASK + "n", ZabbixApiUtils.maskSecret(secret, 1));
        }
    }

    @Nested
    @DisplayName("hidePrivate(Map<String, Object> data, Map<String, String> fieldsToHideAndRegex) Tests")
    class HidePrivateTests {

        @Test
        @DisplayName("hidePrivate with null input map should return null")
        void testHidePrivate_NullInput() {
            assertNull(ZabbixApiUtils.hidePrivate(null, ZabbixApiUtils.DEFAULT_PRIVATE_FIELDS));
        }

        @Test
        @DisplayName("hidePrivate with empty input map should return an empty map")
        void testHidePrivate_EmptyInput() {
            Map<String, Object> emptyMap = Collections.emptyMap();
            Map<String, Object> result = ZabbixApiUtils.hidePrivate(emptyMap, ZabbixApiUtils.DEFAULT_PRIVATE_FIELDS);
            assertTrue(result.isEmpty(), "Result should be an empty map.");
            assertNotEquals(System.identityHashCode(emptyMap), System.identityHashCode(result), "Should be a new map instance (deep copy behavior).");
        }

        @Test
        @DisplayName("hidePrivate should mask sensitive field using DEFAULT_PRIVATE_FIELDS")
        void testHidePrivate_SimpleMap_DefaultRules_Token() {
            Map<String, Object> data = new HashMap<>();
            data.put("user", "admin");
            data.put("token", "this_is_a_secret_token_value");
            data.put("result", "this_is_a_32_char_hex_result00"); // 32 hex chars

            Map<String, Object> result = ZabbixApiUtils.hidePrivate(data, ZabbixApiUtils.DEFAULT_PRIVATE_FIELDS);

            assertEquals("admin", result.get("user"));
            assertEquals(ZabbixApiUtils.HIDING_MASK, result.get("token"));
            assertEquals(ZabbixApiUtils.HIDING_MASK, result.get("result")); // Default regex for "result" is ^[a-fA-F0-9]{32}$
        }
        
        @Test
        @DisplayName("hidePrivate should not mask 'result' if it does not match default hex regex")
        void testHidePrivate_ResultNotMatchingDefaultRegex() {
            Map<String, Object> data = new HashMap<>();
            data.put("result", "this is a normal result string, not a token");
            Map<String, Object> result = ZabbixApiUtils.hidePrivate(data, ZabbixApiUtils.DEFAULT_PRIVATE_FIELDS);
            assertEquals("this is a normal result string, not a token", result.get("result"));
        }


        @Test
        @DisplayName("hidePrivate should handle nested maps")
        void testHidePrivate_NestedMap() {
            Map<String, Object> nestedData = new HashMap<>();
            nestedData.put("sessionid", "nested_session_id_secret");
            nestedData.put("other_info", "public");

            Map<String, Object> data = new HashMap<>();
            data.put("auth_details", nestedData);
            data.put("password", "top_level_password");

            Map<String, Object> result = ZabbixApiUtils.hidePrivate(data, ZabbixApiUtils.DEFAULT_PRIVATE_FIELDS);

            assertEquals(ZabbixApiUtils.HIDING_MASK, result.get("password"));
            assertTrue(result.get("auth_details") instanceof Map, "Nested structure should be preserved.");
            Map<String, Object> resultNested = (Map<String, Object>) result.get("auth_details");
            assertEquals(ZabbixApiUtils.HIDING_MASK, resultNested.get("sessionid"));
            assertEquals("public", resultNested.get("other_info"));
        }

        @Test
        @DisplayName("hidePrivate should handle list of strings with matching key (singular form)")
        void testHidePrivate_ListOfStrings_MatchingKeySingular() {
            Map<String, Object> data = new HashMap<>();
            // "result" key in DEFAULT_PRIVATE_FIELDS has regex "^[a-fA-F0-9]{32}$"
            // "results" (plural) should trigger masking for items in the list matching that regex
            List<String> stringList = Arrays.asList("token1_is_32_chars_0123456789ab", "not_a_token", "token2_is_32_chars_cdef01234567");
            data.put("results", stringList); // Plural key

            Map<String, String> customRules = new HashMap<>(ZabbixApiUtils.DEFAULT_PRIVATE_FIELDS);
            // Ensure "result" rule is present
            assertTrue(customRules.containsKey("result"));


            Map<String, Object> result = ZabbixApiUtils.hidePrivate(data, customRules);

            assertTrue(result.get("results") instanceof List, "List structure should be preserved.");
            List<?> resultList = (List<?>) result.get("results");
            assertEquals(3, resultList.size());
            assertEquals(ZabbixApiUtils.HIDING_MASK, resultList.get(0));
            assertEquals("not_a_token", resultList.get(1));
            assertEquals(ZabbixApiUtils.HIDING_MASK, resultList.get(2));
        }
        
        @Test
        @DisplayName("hidePrivate should handle list of strings with direct matching key")
        void testHidePrivate_ListOfStrings_DirectMatchingKey() {
            Map<String, Object> data = new HashMap<>();
            List<String> stringList = Arrays.asList("secret1", "public_data", "secret2");
            data.put("tokens", stringList); 

            Map<String, String> customRules = new HashMap<>();
            customRules.put("tokens", ".+"); // Rule for the list key itself to mask all string items

            Map<String, Object> result = ZabbixApiUtils.hidePrivate(data, customRules);

            assertTrue(result.get("tokens") instanceof List, "List structure should be preserved.");
            List<?> resultList = (List<?>) result.get("tokens");
            assertEquals(3, resultList.size());
            assertEquals(ZabbixApiUtils.HIDING_MASK, resultList.get(0));
            assertEquals(ZabbixApiUtils.HIDING_MASK, resultList.get(1)); // Masked due to ".+"
            assertEquals(ZabbixApiUtils.HIDING_MASK, resultList.get(2));
        }


        @Test
        @DisplayName("hidePrivate should handle list of maps with sensitive fields")
        void testHidePrivate_ListOfMaps() {
            Map<String, Object> mapInList1 = new HashMap<>();
            mapInList1.put("passwd", "pass1_secret");
            mapInList1.put("info", "info1");

            Map<String, Object> mapInList2 = new HashMap<>();
            mapInList2.put("auth", "auth_token_secret");
            mapInList2.put("info", "info2");

            List<Map<String, Object>> mapList = Arrays.asList(mapInList1, mapInList2);
            Map<String, Object> data = new HashMap<>();
            data.put("user_credentials", mapList);

            Map<String, Object> result = ZabbixApiUtils.hidePrivate(data, ZabbixApiUtils.DEFAULT_PRIVATE_FIELDS);

            assertTrue(result.get("user_credentials") instanceof List, "List of maps structure preserved.");
            List<?> resultList = (List<?>) result.get("user_credentials");
            assertEquals(2, resultList.size());

            assertTrue(resultList.get(0) instanceof Map);
            Map<String, Object> resultMap1 = (Map<String, Object>) resultList.get(0);
            assertEquals(ZabbixApiUtils.HIDING_MASK, resultMap1.get("passwd"));
            assertEquals("info1", resultMap1.get("info"));

            assertTrue(resultList.get(1) instanceof Map);
            Map<String, Object> resultMap2 = (Map<String, Object>) resultList.get(1);
            assertEquals(ZabbixApiUtils.HIDING_MASK, resultMap2.get("auth"));
            assertEquals("info2", resultMap2.get("info"));
        }

        @Test
        @DisplayName("hidePrivate should match keys case-insensitively")
        void testHidePrivate_CaseInsensitiveKeys() {
            Map<String, Object> data = new HashMap<>();
            data.put("TOKEN", "case_insensitive_token_match"); // Uppercase key
            data.put("Password", "another_secret_pwd");

            Map<String, Object> result = ZabbixApiUtils.hidePrivate(data, ZabbixApiUtils.DEFAULT_PRIVATE_FIELDS);
            assertEquals(ZabbixApiUtils.HIDING_MASK, result.get("TOKEN"));
            assertEquals(ZabbixApiUtils.HIDING_MASK, result.get("Password"));
        }

        @Test
        @DisplayName("hidePrivate should not modify the original map (deep copy)")
        void testHidePrivate_NoModifyOriginal() {
            Map<String, Object> originalData = new HashMap<>();
            originalData.put("token", "original_secret_token");
            Map<String, Object> nestedOriginal = new HashMap<>();
            nestedOriginal.put("passwd", "nested_original_passwd");
            originalData.put("details", nestedOriginal);

            // Create a manual deep copy for comparison of content before hidePrivate
            Map<String, Object> dataToPass = new HashMap<>();
            dataToPass.put("token", "original_secret_token");
            Map<String, Object> nestedToPass = new HashMap<>();
            nestedToPass.put("passwd", "nested_original_passwd");
            dataToPass.put("details", nestedToPass);


            Map<String, Object> result = ZabbixApiUtils.hidePrivate(dataToPass, ZabbixApiUtils.DEFAULT_PRIVATE_FIELDS);

            // Check original map is untouched
            assertEquals("original_secret_token", originalData.get("token"));
            assertTrue(originalData.get("details") instanceof Map);
            assertEquals("nested_original_passwd", ((Map<?,?>)originalData.get("details")).get("passwd"));

            // Check passed map (which hidePrivate should have deep copied internally before modifying)
            // This test verifies that the input 'dataToPass' to hidePrivate is not modified if hidePrivate makes its own copy.
            // The current ZabbixApiUtils.hidePrivate makes a new result map, so dataToPass itself is not modified.
            assertEquals("original_secret_token", dataToPass.get("token"));
            assertEquals("nested_original_passwd", ((Map<?,?>)dataToPass.get("details")).get("passwd"));


            // Check result map
            assertEquals(ZabbixApiUtils.HIDING_MASK, result.get("token"));
            assertTrue(result.get("details") instanceof Map);
            assertEquals(ZabbixApiUtils.HIDING_MASK, ((Map<?,?>)result.get("details")).get("passwd"));
        }

        @Test
        @DisplayName("hidePrivate with custom fieldsToHideAndRegex")
        void testHidePrivate_CustomRules() {
            Map<String, Object> data = new HashMap<>();
            data.put("apiKey", "custom_api_key_value_secret");
            data.put("secretField", "another_custom_secret");
            data.put("token", "standard_token_should_not_be_masked_by_custom");

            Map<String, String> customRules = new HashMap<>();
            customRules.put("apikey", ".+"); // Match "apiKey" (case-insensitive)
            customRules.put("secretfield", "^another_.+$"); // Match "secretField" with specific regex

            Map<String, Object> result = ZabbixApiUtils.hidePrivate(data, customRules);

            assertEquals(ZabbixApiUtils.HIDING_MASK, result.get("apiKey"));
            assertEquals(ZabbixApiUtils.HIDING_MASK, result.get("secretField"));
            assertEquals("standard_token_should_not_be_masked_by_custom", result.get("token"),
                         "Fields not in custom rules (or not matching their regex) should remain unchanged.");
        }

        @Test
        @DisplayName("hidePrivate should not mask field if value does not match regex")
        void testHidePrivate_ValueNotMatchingRegex() {
            Map<String, Object> data = new HashMap<>();
            data.put("result", "this-is-not-hex-so-no-mask"); // Default "result" regex is ^[a-fA-F0-9]{32}$

            Map<String, Object> result = ZabbixApiUtils.hidePrivate(data, ZabbixApiUtils.DEFAULT_PRIVATE_FIELDS);
            assertEquals("this-is-not-hex-so-no-mask", result.get("result"));
        }

        @Test
        @DisplayName("hidePrivate should not affect non-string values for sensitive keys")
        void testHidePrivate_NonStringValuesForSensitiveKeys() {
            Map<String, Object> data = new HashMap<>();
            data.put("token", 12345); // Integer value for "token"
            data.put("password", true); // Boolean value for "password"
            List<Integer> numberList = Arrays.asList(1,2,3);
            data.put("results", numberList); // List of Integers for "results"

            Map<String, Object> result = ZabbixApiUtils.hidePrivate(data, ZabbixApiUtils.DEFAULT_PRIVATE_FIELDS);

            assertEquals(12345, result.get("token"), "Integer value should not be masked.");
            assertEquals(true, result.get("password"), "Boolean value should not be masked.");
            assertEquals(numberList, result.get("results"), "List of non-strings should not be masked.");
        }
        
        @Test
        @DisplayName("hidePrivate should use DEFAULT_PRIVATE_FIELDS if custom rules map is null or empty")
        void testHidePrivate_NullOrEmptyCustomRules_UsesDefaults() {
            Map<String, Object> data = new HashMap<>();
            data.put("token", "secret_token_for_default_rules");

            // Test with null custom rules
            Map<String, Object> resultNullRules = ZabbixApiUtils.hidePrivate(data, null);
            assertEquals(ZabbixApiUtils.HIDING_MASK, resultNullRules.get("token"), "Should use default rules when custom rules are null.");

            // Test with empty custom rules
            Map<String, Object> resultEmptyRules = ZabbixApiUtils.hidePrivate(data, Collections.emptyMap());
            assertEquals(ZabbixApiUtils.HIDING_MASK, resultEmptyRules.get("token"), "Should use default rules when custom rules are empty.");
        }

    }
}
