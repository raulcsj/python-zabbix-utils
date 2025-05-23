package io.zabbix4j.api;

/**
 * Contains constants related to the Zabbix API and this library.
 * @author ShortRoundDev
 */
public final class ZabbixApiConstants {

    private ZabbixApiConstants() {
        // Private constructor to prevent instantiation
    }

    /**
     * The version of the zabbix4j library.
     * This corresponds to the {@code __version__} in the original Python library.
     */
    public static final String LIBRARY_VERSION = "1.0.0-SNAPSHOT"; // Placeholder, will be updated by build

    /**
     * The minimum supported Zabbix API version by this library.
     * This corresponds to the {@code __min_supported__} in the original Python library.
     * The format is major.minor (e.g., 5.0f).
     */
    public static final float MIN_SUPPORTED_ZABBIX_API_VERSION = 5.0f;

    /**
     * The maximum supported Zabbix API version by this library.
     * This corresponds to the {@code __max_supported__} in the original Python library.
     * The format is major.minor (e.g., 7.2f).
     * A value of 0.0f indicates no upper limit, meaning it's expected to work with future versions,
     * though full compatibility isn't guaranteed.
     */
    public static final float MAX_SUPPORTED_ZABBIX_API_VERSION = 0.0f; // Or a specific version like 7.2f
}
