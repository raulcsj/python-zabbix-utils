package io.zabbix4j.api.exception;

import java.util.Objects;

/**
 * Exception thrown when an attempted action, method, or feature is not supported
 * by the target Zabbix API version being interacted with.
 * This indicates that the Zabbix server's API does not have the requested capability,
 * often because the feature was introduced in a later version or removed in an earlier one.
 *
 * @author Your Name
 */
public class ZabbixApiNotSupportedException extends ZabbixApiException {

    private final String featureName;
    private final String currentApiVersion;

    /**
     * Constructs a new ZabbixApiNotSupportedException with the specified detail message.
     *
     * @param message The detail message.
     */
    public ZabbixApiNotSupportedException(String message) {
        this(message, null, null, null);
    }

    /**
     * Constructs a new ZabbixApiNotSupportedException with the specified detail message and cause.
     *
     * @param message The detail message.
     * @param cause   The cause of this exception.
     */
    public ZabbixApiNotSupportedException(String message, Throwable cause) {
        this(message, null, null, cause);
    }

    /**
     * Constructs a new ZabbixApiNotSupportedException with a detail message,
     * the name of the unsupported feature, and the current API version.
     * The final exception message will be formatted to include these details.
     *
     * @param message           A base message for the exception. If null or empty, a default message will be generated.
     * @param featureName       The name of the feature or action that is not supported (e.g., "Token usage").
     * @param currentApiVersion The version of the Zabbix API being interacted with (e.g., "5.2.0").
     */
    public ZabbixApiNotSupportedException(String message, String featureName, String currentApiVersion) {
        this(formatMessage(message, featureName, currentApiVersion), featureName, currentApiVersion, null);
    }

    /**
     * Constructs a new ZabbixApiNotSupportedException with a detail message,
     * the name of the unsupported feature, the current API version, and a cause.
     * The final exception message will be formatted to include these details.
     *
     * @param message           A base message for the exception. If null or empty, a default message will be generated.
     * @param featureName       The name of the feature or action that is not supported.
     * @param currentApiVersion The version of the Zabbix API being interacted with.
     * @param cause             The cause of this exception.
     */
    public ZabbixApiNotSupportedException(String message, String featureName, String currentApiVersion, Throwable cause) {
        super(formatMessage(message, featureName, currentApiVersion), cause);
        this.featureName = featureName;
        this.currentApiVersion = currentApiVersion;
    }

    /**
     * Formats the exception message to include feature name and API version if available.
     */
    private static String formatMessage(String message, String featureName, String currentApiVersion) {
        if (featureName != null && !featureName.trim().isEmpty() && currentApiVersion != null && !currentApiVersion.trim().isEmpty()) {
            String featureInfo = "Feature '" + featureName.trim() + "' is not supported by Zabbix API version " + currentApiVersion.trim() + ".";
            return (message != null && !message.trim().isEmpty()) ? message.trim() + " (" + featureInfo + ")" : featureInfo;
        } else if (featureName != null && !featureName.trim().isEmpty()) {
            String featureInfo = "Feature '" + featureName.trim() + "' is not supported.";
            return (message != null && !message.trim().isEmpty()) ? message.trim() + " (" + featureInfo + ")" : featureInfo;
        }
        return (message != null && !message.trim().isEmpty()) ? message : "The requested feature or operation is not supported by the Zabbix API.";
    }

    /**
     * Gets the name of the feature or action that is not supported.
     *
     * @return The name of the unsupported feature, or {@code null} if not specified.
     */
    public String getFeatureName() {
        return featureName;
    }

    /**
     * Gets the version of the Zabbix API that was being interacted with when the
     * non-supported feature was encountered.
     *
     * @return The Zabbix API version string (e.g., "5.2.0"), or {@code null} if not specified.
     */
    public String getCurrentApiVersion() {
        return currentApiVersion;
    }

    /**
     * Returns a string representation of this exception, including the feature name and API version if available.
     *
     * @return A string representation of this exception.
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(super.toString()); // Gets the formatted message from ZabbixApiException
        // Additional details are already incorporated into the message by the constructors.
        // However, if we want to explicitly list them again or ensure they are present:
        // if (featureName != null) {
        //     sb.append(System.lineSeparator()).append("  Unsupported Feature: ").append(featureName);
        // }
        // if (currentApiVersion != null) {
        //     sb.append(System.lineSeparator()).append("  API Version: ").append(currentApiVersion);
        // }
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false; // Compare message and cause from parent
        ZabbixApiNotSupportedException that = (ZabbixApiNotSupportedException) o;
        return Objects.equals(featureName, that.featureName) &&
               Objects.equals(currentApiVersion, that.currentApiVersion);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), featureName, currentApiVersion);
    }
}
