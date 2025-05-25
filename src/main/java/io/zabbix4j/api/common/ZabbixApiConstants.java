package io.zabbix4j.api.common;

import java.util.Set;

/**
 * Contains constants used throughout the Zabbix API client.
 * <p>
 * This class is non-instantiable.
 * </p>
 *
 * @author CSJ
 */
public final class ZabbixApiConstants {

    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private ZabbixApiConstants() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    /**
     * The mask used for hiding sensitive information in logs or outputs.
     * Currently: "********".
     */
    public static final String HIDING_MASK = "********";

    /**
     * The filename for the JSON-RPC endpoint of the Zabbix API.
     * Value: "api_jsonrpc.php".
     */
    public static final String JSONRPC_FILE = "api_jsonrpc.php";

    /**
     * A set of Zabbix API methods that do not require authentication.
     * These typically include version discovery and user login methods.
     */
    public static final Set<String> UNAUTHENTICATED_METHODS = Set.of(
            "apiinfo.version",
            "user.login",
            "user.checkAuthentication"
    );

    /**
     * A set of Zabbix API methods whose responses may contain file content
     * (e.g., configuration export) and might need special handling
     * or be excluded from generic logging.
     */
    public static final Set<String> FILE_CONTENT_METHODS = Set.of(
            "configuration.export"
    );

    /**
     * Field name for "token" which often contains sensitive authentication tokens.
     */
    public static final String FIELD_TOKEN = "token";

    /**
     * Field name for "auth" which often contains sensitive authentication data.
     */
    public static final String FIELD_AUTH = "auth";

    /**
     * Field name for "passwd" which often contains sensitive password information.
     */
    public static final String FIELD_PASSWD = "passwd";

    /**
     * Field name for "sessionid" which often contains sensitive session identifiers.
     */
    public static final String FIELD_SESSIONID = "sessionid";

    /**
     * Field name for "password" which often contains sensitive password information.
     */
    public static final String FIELD_PASSWORD = "password";

    /**
     * Field name for "current_passwd" used in operations like password changes.
     */
    public static final String FIELD_CURRENT_PASSWD = "current_passwd";

    /**
     * Field name for "result" which, in some contexts (like user.login),
     * can contain a session ID that should be masked.
     */
    public static final String FIELD_RESULT = "result";

    /**
     * The version of this Zabbix API client library.
     * Used in the User-Agent header.
     */
    public static final String CLIENT_LIB_VERSION = "1.0.0-SNAPSHOT";
}
