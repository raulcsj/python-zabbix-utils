package io.zabbix4j.api.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Utility class for Zabbix API related helper functions.
 * This class contains static methods and cannot be instantiated.
 *
 * @author CSJ
 */
public final class ZabbixApiUtils {

    /**
     * The standard filename for the Zabbix JSON-RPC API endpoint.
     */
    public static final String JSONRPC_FILE = "api_jsonrpc.php";

    /**
     * The mask used to hide sensitive information.
     */
    public static final String HIDING_MASK = "********";

    /**
     * Default map of field names (case-insensitive keys) and their corresponding regex patterns
     * to identify sensitive information that should be masked.
     * Used by {@link #hidePrivate(Map, Map)} if specific fields are not provided.
     * Regexes are designed to match common token/password formats.
     */
    public static final Map<String, String> DEFAULT_PRIVATE_FIELDS;

    static {
        Map<String, String> privateFields = new HashMap<>();
        privateFields.put("token", ".+");          // Matches any non-empty string
        privateFields.put("auth", ".+");           // Matches any non-empty string
        privateFields.put("passwd", ".+");         // Matches any non-empty string
        privateFields.put("sessionid", ".+");      // Matches any non-empty string
        privateFields.put("password", ".+");       // Matches any non-empty string
        privateFields.put("current_passwd", ".+"); // Matches any non-empty string
        // Matches a typical 32-character hexadecimal string often used for session IDs or tokens
        privateFields.put("result", "^[a-fA-F0-9]{32}$");
        DEFAULT_PRIVATE_FIELDS = Collections.unmodifiableMap(privateFields);
    }

    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private ZabbixApiUtils() {
    }

    /**
     * Checks and corrects the format of a Zabbix API URL.
     * <p>
     * This method performs the following corrections:
     * <ul>
     *     <li>If the URL does not start with "http://" or "https://", it prepends "http://".</li>
     *     <li>If the URL does not end with "api_jsonrpc.php", it appends it. If the URL
     *         ends with a '/', "api_jsonrpc.php" is appended directly; otherwise, a '/' is
     *         added before "api_jsonrpc.php".</li>
     * </ul>
     *
     * @param url The Zabbix API URL string to check and correct.
     * @return The corrected and normalized Zabbix API URL string.
     * @throws IllegalArgumentException if the input {@code url} is null or empty.
     */
    public static String checkUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("URL cannot be null or empty.");
        }

        String correctedUrl = url.trim();

        // Ensure scheme is present
        if (!correctedUrl.startsWith("http://") && !correctedUrl.startsWith("https://")) {
            correctedUrl = "http://" + correctedUrl;
        }

        // Ensure JSON-RPC file path is present
        if (!correctedUrl.endsWith(JSONRPC_FILE)) {
            if (correctedUrl.endsWith("/")) {
                correctedUrl += JSONRPC_FILE;
            } else {
                correctedUrl += "/" + JSONRPC_FILE;
            }
        }
        return correctedUrl;
    }

    /**
     * Masks a secret string by replacing its middle part with a hiding mask.
     * If the secret is null, it's returned as is. If empty, the mask is returned.
     * If {@code showLen} is 0, or if the secret is too short to effectively mask
     * (i.e., its length is less than or equal to mask length + 2 * {@code showLen}),
     * the entire secret is replaced by the mask.
     *
     * @param secret  The secret string to mask.
     * @param showLen The number of characters to show at the beginning and end of the secret.
     * @return The masked secret string.
     */
    public static String maskSecret(String secret, int showLen) {
        if (secret == null) {
            return null;
        }
        if (secret.isEmpty()) {
            return HIDING_MASK;
        }

        int secretLen = secret.length();
        // Using HIDING_MASK.length() directly as maskLen
        if (showLen <= 0 || secretLen <= HIDING_MASK.length() + (2 * showLen)) {
            return HIDING_MASK;
        }

        return secret.substring(0, showLen) + HIDING_MASK + secret.substring(secretLen - showLen);
    }

    /**
     * Recursively hides sensitive information in a map structure (e.g., representing a JSON object).
     * This method creates a deep copy of the input map and modifies it.
     * The method uses {@link #DEFAULT_PRIVATE_FIELDS} if {@code fieldsToHideAndRegex} is null or empty,
     * however, the current signature requires {@code fieldsToHideAndRegex} to be provided.
     *
     * @param data                 The map containing data that might include sensitive fields.
     * @param fieldsToHideAndRegex A map where keys are field names (case-insensitive) to look for,
     *                             and values are regex patterns. If a field's value in {@code data}
     *                             matches the regex, it will be masked using {@link #maskSecret(String, int)}
     *                             with a default {@code showLen} of 4. This map must not be null.
     * @return A new map with sensitive fields masked, or the original data if not a map.
     * @throws IllegalArgumentException if {@code fieldsToHideAndRegex} is null (though this check can be
     *                                  removed if we strictly rely on {@link #DEFAULT_PRIVATE_FIELDS}
     *                                  as a fallback internally when it's null or empty).
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> hidePrivate(Map<String, Object> data, Map<String, String> fieldsToHideAndRegex) {
        if (data == null) {
            return null;
        }
         // As per current requirement, fieldsToHideAndRegex is provided. If it can be optional in future:
         // Map<String, String> effectiveFieldsToHide = (fieldsToHideAndRegex == null || fieldsToHideAndRegex.isEmpty())
         //                                           ? DEFAULT_PRIVATE_FIELDS
         //                                           : fieldsToHideAndRegex;
        if (fieldsToHideAndRegex == null) {
            // Or, if it's truly mandatory: throw new IllegalArgumentException("fieldsToHideAndRegex cannot be null.");
            // For now, let's assume it's mandatory as per current signature and Python example's direct use.
            // If it *were* optional and became empty, we'd use DEFAULT_PRIVATE_FIELDS.
            // If it's mandatory and *can* be empty, that implies no fields are to be hidden by custom rules.
            // Let's assume an empty map means "no custom rules, so don't mask anything beyond defaults if we had them"
            // or "no custom rules, so don't mask anything at all if defaults are not auto-applied here".
            // Given the prompt, it seems like `fieldsToHideAndRegex` is the authority.
            // If IT'S empty, nothing specific is hidden by IT.
            // The Python example implies `fields` (our fieldsToHideAndRegex) IS the set of rules.
            // So if it's empty, no rules apply from this parameter.
            // The DEFAULT_PRIVATE_FIELDS seems more like a global default the caller can choose to pass.
        }


        Map<String, String> effectiveRules = (fieldsToHideAndRegex == null || fieldsToHideAndRegex.isEmpty()) ? DEFAULT_PRIVATE_FIELDS : fieldsToHideAndRegex;


        if (effectiveRules.isEmpty()) {
             return (Map<String, Object>) deepCopy(data); // Return a deep copy if no fields to hide
        }

        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            String lowerKey = key.toLowerCase(); // For case-insensitive matching

            String regexPattern = effectiveRules.get(lowerKey);

            if (value instanceof String && regexPattern != null) {
                if (Pattern.matches(regexPattern, (String) value)) {
                    result.put(key, maskSecret((String) value, 4));
                } else {
                    result.put(key, value); // No regex match, keep original string
                }
            } else if (value instanceof Map) {
                result.put(key, hidePrivate((Map<String, Object>) value, effectiveRules));
            } else if (value instanceof List) {
                String listKeyCheck = lowerKey;
                String listItemsRegex = effectiveRules.get(listKeyCheck); // Check direct key first
                // Check for singular form (e.g. "results" key for "result" rule)
                if (listItemsRegex == null && lowerKey.endsWith("s") && lowerKey.length() > 1) {
                    String singularKey = lowerKey.substring(0, lowerKey.length() - 1);
                    listItemsRegex = effectiveRules.get(singularKey);
                }
                result.put(key, hideList((List<Object>) value, listItemsRegex, effectiveRules));
            } else {
                result.put(key, deepCopy(value)); // Deep copy other types as well
            }
        }
        return result;
    }

    /**
     * Helper method to process lists for {@link #hidePrivate(Map, Map)}.
     * It recursively calls {@code hidePrivate} for map elements and {@code hideList} for list elements.
     * String elements are masked if {@code listItemsRegex} is provided and matches.
     *
     * @param list                 The list to process.
     * @param listItemsRegex       Regex to apply to string items if the list key (or its singular form) matched a rule.
     *                             Can be null if no specific rule applies to list items directly.
     * @param currentRecursiveFields The map of fields to hide for nested maps within the list.
     * @return A new list with sensitive items masked.
     */
    @SuppressWarnings("unchecked")
    private static List<Object> hideList(List<Object> list, String listItemsRegex, Map<String, String> currentRecursiveFields) {
        if (list == null) {
            return null;
        }
        List<Object> newList = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof String && listItemsRegex != null) {
                if (Pattern.matches(listItemsRegex, (String) item)) {
                    newList.add(maskSecret((String) item, 4));
                } else {
                    newList.add(item); // No regex match, keep original string
                }
            } else if (item instanceof Map) {
                newList.add(hidePrivate((Map<String, Object>) item, currentRecursiveFields));
            } else if (item instanceof List) {
                // Recursively process nested lists; they don't inherit listItemsRegex directly from parent list key
                // unless explicitly passed. For general recursion, pass null for listItemsRegex.
                newList.add(hideList((List<Object>) item, null, currentRecursiveFields));
            } else {
                newList.add(deepCopy(item)); // Deep copy other types
            }
        }
        return newList;
    }

    /**
     * Performs a deep copy of the given object. Supports Map, List, and other common immutable types.
     * If the object type is not supported for deep copying (e.g., custom mutable objects),
     * its reference is returned. This method primarily targets collections (Map, List) and primitives/Strings.
     *
     * @param original The object to deep copy.
     * @return A deep copy of the object, or the original object if copying is not supported/needed for that type.
     */
    @SuppressWarnings("unchecked")
    private static Object deepCopy(Object original) {
        if (original == null) {
            return null;
        }
        if (original instanceof Map) {
            Map<?, ?> originalMap = (Map<?, ?>) original;
            Map<Object, Object> copyMap = new HashMap<>();
            for (Map.Entry<?, ?> entry : originalMap.entrySet()) {
                // Assuming keys are typically strings or other immutable types suitable for direct use or shallow copy.
                // If keys can also be complex mutable types needing deep copy, this part would need extension.
                copyMap.put(deepCopy(entry.getKey()), deepCopy(entry.getValue()));
            }
            return copyMap;
        } else if (original instanceof List) {
            List<?> originalList = (List<?>) original;
            List<Object> copyList = new ArrayList<>();
            for (Object item : originalList) {
                copyList.add(deepCopy(item));
            }
            return copyList;
        }
        // For immutable types (String, Integer, Boolean, Double, Long, Float etc.)
        // or types we don't specifically handle for deep copy,
        // returning the original reference is standard practice.
        return original;
    }
}
