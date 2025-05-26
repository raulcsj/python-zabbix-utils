package io.zabbix4j.api.types;

import java.util.Objects;

/**
 * Represents a network node, defined by an address and a port.
 * This class is immutable.
 *
 * @author Your Name
 */
public final class Node {

    private final String address;
    private final int port;

    /**
     * Constructs a {@code Node} object.
     * If the provided address is "0.0.0.0/0", it is internally converted to "127.0.0.1".
     *
     * @param address The network address of the node (e.g., "192.168.1.100", "zabbix.example.com").
     *                Cannot be null or empty.
     * @param port    The network port of the node. Must be between 1 and 65535, inclusive.
     * @throws IllegalArgumentException if the address is null or empty, or if the port is outside the valid range.
     */
    public Node(String address, int port) {
        if (address == null || address.trim().isEmpty()) {
            throw new IllegalArgumentException("Address cannot be null or empty.");
        }
        if ("0.0.0.0/0".equals(address.trim())) {
            this.address = "127.0.0.1";
        } else {
            this.address = address.trim();
        }

        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("Port number must be between 1 and 65535. Got: " + port);
        }
        this.port = port;
    }

    /**
     * Returns the network address of the node.
     *
     * @return The address string.
     */
    public String getAddress() {
        return address;
    }

    /**
     * Returns the network port of the node.
     *
     * @return The port number.
     */
    public int getPort() {
        return port;
    }

    /**
     * Returns a string representation of the node in "address:port" format.
     * For example, "127.0.0.1:10051".
     *
     * @return The string representation of the node.
     */
    @Override
    public String toString() {
        return address + ":" + port;
    }

    /**
     * Compares this {@code Node} with the specified object for equality.
     * The comparison is based on the address and port.
     *
     * @param o The object to compare with.
     * @return {@code true} if the objects are equal (same address and port), {@code false} otherwise.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Node node = (Node) o;
        return port == node.port &&
               Objects.equals(address, node.address);
    }

    /**
     * Returns the hash code for this {@code Node}.
     * The hash code is based on the address and port.
     *
     * @return The hash code value for this object.
     */
    @Override
    public int hashCode() {
        return Objects.hash(address, port);
    }
}
