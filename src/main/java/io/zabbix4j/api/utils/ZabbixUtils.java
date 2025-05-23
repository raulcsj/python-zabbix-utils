package io.zabbix4j.api.utils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Common utility methods for Zabbix API interactions.
 * @author ShortRoundDev
 */
public final class ZabbixUtils {

    /**
     * The filename for the Zabbix API JSON-RPC endpoint.
     */
    public static final String JSONRPC_FILE = "api_jsonrpc.php";

    /**
     * The mask used for hiding sensitive information.
     */
    public static final String HIDING_MASK = "********";

    /**
     * Set of Zabbix API methods that do not require authentication.
     */
    public static final Set<String> UNAUTH_METHODS;

    /**
     * Set of Zabbix API methods that might return file content or large data.
     */
    public static final Set<String> FILES_METHODS;

    /**
     * Map of field names (lowercase) to Patterns that their String values should match for masking.
     * Example: "token" maps to a pattern that matches any non-empty string.
     */
    private static final Map<String, Pattern> SENSITIVE_FIELD_PATTERNS;

    /**
     * Pattern for field *names* that indicate sensitive keys (like SNMP community strings).
     * If a field *name* matches this pattern and its value is a String, it will be masked.
     */
    private static final Pattern SENSITIVE_KEY_NAME_PATTERN;


    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);


    static {
        // Initialize UNAUTH_METHODS
        Set<String> unauthMethods = Set.of("apiinfo.version", "user.login", "user.checkAuthentication");
        UNAUTH_METHODS = Collections.unmodifiableSet(unauthMethods);

        // Initialize FILES_METHODS
        Set<String> filesMethods = Set.of("configuration.export");
        FILES_METHODS = Collections.unmodifiableSet(filesMethods);

        // Initialize SENSITIVE_FIELD_PATTERNS (field name -> pattern for its value)
        Map<String, Pattern> sensitiveFields = new HashMap<>();
        sensitiveFields.put("token", Pattern.compile("^.+$")); // Matches any non-empty string
        sensitiveFields.put("sessionid", Pattern.compile("^.+$"));
        sensitiveFields.put("password", Pattern.compile("^.+$"));
        sensitiveFields.put("passwd", Pattern.compile("^.+$"));
        sensitiveFields.put("secret", Pattern.compile("^.+$"));
        sensitiveFields.put("auth", Pattern.compile("^.+$")); // Common for API tokens
        // Pattern for the *value* if the key is "credentials"
        sensitiveFields.put("credentials", Pattern.compile(".*(password|secret|token|key|passwd).*", Pattern.CASE_INSENSITIVE));
        SENSITIVE_FIELD_PATTERNS = Collections.unmodifiableMap(sensitiveFields);

        // Initialize SENSITIVE_KEY_NAME_PATTERN (pattern for field names like snmp keys)
        SENSITIVE_KEY_NAME_PATTERN = Pattern.compile("^(?i)(snmp_community|snmp_security_name|snmp_auth_passphrase|snmp_priv_passphrase|.*key.*|.*secret.*|.*token.*|.*password.*|.*passwd.*)$");
    }

    private ZabbixUtils() {
        // Private constructor to prevent instantiation
    }

    /**
     * Checks and corrects a Zabbix API URL.
     * Ensures the URL starts with "http://" or "https://" and ends with "/api_jsonrpc.php".
     *
     * @param url The URL string to check.
     * @return The corrected URL.
     * @throws IllegalArgumentException if the URL is null, empty, or does not start with "http" or "https".
     */
    public static String checkUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("URL cannot be null or empty.");
        }

        String trimmedUrl = url.trim();
        if (!trimmedUrl.startsWith("http://") && !trimmedUrl.startsWith("https://")) {
            throw new IllegalArgumentException("URL must start with 'http://' or 'https://'.");
        }

        if (trimmedUrl.endsWith("/")) {
            trimmedUrl = trimmedUrl.substring(0, trimmedUrl.length() - 1);
        }

        if (!trimmedUrl.endsWith("/" + JSONRPC_FILE)) {
            return trimmedUrl + "/" + JSONRPC_FILE;
        }
        return trimmedUrl;
    }

    /**
     * Masks a secret string, showing only a few characters at the beginning and end.
     *
     * @param secret  The secret string to mask.
     * @param showLen The number of characters to show at the beginning and end of the secret.
     *                If the secret is too short, it will be completely masked.
     * @return The masked secret string.
     */
    public static String maskSecret(String secret, int showLen) {
        if (secret == null) {
            return HIDING_MASK;
        }
        int secretLen = secret.length();
        if (secretLen <= showLen * 2) {
            return HIDING_MASK;
        }
        return secret.substring(0, showLen) + HIDING_MASK + secret.substring(secretLen - showLen);
    }

    /**
     * Recursively hides sensitive information in a map structure (typically representing a JSON object).
     * This method creates a deep copy of the input map to avoid modifying the original.
     * Sensitive fields are identified based on {@link #SENSITIVE_FIELD_PATTERNS} and {@link #SENSITIVE_KEY_NAME_PATTERN}.
     *
     * @param data The map containing data where sensitive information might be present.
     * @return A new map with sensitive data masked. Returns an empty map if input is null.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> hidePrivate(Map<String, Object> data) {
        if (data == null) {
            return new HashMap<>();
        }

        Map<String, Object> dataCopy;
        try {
            // Deep copy using Jackson
            dataCopy = MAPPER.convertValue(data, new TypeReference<HashMap<String, Object>>() {});
        } catch (Exception e) {
            // Fallback to a simple shallow copy if deep copy fails (less ideal)
            // Or rethrow as a ProcessingException. For now, log and shallow copy.
            // System.err.println("Error during deep copy for hidePrivate: " + e.getMessage());
            dataCopy = new HashMap<>(data); // Be cautious with this fallback
        }


        for (Map.Entry<String, Object> entry : dataCopy.entrySet()) {
            String currentKey = entry.getKey();
            Object currentValue = entry.getValue();
            String currentKeyLower = currentKey.toLowerCase();
            boolean masked = false;

            if (currentValue instanceof String) {
                String stringValue = (String) currentValue;

                // Check 1: Field name is a known sensitive field (e.g., "token", "password")
                // AND its string value matches the pattern defined for that field.
                if (SENSITIVE_FIELD_PATTERNS.containsKey(currentKeyLower)) {
                    if (SENSITIVE_FIELD_PATTERNS.get(currentKeyLower).matcher(stringValue).matches()) {
                        entry.setValue(maskSecret(stringValue, 2));
                        masked = true;
                    }
                }

                // Check 2: Field name itself matches a pattern for sensitive keys (e.g., "snmp_community")
                // (Only if not already masked by Check 1)
                if (!masked && SENSITIVE_KEY_NAME_PATTERN.matcher(currentKey).matches()) {
                    entry.setValue(maskSecret(stringValue, 2));
                    masked = true;
                }
            }

            // Recurse for nested maps or lists, if not already masked at this level
            if (!masked) {
                if (currentValue instanceof Map) {
                    entry.setValue(hidePrivate((Map<String, Object>) currentValue));
                } else if (currentValue instanceof List) {
                    List<?> list = (List<?>) currentValue;
                    List<Object> newList = new ArrayList<>(list.size());
                    for (Object listItem : list) {
                        if (listItem instanceof Map) {
                            newList.add(hidePrivate((Map<String, Object>) listItem));
                        } else {
                            // Note: We are not masking individual string elements in a list here
                            // unless the list itself is under a key that gets fully masked (e.g. "params": {"secrets_list": ["a", "b"]})
                            // The python version's hide_private also doesn't seem to iterate and mask plain strings in a list whose key isn't sensitive.
                            newList.add(listItem);
                        }
                    }
                    entry.setValue(newList);
                }
            }
        }
        return dataCopy;
    }
}
