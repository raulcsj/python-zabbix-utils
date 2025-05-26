package io.zabbix4j.api.service;

/**
 * Represents the response from a user login attempt, typically containing the auth token (session ID).
 * This is a simplified representation.
 *
 * @param authToken The authentication token (session ID) received from Zabbix.
 * @param userId    The ID of the user who logged in.
 * @author CSJ
 */
public record UserLoginResponse(String authToken, String userId) {
    // Records are immutable by default and provide getters, equals, hashCode, toString.
}
