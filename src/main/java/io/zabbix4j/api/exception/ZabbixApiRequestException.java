package io.zabbix4j.api.exception;

import java.util.Objects;

/**
 * Exception thrown when the Zabbix API returns an error object in its JSON-RPC response.
 * This typically indicates a problem with the request itself (e.g., invalid parameters,
 * authentication issues) or an error encountered by the Zabbix server while processing the request.
 * <p>
 * The Zabbix API error object usually has the following structure:
 * <pre>{@code
 * {
 *   "code": -32602,
 *   "message": "Invalid params.",
 *   "data": "Incorrect arguments passed to function."
 * }
 * }</pre>
 * This exception class captures these details.
 *
 * @author Your Name
 */
public class ZabbixApiRequestException extends ZabbixApiException {

    private final Integer code;
    private final String apiErrorMessage;
    private final String data;
    private final transient Object requestBody; // transient to suggest it might not be serialized

    /**
     * Constructs a new ZabbixApiRequestException.
     *
     * @param message         A descriptive message for this exception (can be a summary).
     * @param code            The error code from the Zabbix API error object (e.g., -32602). Can be null.
     * @param apiErrorMessage The 'message' field from the Zabbix API error object.
     * @param data            The 'data' field from the Zabbix API error object, providing more details.
     */
    public ZabbixApiRequestException(String message, Integer code, String apiErrorMessage, String data) {
        this(message, code, apiErrorMessage, data, null, null);
    }

    /**
     * Constructs a new ZabbixApiRequestException with a cause.
     *
     * @param message         A descriptive message for this exception.
     * @param code            The error code from the Zabbix API error object. Can be null.
     * @param apiErrorMessage The 'message' field from the Zabbix API error object.
     * @param data            The 'data' field from the Zabbix API error object.
     * @param cause           The underlying cause of this exception.
     */
    public ZabbixApiRequestException(String message, Integer code, String apiErrorMessage, String data, Throwable cause) {
        this(message, code, apiErrorMessage, data, null, cause);
    }

    /**
     * Constructs a new ZabbixApiRequestException with the request body that caused the error.
     *
     * @param message         A descriptive message for this exception.
     * @param code            The error code from the Zabbix API error object. Can be null.
     * @param apiErrorMessage The 'message' field from the Zabbix API error object.
     * @param data            The 'data' field from the Zabbix API error object.
     * @param requestBody     The body of the request that led to this error. Stored for debugging;
     *                        its {@code toString()} method should be sanitized if logged directly.
     */
    public ZabbixApiRequestException(String message, Integer code, String apiErrorMessage, String data, Object requestBody) {
        this(message, code, apiErrorMessage, data, requestBody, null);
    }

    /**
     * Constructs a new ZabbixApiRequestException with the request body and a cause.
     *
     * @param message         A descriptive message for this exception.
     * @param code            The error code from the Zabbix API error object. Can be null.
     * @param apiErrorMessage The 'message' field from the Zabbix API error object.
     * @param data            The 'data' field from the Zabbix API error object.
     * @param requestBody     The body of the request that led to this error. Stored for debugging.
     * @param cause           The underlying cause of this exception.
     */
    public ZabbixApiRequestException(String message, Integer code, String apiErrorMessage, String data, Object requestBody, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.apiErrorMessage = apiErrorMessage;
        this.data = data;
        this.requestBody = requestBody; // Store the request body; consider making a defensive copy if mutable
    }

    /**
     * Gets the error code returned by the Zabbix API.
     * This corresponds to the 'code' field in the Zabbix API error object.
     * For example, -32602 for "Invalid params."
     *
     * @return The Zabbix API error code, or {@code null} if not available.
     */
    public Integer getCode() {
        return code;
    }

    /**
     * Gets the error message returned by the Zabbix API.
     * This corresponds to the 'message' field in the Zabbix API error object.
     * For example, "Invalid params."
     *
     * @return The error message from the Zabbix API.
     */
    public String getApiErrorMessage() {
        return apiErrorMessage;
    }

    /**
     * Gets the detailed error data returned by the Zabbix API.
     * This corresponds to the 'data' field in the Zabbix API error object,
     * often providing more context or specific details about the error.
     * For example, "Incorrect arguments passed to function."
     *
     * @return The detailed error data from the Zabbix API.
     */
    public String getData() {
        return data;
    }

    /**
     * Gets the body of the request that caused this error.
     * This is intended for debugging purposes. If this object is logged,
     * ensure its {@code toString()} representation is sanitized to prevent leaking
     * sensitive information (e.g., authentication tokens).
     *
     * @return The request body object, or {@code null} if not provided.
     */
    public Object getRequestBody() {
        return requestBody;
    }

    /**
     * Returns a string representation of this exception, including Zabbix API-specific error details.
     * Note: The requestBody is not included in this default toString() to avoid accidental logging of sensitive data.
     * Use {@link #getRequestBody()} and handle its logging carefully if needed.
     *
     * @return A string representation of this exception.
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(super.toString()); // Start with the message from ZabbixApiException
        sb.append(System.lineSeparator());
        sb.append("  Zabbix API Error Code: ").append(code).append(System.lineSeparator());
        sb.append("  Zabbix API Error Message: ").append(apiErrorMessage).append(System.lineSeparator());
        sb.append("  Zabbix API Error Data: ").append(data);
        // Avoid logging requestBody directly in toString() due to potential sensitive data.
        // if (requestBody != null) {
        //     sb.append(System.lineSeparator()).append("  Request Body: [use getRequestBody() to inspect]");
        // }
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false; // Compare message and cause from parent
        ZabbixApiRequestException that = (ZabbixApiRequestException) o;
        return Objects.equals(code, that.code) &&
               Objects.equals(apiErrorMessage, that.apiErrorMessage) &&
               Objects.equals(data, that.data) &&
               Objects.equals(requestBody, that.requestBody); // Be cautious with requestBody comparison if complex
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), code, apiErrorMessage, data, requestBody);
    }
}
