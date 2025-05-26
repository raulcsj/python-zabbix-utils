package io.zabbix4j.api.types;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Represents an item value to be sent to Zabbix, encapsulating host, key, value, and optional timestamp information.
 * This class is immutable.
 *
 * @author Your Name
 */
public final class ItemValue {

    private final String host;
    private final String key;
    private final String value;
    private final Long clock; // Optional
    private final Integer ns;   // Optional

    /**
     * Constructs an {@code ItemValue} object for an item without a specific timestamp.
     *
     * @param host  The hostname or IP address of the host the item belongs to. Must not be null or empty.
     * @param key   The key of the item. Must not be null or empty.
     * @param value The value of the item. Must not be null or empty.
     * @throws IllegalArgumentException if host, key, or value is null or empty.
     */
    public ItemValue(String host, String key, String value) {
        this(host, key, value, null, null);
    }

    /**
     * Constructs an {@code ItemValue} object for an item with a specific timestamp.
     *
     * @param host  The hostname or IP address of the host the item belongs to. Must not be null or empty.
     * @param key   The key of the item. Must not be null or empty.
     * @param value The value of the item. Must not be null or empty.
     * @param clock The timestamp of the value in seconds since the Unix epoch (optional).
     * @param ns    The nanoseconds part of the timestamp (optional).
     * @throws IllegalArgumentException if host, key, or value is null or empty.
     */
    public ItemValue(String host, String key, String value, Long clock, Integer ns) {
        if (host == null || host.trim().isEmpty()) {
            throw new IllegalArgumentException("Host cannot be null or empty.");
        }
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("Key cannot be null or empty.");
        }
        if (value == null || value.trim().isEmpty()) { // Allow empty string for value, as per Zabbix behavior
            throw new IllegalArgumentException("Value cannot be null.");
        }

        this.host = host.trim();
        this.key = key.trim();
        this.value = value; // Do not trim value, as whitespace might be significant
        this.clock = clock;
        this.ns = ns;
    }

    /**
     * Returns the hostname or IP address of the host.
     *
     * @return The host string.
     */
    public String getHost() {
        return host;
    }

    /**
     * Returns the key of the item.
     *
     * @return The item key.
     */
    public String getKey() {
        return key;
    }

    /**
     * Returns the value of the item.
     *
     * @return The item value.
     */
    public String getValue() {
        return value;
    }

    /**
     * Returns the timestamp of the value in seconds since the Unix epoch.
     *
     * @return The clock value, or {@code null} if not set.
     */
    public Long getClock() {
        return clock;
    }

    /**
     * Returns the nanoseconds part of the timestamp.
     *
     * @return The nanoseconds value, or {@code null} if not set.
     */
    public Integer getNs() {
        return ns;
    }

    /**
     * Converts this {@code ItemValue} object to a {@code Map<String, Object>}.
     * This map is suitable for JSON serialization. The {@code clock} and {@code ns}
     * fields are only included in the map if they are not {@code null}.
     *
     * @return A map representation of this item value.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("host", host);
        map.put("key", key);
        map.put("value", value);
        if (clock != null) {
            map.put("clock", clock);
        }
        if (ns != null) {
            map.put("ns", ns);
        }
        return map;
    }

    /**
     * Returns a JSON-like string representation of this {@code ItemValue},
     * derived from its map representation.
     *
     * @return A string representation of the item value.
     */
    @Override
    public String toString() {
        // Basic JSON-like representation for logging/debugging
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"host\":\"").append(host).append("\",");
        sb.append("\"key\":\"").append(key).append("\",");
        sb.append("\"value\":\"").append(value).append("\""); // Value might contain quotes, this is a simplified toString
        if (clock != null) {
            sb.append(",\"clock\":").append(clock);
        }
        if (ns != null) {
            sb.append(",\"ns\":").append(ns);
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * Compares this {@code ItemValue} with the specified object for equality.
     * The comparison is based on all fields: host, key, value, clock, and ns.
     *
     * @param o The object to compare with.
     * @return {@code true} if the objects are equal, {@code false} otherwise.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ItemValue itemValue = (ItemValue) o;
        return Objects.equals(host, itemValue.host) &&
               Objects.equals(key, itemValue.key) &&
               Objects.equals(value, itemValue.value) &&
               Objects.equals(clock, itemValue.clock) &&
               Objects.equals(ns, itemValue.ns);
    }

    /**
     * Returns the hash code for this {@code ItemValue}.
     * The hash code is based on all fields: host, key, value, clock, and ns.
     *
     * @return The hash code value for this object.
     */
    @Override
    public int hashCode() {
        return Objects.hash(host, key, value, clock, ns);
    }
}
