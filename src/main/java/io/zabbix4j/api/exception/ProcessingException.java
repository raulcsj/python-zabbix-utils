package io.zabbix4j.api.exception;

/**
 * Represents an error that occurred during the processing of a Zabbix API
 * response or during other client-side operations, not directly an error
 * from the Zabbix API itself. For example, this could be due to issues
 * parsing a valid but unexpected response, or problems preparing data.
 *
 * @author ShortRoundDev
 */
public class ProcessingException extends ZabbixApiException {

    /**
     * Constructs a new processing exception with the specified detail message.
     *
     * @param message the detail message. The detail message is saved for
     *                later retrieval by the {@link #getMessage()} method.
     */
    public ProcessingException(String message) {
        super(message);
    }

    /**
     * Constructs a new processing exception with the specified detail message and
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
    public ProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
