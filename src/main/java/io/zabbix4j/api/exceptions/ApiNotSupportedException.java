package io.zabbix4j.api.exceptions;

/**
 * Exception thrown when a feature is not supported by the Zabbix API,
 * potentially due to the Zabbix server version.
 *
 * @author CSJ
 */
public class ApiNotSupportedException extends ZabbixApiException {

    private static final long serialVersionUID = 1L; // Recommended for Serializable classes

    private final String featureDescription;
    private final String zabbixVersion;

    /**
     * Constructs an {@code ApiNotSupportedException} indicating a feature is not supported.
     *
     * @param featureDescription A description of the feature that is not supported (e.g., "Token usage").
     */
    public ApiNotSupportedException(String featureDescription) {
        super(String.format("'%s' is not supported.", featureDescription));
        this.featureDescription = featureDescription;
        this.zabbixVersion = null;
    }

    /**
     * Constructs an {@code ApiNotSupportedException} indicating a feature is not supported
     * by a specific Zabbix version.
     *
     * @param featureDescription A description of the feature that is not supported.
     * @param zabbixVersion      The Zabbix version that does not support the feature.
     */
    public ApiNotSupportedException(String featureDescription, String zabbixVersion) {
        super(String.format("'%s' is not supported by Zabbix version '%s'.", featureDescription, zabbixVersion));
        this.featureDescription = featureDescription;
        this.zabbixVersion = zabbixVersion;
    }

    /**
     * Constructs an {@code ApiNotSupportedException} with a message and a cause.
     *
     * @param featureDescription A description of the feature that is not supported.
     * @param cause              The underlying cause of this exception.
     */
    public ApiNotSupportedException(String featureDescription, Throwable cause) {
        super(String.format("'%s' is not supported.", featureDescription), cause);
        this.featureDescription = featureDescription;
        this.zabbixVersion = null;
    }

    /**
     * Constructs an {@code ApiNotSupportedException} with a message, Zabbix version, and a cause.
     *
     * @param featureDescription A description of the feature that is not supported.
     * @param zabbixVersion      The Zabbix version that does not support the feature.
     * @param cause              The underlying cause of this exception.
     */
    public ApiNotSupportedException(String featureDescription, String zabbixVersion, Throwable cause) {
        super(String.format("'%s' is not supported by Zabbix version '%s'.", featureDescription, zabbixVersion), cause);
        this.featureDescription = featureDescription;
        this.zabbixVersion = zabbixVersion;
    }

    /**
     * Gets the description of the feature that is not supported.
     *
     * @return The feature description.
     */
    public String getFeatureDescription() {
        return featureDescription;
    }

    /**
     * Gets the Zabbix version associated with this non-support, if specified.
     *
     * @return The Zabbix version string, or {@code null} if not specified.
     */
    public String getZabbixVersion() {
        return zabbixVersion;
    }
}
