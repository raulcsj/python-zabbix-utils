package io.zabbix4j.api.exceptions;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ApiNotSupportedException}.
 *
 * @author CSJ
 */
class ApiNotSupportedExceptionTest {

    @Test
    void testConstructor_withFeatureDescription() {
        String feature = "New Fancy Feature";
        ApiNotSupportedException exception = new ApiNotSupportedException(feature);

        assertEquals(String.format("'%s' is not supported.", feature), exception.getMessage());
        assertEquals(feature, exception.getFeatureDescription());
        assertNull(exception.getZabbixVersion());
        assertNull(exception.getCause());
    }

    @Test
    void testConstructor_withFeatureAndVersion() {
        String feature = "Old Obsolete Feature";
        String version = "3.0";
        ApiNotSupportedException exception = new ApiNotSupportedException(feature, version);

        assertEquals(String.format("'%s' is not supported by Zabbix version '%s'.", feature, version), exception.getMessage());
        assertEquals(feature, exception.getFeatureDescription());
        assertEquals(version, exception.getZabbixVersion());
        assertNull(exception.getCause());
    }

    @Test
    void testConstructor_withFeatureAndCause() {
        String feature = "Another Feature";
        Throwable cause = new UnsupportedOperationException("Underlying reason");
        ApiNotSupportedException exception = new ApiNotSupportedException(feature, cause);

        assertEquals(String.format("'%s' is not supported.", feature), exception.getMessage());
        assertEquals(feature, exception.getFeatureDescription());
        assertNull(exception.getZabbixVersion());
        assertSame(cause, exception.getCause());
    }

    @Test
    void testConstructor_withFeatureVersionAndCause() {
        String feature = "Complex Feature";
        String version = "7.0-alpha";
        Throwable cause = new IllegalStateException("State issue");
        ApiNotSupportedException exception = new ApiNotSupportedException(feature, version, cause);

        assertEquals(String.format("'%s' is not supported by Zabbix version '%s'.", feature, version), exception.getMessage());
        assertEquals(feature, exception.getFeatureDescription());
        assertEquals(version, exception.getZabbixVersion());
        assertSame(cause, exception.getCause());
    }

    @Test
    void testGetters() {
        String feature = "Test Feature";
        String version = "1.0";
        ApiNotSupportedException exWithVersion = new ApiNotSupportedException(feature, version);
        assertEquals(feature, exWithVersion.getFeatureDescription());
        assertEquals(version, exWithVersion.getZabbixVersion());

        ApiNotSupportedException exWithoutVersion = new ApiNotSupportedException(feature);
        assertEquals(feature, exWithoutVersion.getFeatureDescription());
        assertNull(exWithoutVersion.getZabbixVersion());
    }
}
