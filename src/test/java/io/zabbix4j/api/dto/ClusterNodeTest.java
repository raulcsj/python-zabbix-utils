package io.zabbix4j.api.dto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ClusterNode}.
 * @author ShortRoundDev
 */
class ClusterNodeTest {

    @Test
    void testConstructor_listOfString_valid() {
        List<String> addresses = Arrays.asList("server1:10051", "server2", "192.168.1.3:10050");
        ClusterNode cluster = new ClusterNode(addresses);

        List<Node> nodes = cluster.getNodes();
        assertEquals(3, nodes.size());
        assertEquals(new Node("server1", 10051), nodes.get(0));
        assertEquals(new Node("server2", 10051), nodes.get(1)); // Default port
        assertEquals(new Node("192.168.1.3", 10050), nodes.get(2));
    }

    @Test
    void testConstructor_listOfString_singleNode() {
        List<String> addresses = Collections.singletonList("localhost:10051");
        ClusterNode cluster = new ClusterNode(addresses);
        assertEquals(1, cluster.getNodes().size());
        assertEquals(new Node("localhost", 10051), cluster.getNodes().get(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"server1:badport", "server2:1000000", "server3:-1"})
    void testConstructor_listOfString_invalidPortFormat(String invalidAddress) {
        List<String> addresses = Collections.singletonList(invalidAddress);
        assertThrows(IllegalArgumentException.class, () -> new ClusterNode(addresses));
    }

    @Test
    void testConstructor_listOfString_emptyOrNullStringInList() {
        List<String> addressesWithNull = Arrays.asList("server1", null, "server2");
        assertThrows(IllegalArgumentException.class, () -> new ClusterNode(addressesWithNull));

        List<String> addressesWithEmpty = Arrays.asList("server1", "", "server2");
        assertThrows(IllegalArgumentException.class, () -> new ClusterNode(addressesWithEmpty));
    }

    @Test
    void testConstructor_listOfString_nullOrEmptyList() {
        assertThrows(IllegalArgumentException.class, () -> new ClusterNode((List<String>) null));
        assertThrows(IllegalArgumentException.class, () -> new ClusterNode(Collections.emptyList()));
    }

    @Test
    void testConstructor_listOfNode_valid() {
        List<Node> nodes = Arrays.asList(new Node("n1", 10051), new Node("n2", 10050));
        ClusterNode cluster = new ClusterNode(nodes, true); // Using internal constructor
        assertEquals(2, cluster.getNodes().size());
        assertEquals(nodes, cluster.getNodes()); // Should be equal as it's a copy
    }

    @Test
    void testConstructor_listOfNode_defensiveCopy() {
        List<Node> originalNodes = new java.util.ArrayList<>();
        originalNodes.add(new Node("n1", 10051));
        ClusterNode cluster = new ClusterNode(originalNodes, true);
        originalNodes.add(new Node("n2", 10050)); // Modify original list

        assertEquals(1, cluster.getNodes().size()); // ClusterNode should have its own copy
    }


    @Test
    void testConstructor_listOfNode_nullOrEmptyList() {
        assertThrows(IllegalArgumentException.class, () -> new ClusterNode(null, true));
        assertThrows(IllegalArgumentException.class, () -> new ClusterNode(Collections.emptyList(), true));
    }


    @Test
    void testEqualsAndHashCode() {
        ClusterNode c1 = new ClusterNode(Arrays.asList("s1:10051", "s2:10050"));
        ClusterNode c2 = new ClusterNode(Arrays.asList("s1:10051", "s2:10050"));
        ClusterNode c3 = new ClusterNode(Arrays.asList("s1:10051", "s3:10050")); // Different node
        ClusterNode c4 = new ClusterNode(Collections.singletonList("s1:10051")); // Different number of nodes

        assertEquals(c1, c2);
        assertEquals(c1.hashCode(), c2.hashCode());

        assertNotEquals(c1, c3);
        assertNotEquals(c1.hashCode(), c3.hashCode());
        assertNotEquals(c1, c4);
        assertNotEquals(c1, null);
        assertNotEquals(c1, new Object());

        // Test with Node list constructor as well
        List<Node> nodes1 = Arrays.asList(new Node("n1", 1000), new Node("n2", 2000));
        List<Node> nodes2 = Arrays.asList(new Node("n1", 1000), new Node("n2", 2000));
        ClusterNode cn1 = new ClusterNode(nodes1, true);
        ClusterNode cn2 = new ClusterNode(nodes2, true);
        assertEquals(cn1, cn2);
        assertEquals(cn1.hashCode(), cn2.hashCode());
    }

    @Test
    void testToString() {
        ClusterNode cluster = new ClusterNode(Arrays.asList("server1:10051", "server2"));
        String str = cluster.toString();

        assertTrue(str.contains("ClusterNode"));
        assertTrue(str.contains("nodes=[server1:10051, server2:10051]")); // Default port for server2
    }
}
