package io.zabbix4j.api.exceptions;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Unit tests for {@link ProcessingException}.
 *
 * @author CSJ
 */
class ProcessingExceptionTest {

    @Test
    void testConstructor_withMessage() {
        String message = "Processing error occurred";
        ProcessingException exception = new ProcessingException(message);

        assertEquals(message, exception.getMessage());
        assertNull(exception.getCause());
    }

    @Test
    void testConstructor_withMessageAndCause() {
        String message = "Processing error with underlying cause";
        Throwable cause = new NullPointerException("Specific reason");
        ProcessingException exception = new ProcessingException(message, cause);

        assertEquals(message, exception.getMessage());
        assertSame(cause, exception.getCause());
    }

    @Test
    void testConstructor_withNullMessage() {
        ProcessingException exception = new ProcessingException(null);
        assertNull(exception.getMessage());
    }

    @Test
    void testConstructor_withNullCause() {
        String message = "Processing error, no specific cause object";
        ProcessingException exception = new ProcessingException(message, null);
        assertEquals(message, exception.getMessage());
        assertNull(exception.getCause());
    }
}
