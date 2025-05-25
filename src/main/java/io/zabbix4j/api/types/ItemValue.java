package io.zabbix4j.api.types;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Represents an item value to be sent to Zabbix, often used in sender operations.
 * <p>
 * This class stores information about the host, item key, value, and optional timestamp (clock and nanoseconds).
 * It is designed to be immutable and provides a builder pattern for construction.
 * </p>
 *
 * @author CSJ
 */
public final class ItemValue {

    private final String host;
    private final String key;
    private final String value;
    private final Long clock;
    private final Integer ns;

    /**
     * Private constructor to enforce object creation via the Builder.
     *
     * @param builder The builder instance containing the item value data.
     */
    private ItemValue(Builder builder) {
        this.host = builder.host;
        this.key = builder.key;
        this.value = builder.value;
        this.clock = builder.clock;
        this.ns = builder.ns;
    }

    /**
     * Gets the hostname for this item value.
     *
     * @return The hostname.
     */
    public String getHost() {
        return host;
    }

    /**
     * Gets the item key for this item value.
     *
     * @return The item key.
     */
    public String getKey() {
        return key;
    }

    /**
     * Gets the value of the item.
     *
     * @return The item value.
     */
    public String getValue() {
        return value;
    }

    /**
     * Gets the timestamp (Unix time) for this item value.
     *
     * @return The timestamp as seconds since epoch, or {@code null} if not set.
     */
    public Long getClock() {
        return clock;
    }

    /**
     * Gets the nanoseconds component of the timestamp for this item value.
     *
     * @return The nanoseconds, or {@code null} if not set.
     */
    public Integer getNs() {
        return ns;
    }

    /**
     * Returns a map representation of this item value, suitable for JSON serialization.
     * Fields with {@code null} values are excluded from the map.
     *
     * @return A map containing the non-null attributes of this item value.
     */
    public Map<String, Object> toJsonMap() {
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
     * Returns a string representation of this item value, typically based on its JSON map form.
     *
     * @return A string representation of the object.
     */
    @Override
    public String toString() {
        return "ItemValue" + toJsonMap().toString();
    }

    /**
     * Compares this {@code ItemValue} with the specified object for equality.
     *
     * @param o The object to be compared for equality with this {@code ItemValue}.
     * @return {@code true} if the specified object is equal to this {@code ItemValue}.
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
     * Returns the hash code value for this {@code ItemValue}.
     *
     * @return The hash code value for this {@code ItemValue}.
     */
    @Override
    public int hashCode() {
        return Objects.hash(host, key, value, clock, ns);
    }

    /**
     * Creates a new builder for {@code ItemValue} instances.
     *
     * @return A new {@code Builder} instance.
     */
    public static Builder newBuilder() {
        return new Builder();
    }

    /**
     * Builder class for {@link ItemValue}.
     * Provides a fluent API for constructing {@code ItemValue} objects.
     */
    public static class Builder {
        private String host;
        private String key;
        private String value;
        private Long clock;
        private Integer ns;

        private Builder() {
            // Private constructor to prevent direct instantiation
        }

        /**
         * Sets the hostname. This field is mandatory.
         *
         * @param host The hostname.
         * @return This builder instance for chaining.
         */
        public Builder host(String host) {
            this.host = host;
            return this;
        }

        /**
         * Sets the item key. This field is mandatory.
         *
         * @param key The item key.
         * @return This builder instance for chaining.
         */
        public Builder key(String key) {
            this.key = key;
            return this;
        }

        /**
         * Sets the item value. This field is mandatory.
         *
         * @param value The item value.
         * @return This builder instance for chaining.
         */
        public Builder value(String value) {
            this.value = value;
            return this;
        }

        /**
         * Sets the timestamp (Unix time in seconds). This field is optional.
         *
         * @param clock The timestamp.
         * @return This builder instance for chaining.
         */
        public Builder clock(Long clock) {
            this.clock = clock;
            return this;
        }

        /**
         * Sets the nanoseconds component of the timestamp. This field is optional.
         *
         * @param ns The nanoseconds.
         * @return This builder instance for chaining.
         */
        public Builder ns(Integer ns) {
            this.ns = ns;
            return this;
        }

        /**
         * Builds the {@link ItemValue} instance.
         *
         * @return A new immutable {@code ItemValue} instance.
         * @throws IllegalArgumentException if mandatory fields (host, key, value) are null or empty.
         */
        public ItemValue build() {
            if (host == null || host.trim().isEmpty()) {
                throw new IllegalArgumentException("Host cannot be null or empty.");
            }
            if (key == null || key.trim().isEmpty()) {
                throw new IllegalArgumentException("Key cannot be null or empty.");
            }
            if (value == null || value.trim().isEmpty()) { // Assuming value also cannot be empty, adjust if needed
                throw new IllegalArgumentException("Value cannot be null or empty.");
            }
            // Additional validation for clock and ns can be added here if needed
            // e.g., ensuring ns is only set if clock is set, or ranges.
            // For now, just type checking via parameters is sufficient.

            return new ItemValue(this);
        }
    }
}
