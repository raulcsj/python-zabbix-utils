package io.zabbix4j.api.types;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AgentResponse}.
 *
 * @author CSJ
 */
class AgentResponseTest {

    @Test
    void testConstructor_plainValue() {
        String raw = "123.45";
        AgentResponse response = new AgentResponse(raw);
        assertEquals(raw, response.getRawResponse());
        assertEquals("123.45", response.getValue());
        assertNull(response.getError());
        assertFalse(response.hasError());
    }

    @Test
    void testConstructor_valueWithNullTerminator() {
        String raw = "some value\0more data";
        AgentResponse response = new AgentResponse(raw);
        assertEquals(raw, response.getRawResponse());
        assertEquals("some value", response.getValue());
        assertNull(response.getError());
        assertFalse(response.hasError());
    }

    @Test
    void testConstructor_zbxNotSupported_simple() {
        String raw = "ZBX_NOTSUPPORTED";
        AgentResponse response = new AgentResponse(raw);
        assertEquals(raw, response.getRawResponse());
        assertNull(response.getValue());
        assertEquals("Not supported by Zabbix Agent", response.getError());
        assertTrue(response.hasError());
    }

    @Test
    void testConstructor_zbxNotSupported_withMessage() {
        String raw = "ZBX_NOTSUPPORTED\0Detailed error: Item not found.";
        AgentResponse response = new AgentResponse(raw);
        assertEquals(raw, response.getRawResponse());
        assertNull(response.getValue());
        assertEquals("Detailed error: Item not found.", response.getError());
        assertTrue(response.hasError());
    }

    @Test
    void testConstructor_zbxNotSupported_emptyMessageAfterNull() {
        String raw = "ZBX_NOTSUPPORTED\0";
        AgentResponse response = new AgentResponse(raw);
        assertEquals(raw, response.getRawResponse());
        assertNull(response.getValue());
        assertEquals("", response.getError()); // Message is empty
        assertTrue(response.hasError());
    }


    @Test
    void testConstructor_nullInput_throwsException() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new AgentResponse(null));
        assertEquals("Raw response cannot be null.", exception.getMessage());
    }

    @Test
    void testConstructor_emptyStringInput() {
        // An empty string is a valid "value" if no ZBX_NOTSUPPORTED prefix.
        String raw = "";
        AgentResponse response = new AgentResponse(raw);
        assertEquals(raw, response.getRawResponse());
        assertEquals("", response.getValue());
        assertNull(response.getError());
        assertFalse(response.hasError());
    }

    @Test
    void testEqualsAndHashCode() {
        AgentResponse r1 = new AgentResponse("value1");
        AgentResponse r2 = new AgentResponse("value1");
        AgentResponse r3 = new AgentResponse("value2");
        AgentResponse r4 = new AgentResponse("ZBX_NOTSUPPORTED");
        AgentResponse r5 = new AgentResponse("ZBX_NOTSUPPORTED");
        AgentResponse r6 = new AgentResponse("ZBX_NOTSUPPORTED\0Some error");
        AgentResponse r7 = new AgentResponse("ZBX_NOTSUPPORTED\0Some other error");

        // Reflexivity
        assertEquals(r1, r1);

        // Symmetry
        assertEquals(r1, r2);
        assertEquals(r2, r1);
        assertEquals(r4, r5);
        assertEquals(r5, r4);


        // Inequality
        assertNotEquals(r1, r3); // Different value
        assertNotEquals(r1, r4); // Different type of response (value vs error)
        assertNotEquals(r4, r6); // Different error message
        assertNotEquals(r6, r7); // Different error message detail

        // HashCode
        assertEquals(r1.hashCode(), r2.hashCode());
        assertNotEquals(r1.hashCode(), r3.hashCode());
        assertEquals(r4.hashCode(), r5.hashCode());
        assertNotEquals(r4.hashCode(), r6.hashCode());

        // Test with null
        assertNotEquals(null, r1);
    }

    @Test
    void testToString() {
        AgentResponse responseValue = new AgentResponse("my_value");
        String strValue = responseValue.toString();
        assertNotNull(strValue);
        assertTrue(strValue.contains("rawResponse='my_value'"));
        assertTrue(strValue.contains("value='my_value'"));
        assertTrue(strValue.contains("error='null'"));


        AgentResponse responseError = new AgentResponse("ZBX_NOTSUPPORTED\0Custom error info.");
        String strError = responseError.toString();
        assertNotNull(strError);
        assertTrue(strError.contains("rawResponse='ZBX_NOTSUPPORTED\0Custom error info.'"));
        assertTrue(strError.contains("value='null'"));
        assertTrue(strError.contains("error='Custom error info.'"));
    }

    @Test
    void testGetters_allStates() {
        // Plain value
        AgentResponse rPlain = new AgentResponse("data");
        assertEquals("data", rPlain.getRawResponse());
        assertEquals("data", rPlain.getValue());
        assertNull(rPlain.getError());
        assertFalse(rPlain.hasError());

        // ZBX_NOTSUPPORTED simple
        AgentResponse rNotSupported = new AgentResponse("ZBX_NOTSUPPORTED");
        assertEquals("ZBX_NOTSUPPORTED", rNotSupported.getRawResponse());
        assertNull(rNotSupported.getValue());
        assertEquals("Not supported by Zabbix Agent", rNotSupported.getError());
        assertTrue(rNotSupported.hasError());

        // ZBX_NOTSUPPORTED with message
        AgentResponse rNotSupportedMsg = new AgentResponse("ZBX_NOTSUPPORTED\0Error details here");
        assertEquals("ZBX_NOTSUPPORTED\0Error details here", rNotSupportedMsg.getRawResponse());
        assertNull(rNotSupportedMsg.getValue());
        assertEquals("Error details here", rNotSupportedMsg.getError());
        assertTrue(rNotSupportedMsg.hasError());
    }
}
