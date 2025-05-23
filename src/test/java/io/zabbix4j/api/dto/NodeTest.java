package io.zabbix4j.api.dto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link Node}.
 * @author ShortRoundDev
 */
class NodeTest {

    @Test
    void testConstructor_valid() {
        Node node1 = new Node("192.168.1.100", 10051);
        assertEquals("192.168.1.100", node1.getAddress());
        assertEquals(10051, node1.getPort());

        Node node2 = new Node("zabbix.example.com", 10050);
        assertEquals("zabbix.example.com", node2.getAddress());
        assertEquals(10050, node2.getPort());
    }

    @Test
    void testConstructor_specialAddress_0_0_0_0_slash_0() {
        Node node = new Node("0.0.0.0/0", 10051);
        assertEquals("127.0.0.1", node.getAddress());
        assertEquals(10051, node.getPort());
    }

    @Test
    void testConstructor_addressTrimming() {
        Node node = new Node("  10.0.0.1\t", 10050);
        assertEquals("10.0.0.1", node.getAddress());
        assertEquals(10050, node.getPort());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t"})
    void testConstructor_invalidAddress_emptyOrBlank(String invalidAddress) {
        assertThrows(IllegalArgumentException.class, () -> new Node(invalidAddress, 10051));
    }

    @Test
    void testConstructor_invalidAddress_null() {
        assertThrows(IllegalArgumentException.class, () -> new Node(null, 10051));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 65536, 100000})
    void testConstructor_invalidPort(int invalidPort) {
        assertThrows(IllegalArgumentException.class, () -> new Node("127.0.0.1", invalidPort));
    }

    @Test
    void testEqualsAndHashCode() {
        Node node1 = new Node("192.168.1.1", 10051);
        Node node2 = new Node("192.168.1.1", 10051);
        Node node3 = new Node("192.168.1.2", 10051); // Different address
        Node node4 = new Node("192.168.1.1", 10050); // Different port

        assertEquals(node1, node2);
        assertEquals(node1.hashCode(), node2.hashCode());

        assertNotEquals(node1, node3);
        assertNotEquals(node1.hashCode(), node3.hashCode()); // Hashcodes can collide, but unlikely for these simple changes
        assertNotEquals(node1, node4);
        assertNotEquals(node1.hashCode(), node4.hashCode());
        assertNotEquals(node1, null);
        assertNotEquals(node1, new Object());
    }

    @Test
    void testToString() {
        Node node = new Node("my.zabbix.server", 12345);
        assertEquals("my.zabbix.server:12345", node.toString());
    }
}
