package io.zabbix4j.api.types;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Represents a cluster of Zabbix nodes.
 * <p>
 * A cluster is defined by a list of node strings, each specifying an address and optionally a port.
 * If a port is not specified for a node, a default port (10051) is used.
 * This class is immutable.
 * </p>
 *
 * @author CSJ
 */
public final class Cluster {

    private static final int DEFAULT_PORT = 10051;
    private final List<Node> nodes;

    /**
     * Constructs a {@code Cluster} instance from a list of node strings.
     *
     * @param nodeStrings A list of strings, where each string represents a node.
     *                    Format for each string can be "address:port" or "address" (default port 10051 will be used).
     *                    The list cannot be null or empty.
     * @throws IllegalArgumentException if {@code nodeStrings} is null or empty,
     *                                  or if any node string is malformed or results in an invalid Node.
     */
    public Cluster(List<String> nodeStrings) {
        if (nodeStrings == null || nodeStrings.isEmpty()) {
            throw new IllegalArgumentException("Node strings list cannot be null or empty.");
        }

        List<Node> parsedNodes = new ArrayList<>();
        for (String nodeString : nodeStrings) {
            if (nodeString == null || nodeString.trim().isEmpty()) {
                throw new IllegalArgumentException("Node string cannot be null or empty in the list.");
            }

            String address;
            int port;

            String[] parts = nodeString.split(":", 2);
            address = parts[0].trim();

            if (parts.length == 2) {
                try {
                    port = Integer.parseInt(parts[1].trim());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid port number format in node string: '" + nodeString + "'.", e);
                }
            } else {
                port = DEFAULT_PORT;
            }

            try {
                parsedNodes.add(new Node(address, port));
            } catch (IllegalArgumentException e) {
                // Catch exceptions from Node constructor (e.g., invalid port range, empty address after trim)
                throw new IllegalArgumentException("Failed to create Node from string: '" + nodeString + "'. " + e.getMessage(), e);
            }
        }
        this.nodes = Collections.unmodifiableList(parsedNodes);
    }

    /**
     * Gets the list of nodes in this cluster.
     *
     * @return An unmodifiable list of {@link Node} objects.
     */
    public List<Node> getNodes() {
        return nodes;
    }

    /**
     * Returns a string representation of the cluster, which is a string representation of its list of nodes.
     *
     * @return A string representation of the cluster.
     */
    @Override
    public String toString() {
        return "Cluster{nodes=" + nodes + "}";
    }

    /**
     * Compares this {@code Cluster} with the specified object for equality.
     * Two clusters are equal if they have the same nodes in the same order.
     *
     * @param o The object to be compared for equality with this {@code Cluster}.
     * @return {@code true} if the specified object is equal to this {@code Cluster}.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Cluster cluster = (Cluster) o;
        return Objects.equals(nodes, cluster.nodes);
    }

    /**
     * Returns the hash code value for this {@code Cluster}.
     *
     * @return The hash code value for this {@code Cluster}.
     */
    @Override
    public int hashCode() {
        return Objects.hash(nodes);
    }
}
