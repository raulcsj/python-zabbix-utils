package io.zabbix4j.api.types;

import java.util.Objects;

/**
 * Represents a response from a Zabbix Agent.
 * <p>
 * This class parses the raw string response from a Zabbix Agent to extract
 * the item value and any potential error messages. It handles specific Zabbix
 * error codes like "ZBX_NOTSUPPORTED".
 * </p>
 * This class is immutable.
 *
 * @author CSJ
 */
public final class AgentResponse {

    private final String rawResponse;
    private final String value;
    private final String error;

    private static final String ZBX_NOTSUPPORTED = "ZBX_NOTSUPPORTED";
    private static final String ZBX_NOTSUPPORTED_PREFIX = ZBX_NOTSUPPORTED + "\0"; // Null character

    /**
     * Constructs an {@code AgentResponse} instance by parsing the raw response string
     * from a Zabbix Agent.
     *
     * @param rawResponse The raw string response from the Zabbix Agent.
     * @throws IllegalArgumentException if {@code rawResponse} is null.
     */
    public AgentResponse(String rawResponse) {
        if (rawResponse == null) {
            throw new IllegalArgumentException("Raw response cannot be null.");
        }
        this.rawResponse = rawResponse;

        if (ZBX_NOTSUPPORTED.equals(rawResponse)) {
            this.value = null;
            this.error = "Not supported by Zabbix Agent";
        } else if (rawResponse.startsWith(ZBX_NOTSUPPORTED_PREFIX)) {
            this.value = null;
            this.error = rawResponse.substring(ZBX_NOTSUPPORTED_PREFIX.length());
        } else {
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
     * Gets the original raw response string from the Zabbix Agent.
     *
     * @return The raw response string.
     */
    public String getRawResponse() {
        return rawResponse;
    }

    /**
     * Gets the extracted item value from the agent's response.
     * This can be {@code null} if the item is not supported, an error occurred,
     * or the response format did not yield a specific value part.
     *
     * @return The extracted item value, or {@code null}.
     */
    public String getValue() {
        return value;
    }

    /**
     * Gets the error message, if any, from the agent's response.
     * This will be {@code null} if the request was successful and no error was reported.
     *
     * @return The error message, or {@code null}.
     */
    public String getError() {
        return error;
    }

    /**
     * Checks if an error was reported by the Zabbix Agent.
     *
     * @return {@code true} if an error message is present, {@code false} otherwise.
     */
    public boolean hasError() {
        return this.error != null;
    }

    /**
     * Returns a string representation of this {@code AgentResponse} object.
     * The format is JSON-like, showing the raw response, extracted value, and error.
     *
     * @return A string representation of the object.
     */
    @Override
    public String toString() {
        return "AgentResponse{" +
                "rawResponse='" + rawResponse + '\'' +
                ", value='" + value + '\'' +
                ", error='" + error + '\'' +
                '}';
    }

    /**
     * Compares this {@code AgentResponse} with the specified object for equality.
     * Two {@code AgentResponse} objects are considered equal if their raw response,
     * value, and error fields are all equal.
     *
     * @param o The object to be compared for equality with this {@code AgentResponse}.
     * @return {@code true} if the specified object is equal to this {@code AgentResponse}.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AgentResponse that = (AgentResponse) o;
        return Objects.equals(rawResponse, that.rawResponse) &&
                Objects.equals(value, that.value) &&
                Objects.equals(error, that.error);
    }

    /**
     * Returns the hash code value for this {@code AgentResponse}.
     * The hash code is based on the raw response, value, and error fields.
     *
     * @return The hash code value for this {@code AgentResponse}.
     */
    @Override
    public int hashCode() {
        return Objects.hash(rawResponse, value, error);
    }
}
