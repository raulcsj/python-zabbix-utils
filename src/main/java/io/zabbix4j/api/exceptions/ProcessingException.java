package io.zabbix4j.api.exceptions;

/**
 * Exception thrown for general errors that occur during the processing of
 * Zabbix API requests or responses, outside of direct API-returned errors.
 * <p>
 * This can include issues like network connection problems, data parsing errors,
 * or unexpected client-side issues before an API call is made or after a response
 * is received but before it's fully processed.
 * </p>
 *
 * @author CSJ
 */
public class ProcessingException extends ZabbixApiException {

    private static final long serialVersionUID = 1L; // Recommended for Serializable classes

    /**
     * Constructs a new ProcessingException with the specified detail message.
     *
     * @param message the detail message.
     */
    public ProcessingException(String message) {
        super(message);
    }

    /**
     * Constructs a new ProcessingException with the specified detail message and
     * cause.
     *
     * @param message the detail message.
     * @param cause   the cause (which is saved for later retrieval by the
     *                {@link #getCause()} method).
     */
    public ProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
