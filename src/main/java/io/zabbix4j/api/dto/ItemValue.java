package io.zabbix4j.api.dto;

import java.util.Objects;

/**
 * Represents a single item value to be sent to the Zabbix server/proxy, typically via Zabbix sender.
 * This class is designed to be immutable.
 *
 * @author ShortRoundDev
 */
public final class ItemValue {

    private final String host;
    private final String key;
    private final String value;
    private final Long clock; // Optional, seconds since Unix epoch
    private final Integer ns; // Optional, nanoseconds for the clock value

    private ItemValue(Builder builder) {
        if (builder.host == null || builder.host.trim().isEmpty()) {
            throw new IllegalArgumentException("Host cannot be null or empty.");
        }
        if (builder.key == null || builder.key.trim().isEmpty()) {
            throw new IllegalArgumentException("Key cannot be null or empty.");
        }
        if (builder.value == null) { // Value can be an empty string, but not null
            throw new IllegalArgumentException("Value cannot be null.");
        }

        this.host = builder.host;
        this.key = builder.key;
        this.value = builder.value;
        this.clock = builder.clock;
        this.ns = builder.ns;
    }

    /**
     * @return The hostname of the monitored host.
     */
    public String getHost() {
        return host;
    }

    /**
     * @return The item key.
     */
    public String getKey() {
        return key;
    }

    /**
     * @return The item value.
     */
    public String getValue() {
        return value;
    }

    /**
     * @return The timestamp of the value in seconds since Unix epoch (optional).
     */
    public Long getClock() {
        return clock;
    }

    /**
     * @return The nanoseconds part of the timestamp (optional).
     */
    public Integer getNs() {
        return ns;
    }

    /**
     * Creates a new Builder instance for constructing {@link ItemValue} objects.
     * @return A new Builder instance.
     */
    public static Builder builder() {
        return new Builder();
    }

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

    @Override
    public int hashCode() {
        return Objects.hash(host, key, value, clock, ns);
    }

    @Override
    public String toString() {
        return "ItemValue{" +
               "host='" + host + '\'' +
               ", key='" + key + '\'' +
               ", value='" + value + '\'' +
               (clock != null ? ", clock=" + clock : "") +
               (ns != null ? ", ns=" + ns : "") +
               '}';
    }

    /**
     * Builder for {@link ItemValue}.
     */
    public static class Builder {
        private String host;
        private String key;
        private String value;
        private Long clock;
        private Integer ns;

        private Builder() {}

        /**
         * Sets the hostname for the item. (Required)
         * @param host The hostname.
         * @return This builder.
         */
        public Builder host(String host) {
            this.host = host;
            return this;
        }

        /**
         * Sets the item key. (Required)
         * @param key The item key.
         * @return This builder.
         */
        public Builder key(String key) {
            this.key = key;
            return this;
        }

        /**
         * Sets the item value. (Required)
         * @param value The item value.
         * @return This builder.
         */
        public Builder value(String value) {
            this.value = value;
            return this;
        }

        /**
         * Sets the timestamp for the item value. (Optional)
         * @param clock Seconds since Unix epoch.
         * @return This builder.
         */
        public Builder clock(Long clock) {
            this.clock = clock;
            return this;
        }

        /**
         * Sets the nanoseconds for the item's timestamp. (Optional)
         * Should only be used if {@link #clock(Long)} is also set.
         * @param ns Nanoseconds.
         * @return This builder.
         */
        public Builder ns(Integer ns) {
            this.ns = ns;
            return this;
        }

        /**
         * Builds the {@link ItemValue} instance.
         * @return A new {@link ItemValue} instance.
         * @throws IllegalArgumentException if required fields (host, key, value) are not set or are invalid.
         */
        public ItemValue build() {
            return new ItemValue(this);
        }
    }
}
