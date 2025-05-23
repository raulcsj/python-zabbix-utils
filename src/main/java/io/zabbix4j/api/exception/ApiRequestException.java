package io.zabbix4j.api.exception;

/**
 * Represents an error returned by the Zabbix API itself after a request.
 * This typically encapsulates the error structure (code, message, data)
 * provided by Zabbix.
 *
 * @author ShortRoundDev
 */
public class ApiRequestException extends ZabbixApiException {

    private final int code;
    private final String errorMessage; // Zabbix API's "message" field
    private final String errorData;    // Zabbix API's "data" field
    private final String requestBody;  // Optional: The request body that caused the error

    /**
     * Constructs a new API request exception.
     *
     * @param code         The error code from the Zabbix API response.
     * @param errorMessage The error message from the Zabbix API response.
     * @param errorData    The error data from the Zabbix API response.
     * @param requestBody  The JSON request body that potentially caused this error (can be null).
     * @param message      A descriptive message for this exception, which will be passed to the superclass.
     *                     This message should ideally incorporate the details of the Zabbix API error.
     */
    public ApiRequestException(int code, String errorMessage, String errorData, String requestBody, String message) {
        super(message);
        this.code = code;
        this.errorMessage = errorMessage;
        this.errorData = errorData;
        this.requestBody = requestBody;
    }

    /**
     * Constructs a new API request exception, automatically formatting the main exception message.
     *
     * @param code         The error code from the Zabbix API response.
     * @param errorMessage The error message from the Zabbix API response.
     * @param errorData    The error data from the Zabbix API response.
     * @param requestBody  The JSON request body that potentially caused this error (can be null).
     */
    public ApiRequestException(int code, String errorMessage, String errorData, String requestBody) {
        super(String.format("Zabbix API Error (code: %d): %s - %s%s",
                code,
                errorMessage,
                errorData,
                requestBody != null ? " | Request: " + requestBody.substring(0, Math.min(requestBody.length(), 200)) + (requestBody.length() > 200 ? "..." : "") : ""));
        this.code = code;
        this.errorMessage = errorMessage;
        this.errorData = errorData;
        this.requestBody = requestBody;
    }

    /**
     * Gets the error code returned by the Zabbix API.
     *
     * @return The Zabbix API error code.
     */
    public int getCode() {
        return code;
    }

    /**
     * Gets the specific error message returned by the Zabbix API.
     * This corresponds to the "message" field in the Zabbix error object.
     *
     * @return The Zabbix API error message.
     */
    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * Gets the detailed error data returned by the Zabbix API.
     * This corresponds to the "data" field in the Zabbix error object.
     *
     * @return The Zabbix API error data.
     */
    public String getErrorData() {
        return errorData;
    }

    /**
     * Gets the JSON request body that was sent to the Zabbix API, if available.
     * This can be useful for debugging the cause of the error.
     *
     * @return The request body string, or {@code null} if not provided.
     */
    public String getRequestBody() {
        return requestBody;
    }
}
