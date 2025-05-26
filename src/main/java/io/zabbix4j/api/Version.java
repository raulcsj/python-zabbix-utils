package io.zabbix4j.api;

import io.zabbix4j.api.types.ApiVersion;

/**
 * Provides version information for the zabbix4j library and the range of
 * Zabbix API versions it supports.
 * This class contains only static constants and cannot be instantiated.
 *
 * @author CSJ
 */
public final class Version {

    /**
     * The current version of the zabbix4j library.
     */
    public static final String LIBRARY_VERSION = "1.0.0-SNAPSHOT";

    /**
     * The minimum Zabbix API version string supported by this library (e.g., "5.0.0").
     * This corresponds to Zabbix server version 5.0.
     */
    public static final String MIN_SUPPORTED_ZABBIX_API_STRING = "5.0.0";

    /**
     * The maximum Zabbix API version string primarily tested and supported by this library (e.g., "7.2.0").
     * This corresponds to Zabbix server version 7.2.
     * While the library might work with newer versions, full compatibility is emphasized up to this version.
     */
    public static final String MAX_SUPPORTED_ZABBIX_API_STRING = "7.2.0";

    /**
     * An {@link ApiVersion} object representing the minimum Zabbix API version supported by this library.
     * Useful for programmatic version comparisons.
     */
    public static final ApiVersion MIN_SUPPORTED_ZABBIX_API = new ApiVersion(MIN_SUPPORTED_ZABBIX_API_STRING);

    /**
     * An {@link ApiVersion} object representing the maximum Zabbix API version primarily tested and supported by this library.
     * Useful for programmatic version comparisons.
     */
    public static final ApiVersion MAX_SUPPORTED_ZABBIX_API = new ApiVersion(MAX_SUPPORTED_ZABBIX_API_STRING);

    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private Version() {
    }
}
