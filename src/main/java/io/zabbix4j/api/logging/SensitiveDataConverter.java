package io.zabbix4j.api.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import io.zabbix4j.api.utils.ZabbixApiUtils;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Map;

/**
 * A Logback PatternLayoutConverter that sanitizes log messages by attempting to hide
 * sensitive information within JSON structures.
 * <p>
 * This converter checks if the formatted log message is a JSON object. If it is,
 * the message is parsed, and {@link ZabbixApiUtils#hidePrivate(Map, Map)} is used
 * with {@link ZabbixApiUtils#DEFAULT_PRIVATE_FIELDS} to mask known sensitive fields.
 * The message is then converted back to a JSON string.
 * <p>
 * If the message is not a valid JSON object or if any error occurs during parsing
 * or sanitization, the original formatted message is returned.
 * <p>
 * To use this converter in a {@code logback.xml} configuration, register it with a
 * conversion word:
 * <pre>{@code
 * <configuration>
 *     <conversionRule conversionWord="mask"
 *                     converterClass="io.zabbix4j.api.logging.SensitiveDataConverter" />
 *
 *     <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
 *         <encoder>
 *             <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %mask%n</pattern>
 *         </encoder>
 *     </appender>
 *
 *     <root level="debug">
 *         <appender-ref ref="STDOUT" />
 *     </root>
 * </configuration>
 * }</pre>
 * Then use {@code %mask} (or your chosen conversion word) in your pattern layout instead of {@code %message} or {@code %msg}.
 *
 * @author CSJ
 */
public class SensitiveDataConverter extends MessageConverter {

    /**
     * Converts the logging event's formatted message. If the message is a JSON string,
     * it attempts to parse and sanitize it using {@link ZabbixApiUtils#hidePrivate}.
     * Otherwise, it returns the original formatted message.
     *
     * @param event The logging event.
     * @return The sanitized message if successful and applicable, or the original message.
     */
    @Override
    public String convert(ILoggingEvent event) {
        String formattedMessage = event.getFormattedMessage();
        if (formattedMessage == null || formattedMessage.trim().isEmpty()) {
            return formattedMessage;
        }

        String trimmedMessage = formattedMessage.trim();
        if (trimmedMessage.startsWith("{") && trimmedMessage.endsWith("}")) {
            try {
                // Parse the JSON string into a Map
                JSONObject jsonObject = new JSONObject(trimmedMessage);
                Map<String, Object> mapRepresentation = jsonObject.toMap();

                // Sanitize the map
                Map<String, Object> sanitizedMap = ZabbixApiUtils.hidePrivate(mapRepresentation, ZabbixApiUtils.DEFAULT_PRIVATE_FIELDS);

                // Convert the sanitized map back to a JSON string
                return new JSONObject(sanitizedMap).toString();
            } catch (JSONException e) {
                // Not a valid JSON or error during processing, return original message
                // Optionally, log this parsing failure at a DEBUG level if the logger for this class is configured.
                // For example: add a static final Logger for SensitiveDataConverter.
            } catch (Exception e) {
                // Catch any other unexpected errors during sanitization
                // and return original message to prevent logging disruption.
            }
        }
        return formattedMessage;
    }
}
