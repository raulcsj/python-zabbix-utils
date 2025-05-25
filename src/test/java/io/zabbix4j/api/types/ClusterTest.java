package io.zabbix4j.api.types;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

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
 * Unit tests for {@link Cluster}.
 *
 * @author CSJ
 */
class ClusterTest {

    @Test
    void testConstructor_validNodeStrings() {
        List<String> nodeStrings = Arrays.asList("host1:10051", "host2", "192.168.1.1:10050");
        Cluster cluster = new Cluster(nodeStrings);

        List<Node> nodes = cluster.getNodes();
        assertNotNull(nodes);
        assertEquals(3, nodes.size());

        assertEquals(new Node("host1", 10051), nodes.get(0));
        assertEquals(new Node("host2", 10051), nodes.get(1)); // Default port
        assertEquals(new Node("192.168.1.1", 10050), nodes.get(2));
    }

    @Test
    void testConstructor_singleNode_defaultPort() {
        Cluster cluster = new Cluster(Collections.singletonList("zabbix.example.com"));
        List<Node> nodes = cluster.getNodes();
        assertEquals(1, nodes.size());
        assertEquals(new Node("zabbix.example.com", 10051), nodes.get(0));
    }

    @Test
    void testConstructor_addressConversionInNode() {
        Cluster cluster = new Cluster(Collections.singletonList("0.0.0.0/0:8080"));
        List<Node> nodes = cluster.getNodes();
        assertEquals(1, nodes.size());
        assertEquals(new Node("127.0.0.1", 8080), nodes.get(0));
    }


    @Test
    void testConstructor_nullNodeStrings_throwsException() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new Cluster(null));
        assertEquals("Node strings list cannot be null or empty.", exception.getMessage());
    }

    @Test
    void testConstructor_emptyNodeStrings_throwsException() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new Cluster(Collections.emptyList()));
        assertEquals("Node strings list cannot be null or empty.", exception.getMessage());
    }

    @Test
    void testConstructor_listWithNullNodeString_throwsException() {
        List<String> nodeStrings = Arrays.asList("host1", null, "host2");
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new Cluster(nodeStrings));
        assertEquals("Node string cannot be null or empty in the list.", exception.getMessage());
    }

    @Test
    void testConstructor_listWithEmptyNodeString_throwsException() {
        List<String> nodeStrings = Arrays.asList("host1", "", "host2");
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new Cluster(nodeStrings));
        assertEquals("Node string cannot be null or empty in the list.", exception.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"host1:badport", "host1:", ":10050", "host1:0", "host1:65536"})
    void testConstructor_malformedNodeString_throwsException(String malformedNode) {
        List<String> nodeStrings = Collections.singletonList(malformedNode);
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new Cluster(nodeStrings));
        assertTrue(exception.getMessage().startsWith("Failed to create Node from string: '" + malformedNode + "'."));
    }

    @Test
    void testGetNodes_returnsUnmodifiableList() {
        Cluster cluster = new Cluster(Collections.singletonList("host1"));
        List<Node> nodes = cluster.getNodes();

        // Attempt to modify the list
        assertThrows(UnsupportedOperationException.class, () -> nodes.add(new Node("host2", 10051)));
        assertThrows(UnsupportedOperationException.class, () -> nodes.remove(0));
    }

    @Test
    void testEqualsAndHashCode() {
        Cluster cluster1 = new Cluster(Arrays.asList("host1:10050", "host2"));
        Cluster cluster2 = new Cluster(Arrays.asList("host1:10050", "host2")); // Same as cluster1
        Cluster cluster3 = new Cluster(Arrays.asList("host1:10050", "host3")); // Different second node
        Cluster cluster4 = new Cluster(Collections.singletonList("host1:10050")); // Fewer nodes
        Cluster cluster5 = new Cluster(Arrays.asList("host2", "host1:10050")); // Same nodes, different order

        // Reflexivity
        assertEquals(cluster1, cluster1);

        // Symmetry
        assertEquals(cluster1, cluster2);
        assertEquals(cluster2, cluster1);

        // Inequality
        assertNotEquals(cluster1, cluster3);
        assertNotEquals(cluster1, cluster4);
        assertNotEquals(cluster1, cluster5); // Order matters for equality

        // HashCode
        assertEquals(cluster1.hashCode(), cluster2.hashCode());
        assertNotEquals(cluster1.hashCode(), cluster3.hashCode());
        assertNotEquals(cluster1.hashCode(), cluster5.hashCode());

        // Test with null
        assertNotEquals(null, cluster1);
    }

    @Test
    void testToString() {
        Cluster cluster = new Cluster(Arrays.asList("serverA:1234", "serverB"));
        String str = cluster.toString();
        assertNotNull(str);
        assertTrue(str.contains("Cluster{nodes=[serverA:1234, serverB:10051]}"));
    }

    @Test
    void testConstructor_nodeStringWithSpaces() {
        Cluster cluster = new Cluster(Collections.singletonList("  myhost.com:10060  "));
        List<Node> nodes = cluster.getNodes();
        assertEquals(1, nodes.size());
        assertEquals(new Node("myhost.com", 10060), nodes.get(0));
    }
}
