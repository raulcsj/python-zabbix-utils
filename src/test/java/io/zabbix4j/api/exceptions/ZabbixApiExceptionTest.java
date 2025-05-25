package io.zabbix4j.api.exceptions;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Unit tests for {@link ZabbixApiException}.
 *
 * @author CSJ
 */
class ZabbixApiExceptionTest {

    @Test
    void testConstructor_withMessage() {
        String message = "Test Zabbix API Exception";
        ZabbixApiException exception = new ZabbixApiException(message);

        assertEquals(message, exception.getMessage());
        assertNull(exception.getCause());
    }

    @Test
    void testConstructor_withMessageAndCause() {
        String message = "Test Zabbix API Exception with cause";
        Throwable cause = new RuntimeException("Root cause");
        ZabbixApiException exception = new ZabbixApiException(message, cause);

        assertEquals(message, exception.getMessage());
        assertSame(cause, exception.getCause());
    }

    @Test
    void testConstructor_withNullMessage() {
        // While not typical, Exception class allows null message
        ZabbixApiException exception = new ZabbixApiException(null);
        assertNull(exception.getMessage());
    }

    @Test
    void testConstructor_withNullCause() {
        String message = "Test with null cause";
        ZabbixApiException exception = new ZabbixApiException(message, null);
        assertEquals(message, exception.getMessage());
        assertNull(exception.getCause());
    }
}
