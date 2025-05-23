package io.zabbix4j.api.dto;

import java.util.Objects;

/**
 * Represents a response from a Zabbix Agent.
 * The agent's response can be a simple value, an error message, or an indication of non-support.
 *
 * @author ShortRoundDev
 */
public final class AgentResponse {

    private static final String ZBX_NOTSUPPORTED = "ZBX_NOTSUPPORTED";
    private static final String ZBX_NOTSUPPORTED_ERROR_PREFIX = ZBX_NOTSUPPORTED + "\0";

    private final String rawValue;
    private final String value; // Can be null if there was an error or not supported
    private final String error; // Can be null if the query was successful

    /**
     * Constructs an AgentResponse by parsing the raw string returned by the Zabbix Agent.
     *
     * @param rawResponseFromAgent The raw string response from the agent.
     * @throws IllegalArgumentException if rawResponseFromAgent is null.
     */
    public AgentResponse(String rawResponseFromAgent) {
        if (rawResponseFromAgent == null) {
            throw new IllegalArgumentException("Raw response from agent cannot be null.");
        }
        this.rawValue = rawResponseFromAgent;

        if (rawResponseFromAgent.equals(ZBX_NOTSUPPORTED)) {
            this.value = null;
            this.error = "Not supported by Zabbix Agent";
        } else if (rawResponseFromAgent.startsWith(ZBX_NOTSUPPORTED_ERROR_PREFIX)) {
            this.value = null;
            this.error = rawResponseFromAgent.substring(ZBX_NOTSUPPORTED_ERROR_PREFIX.length());
        } else {
            // Check for a null character to separate value from potential (though unusual) extra data
            int nullCharIndex = rawResponseFromAgent.indexOf('\0');
            if (nullCharIndex != -1) {
                this.value = rawResponseFromAgent.substring(0, nullCharIndex);
                // What to do with text after null char? The Python code seems to imply it's not part of the error.
                // For now, we assume the value is only up to the first null character if one exists.
            } else {
                this.value = rawResponseFromAgent;
            }
            this.error = null;
        }
    }

    /**
     * @return The original raw string received from the Zabbix Agent.
     */
    public String getRawValue() {
        return rawValue;
    }

    /**
     * @return The actual value returned by the agent, or {@code null} if the item is not supported
     * or an error occurred.
     */
    public String getValue() {
        return value;
    }

    /**
     * @return An error message if the agent reported an error or if the item is not supported.
     * Returns {@code null} if the query was successful and a value was obtained.
     */
    public String getError() {
        return error;
    }

    /**
     * Checks if the agent reported an error or if the item is not supported.
     * @return {@code true} if there is an error message, {@code false} otherwise.
     */
    public boolean hasError() {
        return this.error != null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AgentResponse that = (AgentResponse) o;
        return Objects.equals(rawValue, that.rawValue) && // Comparing rawValue is most direct
               Objects.equals(value, that.value) &&
               Objects.equals(error, that.error);
    }

    @Override
    public int hashCode() {
        return Objects.hash(rawValue, value, error);
    }

    @Override
    public String toString() {
        return "AgentResponse{" +
               "rawValue='" + rawValue + '\'' +
               (value != null ? ", value='" + value + '\'' : "") +
               (error != null ? ", error='" + error + '\'' : "") +
               '}';
    }
}
