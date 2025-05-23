package io.zabbix4j.api.exception;

/**
 * Represents an error that occurred during communication with the Zabbix server
 * or Zabbix agent/proxy. This could be due to network issues, timeouts,
 * or problems with the underlying HTTP client or socket connections.
 *
 * @author ShortRoundDev
 */
public class CommunicationException extends ZabbixApiException {

    /**
     * Constructs a new communication exception with the specified detail message.
     *
     * @param message the detail message. The detail message is saved for
     *                later retrieval by the {@link #getMessage()} method.
     */
    public CommunicationException(String message) {
        super(message);
    }

    /**
     * Constructs a new communication exception with the specified detail message and
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
    public CommunicationException(String message, Throwable cause) {
        super(message, cause);
    }
}
