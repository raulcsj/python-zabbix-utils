package io.zabbix4j.api.types;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Represents a cluster of Zabbix nodes, typically for a High Availability (HA) setup.
 * A cluster consists of one or more {@link Node} objects.
 * This class is immutable.
 *
 * @author Your Name
 */
public final class Cluster {

    /**
     * The default port for Zabbix agent/server communication (10051).
     */
    public static final int DEFAULT_ZABBIX_PORT = 10051;

    private final List<Node> nodes;

    /**
     * Constructs a {@code Cluster} object from a list of node strings.
     * Each string in the list represents a node in the format "address" or "address:port".
     * If a port is not specified, {@link #DEFAULT_ZABBIX_PORT} is used.
     *
     * @param nodeStrings A list of strings, where each string defines a node.
     *                    For example: {@code ["zabbix.server.com:10051", "zabbix.proxy.com", "192.168.1.100:10050"]}.
     *                    The list must not be null or empty, and must not contain null or blank strings.
     * @throws IllegalArgumentException if {@code nodeStrings} is null, empty, contains null/blank strings,
     *                                  or if any node string is invalid (e.g., invalid port format).
     */
    public Cluster(List<String> nodeStrings) {
        if (nodeStrings == null || nodeStrings.isEmpty()) {
            throw new IllegalArgumentException("Node strings list cannot be null or empty.");
        }

        List<Node> parsedNodes = new ArrayList<>();
        for (String nodeItem : nodeStrings) {
            if (nodeItem == null || nodeItem.trim().isEmpty()) {
                throw new IllegalArgumentException("Node string in the list cannot be null or empty.");
            }
            nodeItem = nodeItem.trim();
            String address;
            int port;

            if (nodeItem.contains(":")) {
                String[] parts = nodeItem.split(":", 2); // Split only on the first colon
                address = parts[0];
                if (parts.length < 2 || parts[1].trim().isEmpty()) {
                     throw new IllegalArgumentException("Invalid node format: port missing after colon in '" + nodeItem + "'.");
                }
                try {
                    port = Integer.parseInt(parts[1].trim());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid port number in node string: '" + nodeItem + "'. Port must be an integer.", e);
                }
            } else {
                address = nodeItem;
                port = DEFAULT_ZABBIX_PORT;
            }
            // Node constructor already validates address and port range
            parsedNodes.add(new Node(address, port));
        }
        this.nodes = Collections.unmodifiableList(parsedNodes);
    }

    /**
     * Returns the unmodifiable list of {@link Node} objects in this cluster.
     *
     * @return An unmodifiable list of nodes.
     */
    public List<Node> getNodes() {
        return nodes;
    }

    /**
     * Returns a string representation of this cluster, which is the string representation of its list of nodes.
     *
     * @return A string representation of the cluster (e.g., "[127.0.0.1:10051, 192.168.1.10:10051]").
     */
    @Override
    public String toString() {
        return nodes.toString();
    }

    /**
     * Compares this {@code Cluster} with the specified object for equality.
     * The comparison is based on the list of {@link Node} objects. Order matters in the list.
     *
     * @param o The object to compare with.
     * @return {@code true} if the objects are equal (same nodes in the same order), {@code false} otherwise.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Cluster cluster = (Cluster) o;
        return Objects.equals(nodes, cluster.nodes);
    }

    /**
     * Returns the hash code for this {@code Cluster}.
     * The hash code is based on the list of {@link Node} objects.
     *
     * @return The hash code value for this object.
     */
    @Override
    public int hashCode() {
        return Objects.hash(nodes);
    }
}
