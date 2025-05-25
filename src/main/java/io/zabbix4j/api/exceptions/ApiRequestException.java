package io.zabbix4j.api.exceptions;

/**
 * Exception thrown when the Zabbix API returns an error in its response.
 * <p>
 * This exception encapsulates details about the API error, including an error code,
 * a message from the API, and supplementary data. It may also include the original
 * request body that led to the error.
 * </p>
 *
 * @author CSJ
 */
public class ApiRequestException extends ZabbixApiException {

    private static final long serialVersionUID = 1L; // Recommended for Serializable classes

    private final Integer code;
    private final String data;
    private final transient Object requestBody; // transient as it might not be serializable or very large

    /**
     * Constructs an {@code ApiRequestException} with a general error message.
     * Use this when the error structure from Zabbix API is not available or not applicable.
     *
     * @param message The detail message.
     */
    public ApiRequestException(String message) {
        super(message);
        this.code = null;
        this.data = null;
        this.requestBody = null;
    }

    /**
     * Constructs an {@code ApiRequestException} with detailed information from the Zabbix API error response.
     *
     * @param apiMessage  The error message provided by the Zabbix API.
     * @param code        The error code provided by the Zabbix API.
     * @param data        Additional data or details related to the error from the Zabbix API.
     * @param requestBody The request body that triggered this API error.
     *                    This will not be serialized with the exception by default due to 'transient'.
     *                    Masking of sensitive data within this body should be handled by the caller (e.g., at logging).
     */
    public ApiRequestException(String apiMessage, Integer code, String data, Object requestBody) {
        super(String.format("API Error (code: %s): %s - Data: %s",
                code != null ? code.toString() : "N/A",
                apiMessage != null ? apiMessage : "No message",
                data != null ? data : "N/A"));
        this.code = code;
        // If apiMessage is the primary message, store it in the super class's message field.
        // The 'message' field in this class is effectively the 'apiMessage' parameter.
        // No, the super's message is already formatted. This.message is not needed.
        this.data = data;
        this.requestBody = requestBody;
    }

    /**
     * Constructs an {@code ApiRequestException} with detailed information from the Zabbix API error response
     * and a cause.
     *
     * @param apiMessage  The error message provided by the Zabbix API.
     * @param code        The error code provided by the Zabbix API.
     * @param data        Additional data or details related to the error from the Zabbix API.
     * @param requestBody The request body that triggered this API error.
     * @param cause       The underlying cause of this exception.
     */
    public ApiRequestException(String apiMessage, Integer code, String data, Object requestBody, Throwable cause) {
        super(String.format("API Error (code: %s): %s - Data: %s",
                code != null ? code.toString() : "N/A",
                apiMessage != null ? apiMessage : "No message",
                data != null ? data : "N/A"), cause);
        this.code = code;
        this.data = data;
        this.requestBody = requestBody;
    }

    /**
     * Gets the error code returned by the Zabbix API.
     *
     * @return The Zabbix API error code, or {@code null} if not applicable.
     */
    public Integer getCode() {
        return code;
    }

    /**
     * Gets additional data or details related to the error from the Zabbix API.
     *
     * @return The error data, or {@code null} if not provided.
     */
    public String getData() {
        return data;
    }

    /**
     * Gets the request body that triggered this API error.
     * Note: This field is transient and might be null if the exception instance
     * was serialized and deserialized. Masking of sensitive data within this
     * body should be handled by the caller (e.g., at logging).
     *
     * @return The request body, or {@code null}.
     */
    public Object getRequestBody() {
        return requestBody;
    }
}
