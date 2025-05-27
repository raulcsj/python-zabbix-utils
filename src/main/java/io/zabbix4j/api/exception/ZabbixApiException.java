package io.zabbix4j.api.exception;

/**
 * Base exception for all errors and exceptional conditions encountered within the Zabbix API client library.
 * This is a checked exception, requiring callers to handle it or declare it in their method signatures.
 * It serves as a common superclass for more specific Zabbix API related exceptions.
 *
 * @author Your Name
 */
public class ZabbixApiException extends RuntimeException { // Changed from Exception to RuntimeException

    /**
     * Constructs a new ZabbixApiException with the specified detail message.
     * The cause is not initialized, and may subsequently be initialized by a
     * call to {@link #initCause(Throwable)}.
     *
     * @param message the detail message. The detail message is saved for
     *                later retrieval by the {@link #getMessage()} method.
     */
    public ZabbixApiException(String message) {
        super(message);
    }

    /**
     * Constructs a new ZabbixApiException with the specified detail message and
     * cause.
     * <p>Note that the detail message associated with
     * {@code cause} is <i>not</i> automatically incorporated in
     * this exception's detail message.
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
