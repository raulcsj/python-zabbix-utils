package io.zabbix4j.api.service;

import io.zabbix4j.api.exception.ZabbixApiException;

/**
 * Service interface for Zabbix User related API operations.
 *
 * @author CSJ
 */
public interface UserService {

    /**
     * Logs into the Zabbix API using user credentials.
     *
     * @param user     The Zabbix username.
     * @param password The Zabbix password.
     * @return A {@link UserLoginResponse} containing the authentication token and user ID.
     * @throws ZabbixApiException if an error occurs during the API call or if login fails.
     */
    UserLoginResponse login(String user, String password) throws ZabbixApiException;

    // Other user-related methods like logout, checkAuthentication, etc., can be added here.
}
