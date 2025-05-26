package io.zabbix4j.api.types;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for the {@link Node} class.
 *
 * @author CSJ
 */
class NodeTest {

    @Nested
    @DisplayName("Constructor and Getter Tests")
    class ConstructorAndGetterTests {

        @Test
        @DisplayName("Constructor should set fields correctly for valid inputs")
        void testConstructor_ValidInputs() {
            Node node = new Node("zabbix.example.com", 10051);
            assertNotNull(node, "Node object should be created.");
            assertEquals("zabbix.example.com", node.getAddress(), "Address should match constructor argument.");
            assertEquals(10051, node.getPort(), "Port should match constructor argument.");
        }

        @Test
        @DisplayName("Constructor should convert '0.0.0.0/0' address to '127.0.0.1'")
        void testConstructor_SpecialAddressConversion() {
            Node node = new Node("0.0.0.0/0", 8080);
            assertEquals("127.0.0.1", node.getAddress(), "Address '0.0.0.0/0' should be converted to '127.0.0.1'.");
            assertEquals(8080, node.getPort());
        }
        
        @Test
        @DisplayName("Constructor should trim whitespace from address")
        void testConstructor_AddressTrimming() {
            Node node = new Node("  192.168.1.1  ", 10050);
            assertEquals("192.168.1.1", node.getAddress(), "Address should be trimmed.");
        }


        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {"", "  "})
        @DisplayName("Constructor should throw IllegalArgumentException for invalid address")
        void testConstructor_InvalidAddress(String invalidAddress) {
            Exception e = assertThrows(IllegalArgumentException.class, () -> new Node(invalidAddress, 10051));
            assertEquals("Address cannot be null or empty.", e.getMessage(),
                         "Exception message should indicate address error for input: '" + invalidAddress + "'");
        }

        @ParameterizedTest
        @ValueSource(ints = {0, -1, 65536, 100000})
        @DisplayName("Constructor should throw IllegalArgumentException for invalid port")
        void testConstructor_InvalidPort(int invalidPort) {
            Exception e = assertThrows(IllegalArgumentException.class, () -> new Node("localhost", invalidPort));
            assertEquals("Port number must be between 1 and 65535. Got: " + invalidPort, e.getMessage(),
                         "Exception message should indicate port range error for port: " + invalidPort);
        }

        @Test
        @DisplayName("Getters should return correct values")
        void testGetters() {
            Node node = new Node("my.server", 12345);
            assertEquals("my.server", node.getAddress(), "getAddress() should return the set address.");
            assertEquals(12345, node.getPort(), "getPort() should return the set port.");
        }
    }

    @Test
    @DisplayName("toString() should return 'address:port' format")
    void testToStringMethod() {
        Node node = new Node("server.domain.tld", 80);
        assertEquals("server.domain.tld:80", node.toString(), "toString() format should be 'address:port'.");

        Node nodeLocal = new Node("127.0.0.1", 10051);
        assertEquals("127.0.0.1:10051", nodeLocal.toString());
    }

    @Nested
    @DisplayName("equals() and hashCode() Tests")
    class EqualsAndHashCodeTests {
        Node node1a = new Node("example.com", 10050);
        Node node1b = new Node("example.com", 10050); // Equal to node1a
        Node node2_diffAddress = new Node("another.com", 10050);
        Node node3_diffPort = new Node("example.com", 10051);
        Node node4_specialAddress = new Node("0.0.0.0/0", 10050); // Becomes 127.0.0.1:10050
        Node node5_explicitLocalhost = new Node("127.0.0.1", 10050);


        @Test
        @DisplayName("equals() basic contract")
        void testEquals() {
            assertEquals(node1a, node1a, "An object must be equal to itself.");
            assertEquals(node1a, node1b, "Objects with identical address and port must be equal.");

            assertNotEquals(node1a, node2_diffAddress, "Objects with different address must not be equal.");
            assertNotEquals(node1a, node3_diffPort, "Objects with different port must not be equal.");

            assertNotEquals(node1a, null, "An object must not be equal to null.");
            assertNotEquals(node1a, "example.com:10050", "An object must not be equal to an object of a different type.");

            assertEquals(node4_specialAddress, node5_explicitLocalhost,
                         "'0.0.0.0/0' should be converted and thus equal to '127.0.0.1' with the same port.");
        }

        @Test
        @DisplayName("hashCode() consistency and equality")
        void testHashCode() {
            assertEquals(node1a.hashCode(), node1b.hashCode(), "Equal objects must have equal hash codes.");
            assertEquals(node4_specialAddress.hashCode(), node5_explicitLocalhost.hashCode(),
                         "Hash codes for '0.0.0.0/0' and '127.0.0.1' (same port) should be equal due to address conversion.");
            // It's not strictly required for non-equal objects to have different hash codes,
            // but good if they do. We mainly test that equal objects have equal hash codes.
            assertNotEquals(node1a.hashCode(), node2_diffAddress.hashCode(),
                            "Hashcodes for nodes with different addresses should ideally differ.");
             assertNotEquals(node1a.hashCode(), node3_diffPort.hashCode(),
                            "Hashcodes for nodes with different ports should ideally differ.");
        }
    }
}
