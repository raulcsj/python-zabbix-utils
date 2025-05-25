package io.zabbix4j.api.common;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ZabbixApiConstants}.
 *
 * @author CSJ
 */
class ZabbixApiConstantsTest {

    @Test
    void testConstantsValues() {
        assertEquals("********", ZabbixApiConstants.HIDING_MASK);
        assertEquals("api_jsonrpc.php", ZabbixApiConstants.JSONRPC_FILE);
        assertEquals("token", ZabbixApiConstants.FIELD_TOKEN);
        assertEquals("auth", ZabbixApiConstants.FIELD_AUTH);
        assertEquals("passwd", ZabbixApiConstants.FIELD_PASSWD);
        assertEquals("sessionid", ZabbixApiConstants.FIELD_SESSIONID);
        assertEquals("password", ZabbixApiConstants.FIELD_PASSWORD);
        assertEquals("current_passwd", ZabbixApiConstants.FIELD_CURRENT_PASSWD);
        assertEquals("result", ZabbixApiConstants.FIELD_RESULT);
        assertEquals("1.0.0-SNAPSHOT", ZabbixApiConstants.CLIENT_LIB_VERSION); // As added in a previous task
    }

    @Test
    void testUnauthenticatedMethodsSet() {
        Set<String> methods = ZabbixApiConstants.UNAUTHENTICATED_METHODS;
        assertTrue(methods.contains("apiinfo.version"));
        assertTrue(methods.contains("user.login"));
        assertTrue(methods.contains("user.checkAuthentication"));
        assertEquals(3, methods.size()); // Ensure no extra methods slipped in

        // Test unmodifiability
        assertThrows(UnsupportedOperationException.class, () -> methods.add("another.method"));
        assertThrows(UnsupportedOperationException.class, () -> methods.remove("user.login"));
    }

    @Test
    void testFileContentMethodsSet() {
        Set<String> methods = ZabbixApiConstants.FILE_CONTENT_METHODS;
        assertTrue(methods.contains("configuration.export"));
        assertEquals(1, methods.size()); // Ensure no extra methods slipped in

        // Test unmodifiability
        assertThrows(UnsupportedOperationException.class, () -> methods.add("another.method"));
        assertThrows(UnsupportedOperationException.class, () -> methods.remove("configuration.export"));
    }

    @Test
    void testPrivateConstructor() throws NoSuchMethodException {
        Constructor<ZabbixApiConstants> constructor = ZabbixApiConstants.class.getDeclaredConstructor();
        assertTrue(Modifier.isPrivate(constructor.getModifiers()));
        constructor.setAccessible(true);
        assertThrows(InvocationTargetException.class, () -> {
            try {
                constructor.newInstance();
            } catch (InvocationTargetException e) {
                // Check if the cause is UnsupportedOperationException
                if (e.getCause() instanceof UnsupportedOperationException) {
                    throw e; // Re-throw to be caught by assertThrows
                }
                throw new RuntimeException("Unexpected cause of InvocationTargetException", e.getCause());
            }
        }, "Constructor should throw UnsupportedOperationException");
    }
}
