package io.zabbix4j.api.common;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Utility class for masking sensitive data in strings and data structures.
 * <p>
 * This class is non-instantiable.
 * </p>
 *
 * @author CSJ
 */
public final class DataMaskingUtils {

    private static final Pattern HEX_32_CHAR_PATTERN = Pattern.compile("^[A-Za-z0-9]{32}$");

    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private DataMaskingUtils() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    /**
     * Masks a secret string, showing only a few characters at the beginning and end.
     * If the string is too short or {@code showLen} is 0, the entire string is replaced by {@link ZabbixApiConstants#HIDING_MASK}.
     *
     * @param secret  The secret string to mask.
     * @param showLen The number of characters to show at the beginning and end of the string.
     * @return The masked string, or the original string if it's null or empty.
     */
    public static String maskSecret(String secret, int showLen) {
        if (secret == null || secret.isEmpty()) {
            return secret;
        }

        if (showLen <= 0 || secret.length() <= ZabbixApiConstants.HIDING_MASK.length() + showLen * 2) {
            return ZabbixApiConstants.HIDING_MASK;
        }

        return secret.substring(0, showLen) +
                ZabbixApiConstants.HIDING_MASK +
                secret.substring(secret.length() - showLen);
    }

    /**
     * Masks a secret string, showing 4 characters at the beginning and end by default.
     *
     * @param secret The secret string to mask.
     * @return The masked string.
     * @see #maskSecret(String, int)
     */
    public static String maskSecret(String secret) {
        return maskSecret(secret, 4);
    }

    /**
     * Recursively hides private fields in a map representing JSON-like data.
     * This method creates a deep copy of the input map and modifies the copy.
     *
     * @param dataMap The map containing data where sensitive fields need to be hidden.
     * @return A new map with sensitive fields masked. Returns an empty map if input is null.
     */
    @SuppressWarnings("unchecked") // For casting Object to Map or List
    public static Map<String, Object> hidePrivateFields(Map<String, Object> dataMap) {
        if (dataMap == null) {
            return new HashMap<>(); // Or throw IllegalArgumentException, returning empty map for now
        }

        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : dataMap.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (value instanceof Map) {
                result.put(key, hidePrivateFields((Map<String, Object>) value));
            } else if (value instanceof List) {
                result.put(key, hidePrivateFieldsInList((List<Object>) value));
            } else if (value instanceof String) {
                String stringValue = (String) value;
                if (ZabbixApiConstants.FIELD_TOKEN.equals(key) ||
                        ZabbixApiConstants.FIELD_AUTH.equals(key) ||
                        ZabbixApiConstants.FIELD_PASSWD.equals(key) ||
                        ZabbixApiConstants.FIELD_SESSIONID.equals(key) ||
                        ZabbixApiConstants.FIELD_PASSWORD.equals(key) ||
                        ZabbixApiConstants.FIELD_CURRENT_PASSWD.equals(key)) {
                    result.put(key, maskSecret(stringValue));
                } else if (ZabbixApiConstants.FIELD_RESULT.equals(key) && HEX_32_CHAR_PATTERN.matcher(stringValue).matches()) {
                    result.put(key, maskSecret(stringValue));
                } else {
                    result.put(key, stringValue);
                }
            } else {
                result.put(key, value); // Non-string, non-collection type
            }
        }
        return result;
    }

    /**
     * Helper method to recursively hide private fields in a list.
     * This method creates a deep copy of the input list and modifies the copy.
     *
     * @param dataList The list containing data where sensitive fields might be present in maps.
     * @return A new list with sensitive fields in any contained maps masked.
     */
    @SuppressWarnings("unchecked")
    private static List<Object> hidePrivateFieldsInList(List<Object> dataList) {
        if (dataList == null) {
            return new ArrayList<>();
        }
        List<Object> resultList = new ArrayList<>();
        for (Object item : dataList) {
            if (item instanceof Map) {
                resultList.add(hidePrivateFields((Map<String, Object>) item));
            } else if (item instanceof List) {
                resultList.add(hidePrivateFieldsInList((List<Object>) item)); // Handle lists of lists
            } else {
                resultList.add(item);
            }
        }
        return resultList;
    }

    /**
     * Hides private fields in a JSON string.
     * <p>
     * <b>Note:</b> This method currently has a placeholder implementation for JSON parsing and serialization.
     * For robust handling, a dedicated JSON library (e.g., Jackson, Gson, org.json) is required.
     * The current implementation will throw an {@link UnsupportedOperationException} if called,
     * indicating the need for a proper JSON library.
     * </p>
     *
     * @param jsonString The JSON string where sensitive fields need to be hidden.
     * @return A new JSON string with sensitive fields masked.
     * @throws UnsupportedOperationException if a JSON library is not available/integrated.
     *                                       This is a placeholder and should be replaced with actual JSON processing.
     */
    public static String hidePrivateFieldsInJsonString(String jsonString) {
        // Placeholder: Requires a JSON library like Jackson, Gson, or org.json
        // Example with a hypothetical JsonUtils.toMap and JsonUtils.toJsonString:
        //
        // if (jsonString == null || jsonString.isEmpty()) {
        //     return jsonString;
        // }
        // try {
        //     Map<String, Object> dataMap = JsonUtils.toMap(jsonString); // Hypothetical
        //     Map<String, Object> maskedMap = hidePrivateFields(dataMap);
        //     return JsonUtils.toJsonString(maskedMap); // Hypothetical
        // } catch (Exception e) {
        //     // Log error or handle appropriately
        //     // For now, returning the original string or a generic error message
        //     // Or rethrow as a specific DataMaskingException
        //     System.err.println("JSON processing error during data masking: " + e.getMessage());
        //     return jsonString; // Or throw new ProcessingException("Failed to mask JSON", e);
        // }

        // Current placeholder action:
        throw new UnsupportedOperationException(
                "JSON parsing and serialization for data masking is not yet implemented. " +
                        "A JSON library (e.g., Jackson, Gson, or org.json) is required for this functionality."
        );
    }
}
