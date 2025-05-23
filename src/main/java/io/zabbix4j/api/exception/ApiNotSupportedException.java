package io.zabbix4j.api.exception;

/**
 * Indicates that a requested Zabbix API feature or method is not supported,
 * often due to the Zabbix server version being too old or too new for a specific
 * operation, or the feature being experimental and not enabled.
 *
 * @author ShortRoundDev
 */
public class ApiNotSupportedException extends ZabbixApiException {

    private final String zabbixVersion; // Optional: the version of Zabbix being interacted with

    /**
     * Constructs a new API not supported exception with the specified detail message.
     *
     * @param message the detail message.
     */
    public ApiNotSupportedException(String message) {
        super(message);
        this.zabbixVersion = null;
    }

    /**
     * Constructs a new API not supported exception with the specified detail message
     * and the Zabbix version that caused the incompatibility.
     *
     * @param message       the detail message.
     * @param zabbixVersion the Zabbix version string (e.g., "5.0.17") related to the support issue.
     */
    public ApiNotSupportedException(String message, String zabbixVersion) {
        super(message + (zabbixVersion != null ? " (Zabbix version: " + zabbixVersion + ")" : ""));
        this.zabbixVersion = zabbixVersion;
    }

    /**
     * Gets the Zabbix version associated with this exception, if provided.
     *
     * @return The Zabbix version string, or {@code null} if not set.
     */
    public String getZabbixVersion() {
        return zabbixVersion;
    }
}
