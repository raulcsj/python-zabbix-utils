package io.zabbix4j.api.exceptions;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ApiRequestException}.
 *
 * @author CSJ
 */
class ApiRequestExceptionTest {

    @Test
    void testConstructor_withMessageOnly() {
        String message = "Simple API request error";
        ApiRequestException exception = new ApiRequestException(message);

        assertEquals(message, exception.getMessage());
        assertNull(exception.getCode());
        assertNull(exception.getData());
        assertNull(exception.getRequestBody());
        assertNull(exception.getCause());
    }

    @Test
    void testConstructor_withDetails() {
        String apiMessage = "Detailed API error";
        Integer code = -32602;
        String data = "Invalid params";
        Object requestBody = new HashMap<>(Map.of("param1", "value1"));

        ApiRequestException exception = new ApiRequestException(apiMessage, code, data, requestBody);

        String expectedMessage = String.format("API Error (code: %s): %s - Data: %s", code, apiMessage, data);
        assertEquals(expectedMessage, exception.getMessage());
        assertEquals(code, exception.getCode());
        assertEquals(data, exception.getData());
        assertSame(requestBody, exception.getRequestBody());
        assertNull(exception.getCause());
    }

    @Test
    void testConstructor_withDetailsAndCause() {
        String apiMessage = "Another API error";
        Integer code = -32000;
        String data = "Server error";
        Object requestBody = "{\"method\":\"test\"}";
        Throwable cause = new RuntimeException("Underlying issue");

        ApiRequestException exception = new ApiRequestException(apiMessage, code, data, requestBody, cause);

        String expectedMessage = String.format("API Error (code: %s): %s - Data: %s", code, apiMessage, data);
        assertEquals(expectedMessage, exception.getMessage());
        assertEquals(code, exception.getCode());
        assertEquals(data, exception.getData());
        assertSame(requestBody, exception.getRequestBody());
        assertSame(cause, exception.getCause());
    }

    @Test
    void testConstructor_withNullDetails() {
        // Test how the message formatting handles nulls for code, apiMessage, data
        ApiRequestException exception = new ApiRequestException(null, null, null, null);
        String expectedMessage = "API Error (code: N/A): No message - Data: N/A";
        assertEquals(expectedMessage, exception.getMessage());
        assertNull(exception.getCode());
        assertNull(exception.getData());
        assertNull(exception.getRequestBody());
    }

    @Test
    void testConstructor_withNullApiMessageAndData() {
        Integer code = -1;
        ApiRequestException exception = new ApiRequestException(null, code, null, null);
        String expectedMessage = String.format("API Error (code: %s): No message - Data: N/A", code);
        assertEquals(expectedMessage, exception.getMessage());
        assertEquals(code, exception.getCode());
    }
}
