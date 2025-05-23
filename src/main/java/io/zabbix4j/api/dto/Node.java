package io.zabbix4j.api.dto;

import java.util.Objects;

/**
 * Represents a network node with an address and port.
 *
 * @author ShortRoundDev
 */
public final class Node {

    private final String address;
    private final int port;

    /**
     * Constructs a Node.
     * If the provided address is "0.0.0.0/0", it will be converted to "127.0.0.1".
     *
     * @param address The network address (e.g., "192.168.1.100", "zabbix.example.com").
     * @param port    The network port (e.g., 10050, 10051).
     * @throws IllegalArgumentException if the address is null or empty, or if the port is outside the valid range (0-65535).
     */
    public Node(String address, int port) {
        if (address == null || address.trim().isEmpty()) {
            throw new IllegalArgumentException("Address cannot be null or empty.");
        }
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 0 and 65535, inclusive. Got: " + port);
        }

        if ("0.0.0.0/0".equals(address.trim())) {
            this.address = "127.0.0.1";
        } else {
            this.address = address.trim();
        }
        this.port = port;
    }

    /**
     * @return The network address of the node.
     */
    public String getAddress() {
        return address;
    }

    /**
     * @return The network port of the node.
     */
    public int getPort() {
        return port;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Node node = (Node) o;
        return port == node.port &&
               Objects.equals(address, node.address);
    }

    @Override
    public int hashCode() {
        return Objects.hash(address, port);
    }

    /**
     * Returns a string representation of the node in "address:port" format.
     *
     * @return The string "address:port".
     */
    @Override
    public String toString() {
        return address + ":" + port;
    }
}
