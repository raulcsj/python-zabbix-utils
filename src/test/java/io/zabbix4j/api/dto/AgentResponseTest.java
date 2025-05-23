package io.zabbix4j.api.dto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AgentResponse}.
 * @author ShortRoundDev
 */
class AgentResponseTest {

    @Test
    void testConstructor_plainValue() {
        String raw = "some value";
        AgentResponse response = new AgentResponse(raw);
        assertEquals(raw, response.getRawValue());
        assertEquals("some value", response.getValue());
        assertNull(response.getError());
        assertFalse(response.hasError());
    }

    @Test
    void testConstructor_plainValueWithNullChar() {
        // This scenario is less common from actual Zabbix agents but tests parsing robustness
        String raw = "value\0extra";
        AgentResponse response = new AgentResponse(raw);
        assertEquals(raw, response.getRawValue());
        assertEquals("value", response.getValue()); // Should only take up to null char
        assertNull(response.getError());
        assertFalse(response.hasError());
    }

    @Test
    void testConstructor_zbxNotSupported_simple() {
        String raw = "ZBX_NOTSUPPORTED";
        AgentResponse response = new AgentResponse(raw);
        assertEquals(raw, response.getRawValue());
        assertNull(response.getValue());
        assertEquals("Not supported by Zabbix Agent", response.getError());
        assertTrue(response.hasError());
    }

    @Test
    void testConstructor_zbxNotSupported_withMessage() {
        String raw = "ZBX_NOTSUPPORTED\0Some detailed error message for why it's not supported.";
        AgentResponse response = new AgentResponse(raw);
        assertEquals(raw, response.getRawValue());
        assertNull(response.getValue());
        assertEquals("Some detailed error message for why it's not supported.", response.getError());
        assertTrue(response.hasError());
    }

    @Test
    void testConstructor_zbxNotSupported_withEmptyMessageAfterNullChar() {
        String raw = "ZBX_NOTSUPPORTED\0";
        AgentResponse response = new AgentResponse(raw);
        assertEquals(raw, response.getRawValue());
        assertNull(response.getValue());
        assertEquals("", response.getError()); // Message is empty
        assertTrue(response.hasError());
    }

    @Test
    void testConstructor_emptyString() {
        String raw = "";
        AgentResponse response = new AgentResponse(raw);
        assertEquals(raw, response.getRawValue());
        assertEquals("", response.getValue());
        assertNull(response.getError());
        assertFalse(response.hasError());
    }

    @Test
    void testConstructor_nullRawResponse() {
        assertThrows(IllegalArgumentException.class, () -> new AgentResponse(null));
    }

    @Test
    void testEqualsAndHashCode() {
        AgentResponse r1 = new AgentResponse("value1");
        AgentResponse r2 = new AgentResponse("value1");
        AgentResponse r3 = new AgentResponse("value2");
        AgentResponse r4 = new AgentResponse("ZBX_NOTSUPPORTED");
        AgentResponse r5 = new AgentResponse("ZBX_NOTSUPPORTED");
        AgentResponse r6 = new AgentResponse("ZBX_NOTSUPPORTED\0Error details");

        assertEquals(r1, r2); // Based on rawValue, value, and error
        assertEquals(r1.hashCode(), r2.hashCode());

        assertNotEquals(r1, r3);
        assertNotEquals(r1.hashCode(), r3.hashCode());

        assertEquals(r4, r5); // Both simple ZBX_NOTSUPPORTED
        assertEquals(r4.hashCode(), r5.hashCode());

        assertNotEquals(r4, r6); // Different error message details
        assertNotEquals(r4.hashCode(), r6.hashCode());

        assertNotEquals(r1, r4); // Different type of response (value vs error)
        assertNotEquals(r1, null);
        assertNotEquals(r1, new Object());
    }

    @Test
    void testToString() {
        AgentResponse rValue = new AgentResponse("my_value");
        String strValue = rValue.toString();
        assertTrue(strValue.contains("rawValue='my_value'"));
        assertTrue(strValue.contains("value='my_value'"));
        assertFalse(strValue.contains("error="));

        AgentResponse rError = new AgentResponse("ZBX_NOTSUPPORTED\0Custom Error");
        String strError = rError.toString();
        assertTrue(strError.contains("rawValue='ZBX_NOTSUPPORTED\0Custom Error'"));
        assertFalse(strError.contains("value=")); // Value should be null
        assertTrue(strError.contains("error='Custom Error'"));
    }
}
