package io.zabbix4j.api.service.async;

import io.zabbix4j.api.exception.ZabbixApiException;
import java.util.concurrent.CompletableFuture;

/**
 * Asynchronous service interface for Zabbix User related API operations.
 * All methods return a {@link CompletableFuture} to handle asynchronous responses.
 *
 * @author CSJ
 */
public interface AsyncUserService {

    /**
     * Asynchronously logs into the Zabbix API using user credentials.
     * The returned future will complete with the authentication token (session ID) on success.
     *
     * @param user     The Zabbix username.
     * @param password The Zabbix password.
     * @return A {@link CompletableFuture} which will complete with the auth token string,
     *         or complete exceptionally if an error occurs during the API call or if login fails.
     */
    CompletableFuture<String> login(String user, String password);

    /**
     * Asynchronously logs out from the Zabbix API, invalidating the current session/token.
     *
     * @return A {@link CompletableFuture} which will complete normally on successful logout,
     *         or complete exceptionally if an error occurs.
     */
    CompletableFuture<Void> logout();

    /**
     * Asynchronously checks the validity of the current authentication (session ID or token).
     *
     * @param currentAuthToken The authentication token (session ID or API token) to check.
     *                         If this is an API token, {@code isTokenValue} should be true.
     *                         If using the client's internal auth state, this might be obtained from the client.
     * @param isTokenValue     {@code true} if {@code currentAuthToken} is an API token (not a session ID),
     *                         {@code false} otherwise. This helps in formatting the check request if needed,
     *                         though often Zabbix API `user.checkAuthentication` can infer from the `auth` field.
     * @return A {@link CompletableFuture} which will complete with {@code true} if authentication is valid,
     *         {@code false} otherwise, or complete exceptionally if an API error occurs.
     */
    CompletableFuture<Boolean> checkAuthentication(String currentAuthToken, boolean isTokenValue);

    // Other user-related methods like get, create, update, delete can be added here,
    // each returning a CompletableFuture. For example:
    // CompletableFuture<JSONArray> get(Map<String, Object> params);
    // CompletableFuture<JSONObject> create(Map<String, Object> params);
}
