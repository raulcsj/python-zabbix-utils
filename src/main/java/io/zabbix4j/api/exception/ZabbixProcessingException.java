package io.zabbix4j.api.exception;

/**
 * General-purpose exception for errors occurring during the processing of requests or responses
 * within the Zabbix API client library. This exception is typically used for issues
 * not directly stemming from a Zabbix API error response (covered by {@link ZabbixApiRequestException})
 * or unsupported API features (covered by {@link ZabbixApiNotSupportedException}).
 * <p>
 * Examples of situations where this exception might be thrown include:
 * <ul>
 *     <li>Errors during JSON serialization or deserialization of request/response bodies.</li>
 *     <li>Network connectivity issues that are handled internally before a request can be made or after a response is partially received (e.g., connection timeouts, SSL handshake failures if not covered by a more specific IO exception).</li>
 *     <li>Unexpected or malformed response structures from Zabbix Getter or Zabbix Sender utilities, if the library interacts with them.</li>
 *     <li>Internal library errors or unexpected states during the handling of API communication.</li>
 * </ul>
 *
 * @author Your Name
 */
public class ZabbixProcessingException extends ZabbixApiException {

    /**
     * Constructs a new ZabbixProcessingException with the specified detail message.
     * The cause is not initialized, and may subsequently be initialized by a
     * call to {@link #initCause(Throwable)}.
     *
     * @param message the detail message. The detail message is saved for
     *                later retrieval by the {@link #getMessage()} method.
     */
    public ZabbixProcessingException(String message) {
        super(message);
    }

    /**
     * Constructs a new ZabbixProcessingException with the specified detail message and
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
    public ZabbixProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
