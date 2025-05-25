package io.zabbix4j.api.types;

import java.util.Objects;

/**
 * Represents a network node with an address and port.
 * <p>
 * The address "0.0.0.0/0" is automatically converted to "127.0.0.1".
 * Port numbers are validated to be within the standard range (1-65535).
 * This class is immutable.
 * </p>
 *
 * @author CSJ
 */
public final class Node {

    private final String address;
    private final int port;

    /**
     * Constructs a {@code Node} instance.
     *
     * @param address The network address of the node. If "0.0.0.0/0", it's converted to "127.0.0.1".
     *                Cannot be null or empty.
     * @param port    The port number of the node. Must be between 1 and 65535.
     * @throws IllegalArgumentException if the address is null/empty, or if the port is out of range.
     */
    public Node(String address, int port) {
        if (address == null || address.trim().isEmpty()) {
            throw new IllegalArgumentException("Address cannot be null or empty.");
        }
        if ("0.0.0.0/0".equals(address)) {
            this.address = "127.0.0.1";
        } else {
            this.address = address;
        }

        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port number must be between 1 and 65535, inclusive. Received: " + port);
        }
        this.port = port;
    }

    /**
     * Gets the address of the node.
     *
     * @return The node address.
     */
    public String getAddress() {
        return address;
    }

    /**
     * Gets the port number of the node.
     *
     * @return The node port.
     */
    public int getPort() {
        return port;
    }

    /**
     * Returns a string representation of the node in "address:port" format.
     *
     * @return A string representation of the node.
     */
    @Override
    public String toString() {
        return address + ":" + port;
    }

    /**
     * Compares this {@code Node} with the specified object for equality.
     *
     * @param o The object to be compared for equality with this {@code Node}.
     * @return {@code true} if the specified object is equal to this {@code Node}.
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
     * Returns the hash code value for this {@code Node}.
     *
     * @return The hash code value for this {@code Node}.
     */
    @Override
    public int hashCode() {
        return Objects.hash(address, port);
    }
}
