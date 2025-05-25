package io.zabbix4j.api.types;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link Node}.
 *
 * @author CSJ
 */
class NodeTest {

    @Test
    void testConstructor_validInputs() {
        Node node = new Node("192.168.1.1", 10050);
        assertEquals("192.168.1.1", node.getAddress());
        assertEquals(10050, node.getPort());
    }

    @Test
    void testConstructor_addressConversion() {
        Node node = new Node("0.0.0.0/0", 8080);
        assertEquals("127.0.0.1", node.getAddress());
        assertEquals(8080, node.getPort());
    }

    @Test
    void testConstructor_nullAddress_throwsException() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new Node(null, 10050));
        assertEquals("Address cannot be null or empty.", exception.getMessage());
    }

    @Test
    void testConstructor_emptyAddress_throwsException() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new Node("", 10050));
        assertEquals("Address cannot be null or empty.", exception.getMessage());
    }

    @Test
    void testConstructor_blankAddress_throwsException() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new Node("   ", 10050));
        assertEquals("Address cannot be null or empty.", exception.getMessage());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 65536, 100000})
    void testConstructor_invalidPort_throwsException(int invalidPort) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new Node("127.0.0.1", invalidPort));
        assertEquals("Port number must be between 1 and 65535, inclusive. Received: " + invalidPort,
                exception.getMessage());
    }

    @ParameterizedTest
    @CsvSource({"1", "65535"})
    void testConstructor_boundaryPorts(int validPort) {
        Node node = new Node("127.0.0.1", validPort);
        assertEquals(validPort, node.getPort());
    }

    @Test
    void testEqualsAndHashCode() {
        Node node1 = new Node("192.168.1.1", 10050);
        Node node2 = new Node("192.168.1.1", 10050);
        Node node3 = new Node("192.168.1.2", 10050); // Different address
        Node node4 = new Node("192.168.1.1", 10051); // Different port
        Node node5 = new Node("0.0.0.0/0", 80); // Will be 127.0.0.1:80
        Node node6 = new Node("127.0.0.1", 80); // Same as node5 effectively

        // Reflexivity
        assertEquals(node1, node1);

        // Symmetry
        assertEquals(node1, node2);
        assertEquals(node2, node1);

        // Inequality
        assertNotEquals(node1, node3);
        assertNotEquals(node1, node4);
        assertNotEquals(node3, node4);

        // Equality for converted address
        assertEquals(node5, node6);

        // HashCode
        assertEquals(node1.hashCode(), node2.hashCode());
        assertNotEquals(node1.hashCode(), node3.hashCode());
        assertNotEquals(node1.hashCode(), node4.hashCode());
        assertEquals(node5.hashCode(), node6.hashCode());

        // Test with null
        assertNotEquals(null, node1);
    }

    @Test
    void testToString() {
        Node node = new Node("example.com", 12345);
        String str = node.toString();
        assertNotNull(str);
        assertEquals("example.com:12345", str);

        Node nodeConverted = new Node("0.0.0.0/0", 80);
        assertEquals("127.0.0.1:80", nodeConverted.toString());
    }

    @Test
    void testGetters() {
        Node node = new Node("my.zabbix.server", 10051);
        assertEquals("my.zabbix.server", node.getAddress());
        assertEquals(10051, node.getPort());
    }
}
