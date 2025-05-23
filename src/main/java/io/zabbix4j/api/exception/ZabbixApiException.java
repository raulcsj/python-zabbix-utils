package io.zabbix4j.api.exception;

/**
 * Base exception for all Zabbix API client-specific errors.
 * This class provides a common superclass for more specific exceptions
 * related to Zabbix API interactions.
 *
 * @author ShortRoundDev
 */
public class ZabbixApiException extends RuntimeException {

    /**
     * Constructs a new Zabbix API exception with the specified detail message.
     *
     * @param message the detail message. The detail message is saved for
     *                later retrieval by the {@link #getMessage()} method.
     */
    public ZabbixApiException(String message) {
        super(message);
    }

    /**
     * Constructs a new Zabbix API exception with the specified detail message and
     * cause.
     *
     * <p>Note that the detail message associated with {@code cause} is
     * <i>not</i> automatically incorporated in this exception's detail
     * message.
     *
     * @param message the detail message (which is saved for later retrieval
     *                by the {@link #getMessage()} method).
     * @param cause   the cause (which is saved for later retrieval by the
     *                {@link #getCause()} method).  (A {@code null} value is
     *                permitted, and indicates that the cause is nonexistent or
     *                unknown.)
     */
    public ZabbixApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
