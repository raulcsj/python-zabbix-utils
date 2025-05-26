package io.zabbix4j.api.types;

import java.util.Objects;

/**
 * Represents a response from a Zabbix Agent.
 * This class parses a raw string response to extract the actual value or an error message.
 * It handles specific "ZBX_NOTSUPPORTED" error codes.
 * This class is immutable.
 *
 * @author Your Name
 */
public final class AgentResponse {

    private static final String ERROR_CODE_NOT_SUPPORTED = "ZBX_NOTSUPPORTED";
    private static final String DEFAULT_NOT_SUPPORTED_MESSAGE = "Not supported by Zabbix Agent";

    private final String raw;
    private final String value;
    private final String error;

    /**
     * Constructs an {@code AgentResponse} object by parsing a raw response string from the Zabbix Agent.
     *
     * @param rawResponse The raw string response from the agent. Cannot be null.
     * @throws IllegalArgumentException if {@code rawResponse} is null.
     */
    public AgentResponse(String rawResponse) {
        if (rawResponse == null) {
            throw new IllegalArgumentException("Raw response cannot be null.");
        }
        this.raw = rawResponse;

        if (ERROR_CODE_NOT_SUPPORTED.equals(rawResponse)) {
            this.value = null;
            this.error = DEFAULT_NOT_SUPPORTED_MESSAGE;
        } else if (rawResponse.startsWith(ERROR_CODE_NOT_SUPPORTED + "\0")) { // Check for null character suffix
            this.value = null;
            // Extract the message part after "ZBX_NOTSUPPORTED\0"
            this.error = rawResponse.substring(ERROR_CODE_NOT_SUPPORTED.length() + 1);
        } else {
            // Check if there's a null character in a non-error response
            int nullCharIndex = rawResponse.indexOf('\0');
            if (nullCharIndex != -1) {
                this.value = rawResponse.substring(0, nullCharIndex);
            } else {
                this.value = rawResponse;
            }
            this.error = null;
        }
    }

    /**
     * Returns the original raw response string received from the agent.
     *
     * @return The raw response string.
     */
    public String getRawResponse() {
        return raw;
    }

    /**
     * Returns the extracted value from the agent's response.
     * This can be {@code null} if the item is not supported or an error occurred.
     *
     * @return The extracted value, or {@code null}.
     */
    public String getValue() {
        return value;
    }

    /**
     * Returns the error message if the agent reported an error or if the item is not supported.
     * This can be {@code null} if the request was successful and a value was retrieved.
     *
     * @return The error message, or {@code null}.
     */
    public String getError() {
        return error;
    }

    /**
     * Checks if this response indicates an error or "not supported" status.
     *
     * @return {@code true} if an error message is present, {@code false} otherwise.
     */
    public boolean hasError() {
        return error != null;
    }

    /**
     * Returns a string representation of this {@code AgentResponse} object,
     * summarizing its value and error state.
     *
     * @return A string representation, e.g., "AgentResponse{value='some_value', error=null}".
     */
    @Override
    public String toString() {
        return "AgentResponse{" +
               "value='" + value + '\'' +
               ", error='" + error + '\'' +
               // Optionally include raw for debugging, but it might be too verbose for typical toString
               // ", raw='" + raw + '\'' +
               '}';
    }

    /**
     * Compares this {@code AgentResponse} with the specified object for equality.
     * The comparison is based on the {@code raw} response string, the extracted {@code value},
     * and the {@code error} message.
     *
     * @param o The object to compare with.
     * @return {@code true} if the objects are equal, {@code false} otherwise.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AgentResponse that = (AgentResponse) o;
        return Objects.equals(raw, that.raw) &&
               Objects.equals(value, that.value) &&
               Objects.equals(error, that.error);
    }

    /**
     * Returns the hash code for this {@code AgentResponse}.
     * The hash code is based on the {@code raw} response string, the extracted {@code value},
     * and the {@code error} message.
     *
     * @return The hash code value for this object.
     */
    @Override
    public int hashCode() {
        return Objects.hash(raw, value, error);
    }
}
