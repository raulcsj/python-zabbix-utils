package io.zabbix4j.api.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Represents a cluster of Zabbix nodes (e.g., Zabbix proxies or servers in an HA setup).
 * This class is designed to be immutable.
 *
 * @author ShortRoundDev
 */
public final class ClusterNode {

    private static final int DEFAULT_ZABBIX_PORT = 10051; // Default Zabbix agent/server/proxy port

    private final List<Node> nodes;

    /**
     * Constructs a ClusterNode from a list of node address strings.
     * Each string can be in the format "host:port" or just "host" (in which case the default Zabbix port 10051 is used).
     *
     * @param nodeAddresses A list of strings, where each string represents a node's address.
     * @throws IllegalArgumentException if nodeAddresses is null or if any address string is invalid.
     */
    public ClusterNode(List<String> nodeAddresses) {
        if (nodeAddresses == null) {
            throw new IllegalArgumentException("Node addresses list cannot be null.");
        }
        if (nodeAddresses.isEmpty()) {
            // Or, depending on requirements, initialize with an empty list:
            // this.nodes = Collections.emptyList();
            // For now, let's consider an empty list of addresses as an invalid argument
            // if a cluster implies at least one node.
            // If an empty cluster is permissible, change to Collections.emptyList().
            throw new IllegalArgumentException("Node addresses list cannot be empty if a cluster is to be defined.");
        }

        List<Node> parsedNodes = new ArrayList<>();
        for (String addressStr : nodeAddresses) {
            if (addressStr == null || addressStr.trim().isEmpty()) {
                throw new IllegalArgumentException("Node address string cannot be null or empty.");
            }
            String trimmedAddressStr = addressStr.trim();
            String[] parts = trimmedAddressStr.split(":", 2);
            String host = parts[0];
            int port = DEFAULT_ZABBIX_PORT;
            if (parts.length > 1) {
                try {
                    port = Integer.parseInt(parts[1]);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid port in address string: '" + trimmedAddressStr + "'", e);
                }
            }
            parsedNodes.add(new Node(host, port));
        }
        this.nodes = Collections.unmodifiableList(parsedNodes);
    }

    /**
     * Constructs a ClusterNode directly from a list of {@link Node} objects.
     *
     * @param nodes The list of {@link Node} objects. The provided list will be defensively copied.
     * @throws IllegalArgumentException if nodes is null or empty.
     */
    public ClusterNode(List<Node> nodes, boolean internal) {
        if (nodes == null || nodes.isEmpty()) {
             throw new IllegalArgumentException("Nodes list cannot be null or empty.");
        }
        this.nodes = Collections.unmodifiableList(new ArrayList<>(nodes)); // Defensive copy
    }


    /**
     * @return An unmodifiable list of {@link Node} objects representing the nodes in this cluster.
     */
    public List<Node> getNodes() {
        return nodes;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ClusterNode that = (ClusterNode) o;
        // Order of nodes might matter for equality depending on requirements.
        // If order doesn't matter, a Set<Node> might be more appropriate internally,
        // or sort both lists before comparison. For now, assuming order matters.
        return Objects.equals(nodes, that.nodes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nodes);
    }

    /**
     * Returns a string representation of the cluster, listing the nodes.
     * Example: "ClusterNode{nodes=[127.0.0.1:10051, example.com:10050]}"
     *
     * @return A string representation of the cluster.
     */
    @Override
    public String toString() {
        return "ClusterNode{" +
               "nodes=" + nodes.stream().map(Node::toString).collect(Collectors.joining(", ", "[", "]")) +
               '}';
    }
}
