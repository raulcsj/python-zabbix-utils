package io.zabbix4j.api.types;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@link Cluster} class.
 *
 * @author CSJ
 */
class ClusterTest {

    @Nested
    @DisplayName("Constructor and getNodes() Tests")
    class ConstructorAndGetNodesTests {

        @Test
        @DisplayName("Constructor should create correct Node objects for valid input list")
        void testConstructor_ValidNodeStrings() {
            List<String> nodeStrings = Arrays.asList("host1:10051", "host2", "host3:10050", "  host4:10052  ", "0.0.0.0/0:10053");
            Cluster cluster = new Cluster(nodeStrings);
            assertNotNull(cluster.getNodes(), "Nodes list should not be null.");
            assertEquals(5, cluster.getNodes().size(), "Nodes list size should match input.");

            // Verify individual nodes
            assertEquals(new Node("host1", 10051), cluster.getNodes().get(0));
            assertEquals(new Node("host2", Cluster.DEFAULT_ZABBIX_PORT), cluster.getNodes().get(1),
                         "Node without port should use default port.");
            assertEquals(new Node("host3", 10050), cluster.getNodes().get(2));
            assertEquals(new Node("host4", 10052), cluster.getNodes().get(3), "Node string should be trimmed.");
            assertEquals(new Node("127.0.0.1", 10053), cluster.getNodes().get(4), "'0.0.0.0/0' should be converted.");
        }

        @Test
        @DisplayName("Constructor should throw IllegalArgumentException for null node strings list")
        void testConstructor_NullNodeStringsList() {
            Exception e = assertThrows(IllegalArgumentException.class, () -> new Cluster(null));
            assertEquals("Node strings list cannot be null or empty.", e.getMessage());
        }

        @Test
        @DisplayName("Constructor should throw IllegalArgumentException for empty node strings list")
        void testConstructor_EmptyNodeStringsList() {
            Exception e = assertThrows(IllegalArgumentException.class, () -> new Cluster(Collections.emptyList()));
            assertEquals("Node strings list cannot be null or empty.", e.getMessage());
        }

        @Test
        @DisplayName("Constructor should throw IllegalArgumentException for list containing null node string")
        void testConstructor_ListWithNullNodeString() {
            List<String> nodeStrings = Arrays.asList("host1:10051", null, "host2:10050");
            Exception e = assertThrows(IllegalArgumentException.class, () -> new Cluster(nodeStrings));
            assertEquals("Node string in the list cannot be null or empty.", e.getMessage());
        }

        @ParameterizedTest
        @NullAndEmptySource // Covers null (already tested above) and empty string
        @ValueSource(strings = {"  "}) // Covers blank string
        @DisplayName("Constructor should throw IllegalArgumentException for list containing empty or blank node string")
        void testConstructor_ListWithEmptyOrBlankNodeString(String invalidNodeString) {
             // Need to handle NullAndEmptySource providing null to a List.of
            List<String> nodeStrings;
            if (invalidNodeString == null) {
                 nodeStrings = Arrays.asList("host1:10051", null); // Explicitly test null in list
            } else {
                 nodeStrings = Arrays.asList("host1:10051", invalidNodeString);
            }
            Exception e = assertThrows(IllegalArgumentException.class, () -> new Cluster(nodeStrings));
            assertEquals("Node string in the list cannot be null or empty.", e.getMessage());
        }
        
        @Test
        @DisplayName("Constructor should throw IllegalArgumentException for list with node string with bad port")
        void testConstructor_ListWithInvalidNodeStringFormat_BadPort() {
            List<String> nodeStrings = Collections.singletonList("host1:badport");
            Exception e = assertThrows(IllegalArgumentException.class, () -> new Cluster(nodeStrings));
            assertTrue(e.getMessage().contains("Invalid port number in node string"), "Exception message should indicate port format error.");
        }

        @Test
        @DisplayName("Constructor should throw IllegalArgumentException for list with node string missing port after colon")
        void testConstructor_ListWithInvalidNodeStringFormat_MissingPortAfterColon() {
            List<String> nodeStrings = Collections.singletonList("host1:");
            Exception e = assertThrows(IllegalArgumentException.class, () -> new Cluster(nodeStrings));
            assertTrue(e.getMessage().contains("Invalid node format: port missing after colon"), "Exception message should indicate missing port.");
        }
        
        @Test
        @DisplayName("Constructor should throw IllegalArgumentException for list with node string with out-of-range port")
        void testConstructor_ListWithInvalidNodeStringFormat_PortOutOfRange() {
            List<String> nodeStrings = Collections.singletonList("host1:70000");
            Exception e = assertThrows(IllegalArgumentException.class, () -> new Cluster(nodeStrings));
            assertTrue(e.getMessage().contains("Port number must be between 1 and 65535"), "Node's constructor should throw for out-of-range port.");
        }


        @Test
        @DisplayName("getNodes() should return an unmodifiable list")
        void testNodesListIsUnmodifiable() {
            Cluster cluster = new Cluster(Collections.singletonList("host1:10051"));
            List<Node> nodes = cluster.getNodes();
            assertThrows(UnsupportedOperationException.class, () -> nodes.add(new Node("another", 10050)),
                         "List returned by getNodes() should be unmodifiable.");
            assertThrows(UnsupportedOperationException.class, nodes::clear,
                         "List returned by getNodes() should be unmodifiable.");
        }
    }

    @Test
    @DisplayName("toString() should return string representation of the internal list of nodes")
    void testToStringMethod() {
        Cluster cluster1 = new Cluster(Arrays.asList("serverA:10051", "serverB"));
        // Expected format relies on List.toString() and Node.toString()
        String expected1 = "[serverA:10051, serverB:" + Cluster.DEFAULT_ZABBIX_PORT + "]";
        assertEquals(expected1, cluster1.toString(), "toString() output mismatch for cluster1.");

        Cluster cluster2 = new Cluster(Collections.singletonList("127.0.0.1:10050"));
        String expected2 = "[127.0.0.1:10050]";
        assertEquals(expected2, cluster2.toString(), "toString() output mismatch for cluster2.");
    }

    @Nested
    @DisplayName("equals() and hashCode() Tests")
    class EqualsAndHashCodeTests {
        Cluster cluster1a = new Cluster(Arrays.asList("hostA:10051", "hostB:" + Cluster.DEFAULT_ZABBIX_PORT));
        Cluster cluster1b = new Cluster(Arrays.asList("hostA:10051", "hostB")); // Same as 1a due to default port
        Cluster cluster2_diffOrder = new Cluster(Arrays.asList("hostB", "hostA:10051"));
        Cluster cluster3_diffNode = new Cluster(Arrays.asList("hostA:10051", "hostC:10050"));
        Cluster cluster4_subset = new Cluster(Collections.singletonList("hostA:10051"));

        @Test
        @DisplayName("equals() basic contract")
        void testEquals() {
            assertEquals(cluster1a, cluster1a, "An object must be equal to itself.");
            assertEquals(cluster1a, cluster1b, "Clusters with same nodes in same order must be equal.");

            assertNotEquals(cluster1a, cluster2_diffOrder, "Clusters with same nodes in different order must not be equal.");
            assertNotEquals(cluster1a, cluster3_diffNode, "Clusters with different nodes must not be equal.");
            assertNotEquals(cluster1a, cluster4_subset, "Cluster must not be equal to its subset.");

            assertNotEquals(cluster1a, null, "An object must not be equal to null.");
            assertNotEquals(cluster1a, "some string", "An object must not be equal to an object of a different type.");
        }

        @Test
        @DisplayName("hashCode() consistency and equality")
        void testHashCode() {
            assertEquals(cluster1a.hashCode(), cluster1b.hashCode(), "Equal objects must have equal hash codes.");
            // Not strictly required for non-equal objects to have different hash codes, but good if they do.
            // For example, cluster1a and cluster2_diffOrder might have different hash codes because List.hashCode() is order-sensitive.
            assertNotEquals(cluster1a.hashCode(), cluster2_diffOrder.hashCode(),
                            "Hash codes for clusters with nodes in different order should ideally differ.");
        }
    }
}
