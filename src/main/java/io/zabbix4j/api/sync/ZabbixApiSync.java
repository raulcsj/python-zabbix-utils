package io.zabbix4j.api.sync;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.zabbix4j.api.ZabbixApiConstants;
import io.zabbix4j.api.dto.APIVersion;
import io.zabbix4j.api.exception.ApiNotSupportedException;
import io.zabbix4j.api.exception.ApiRequestException;
import io.zabbix4j.api.exception.CommunicationException;
import io.zabbix4j.api.exception.ProcessingException;
import io.zabbix4j.api.utils.ZabbixUtils;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Synchronous client for interacting with the Zabbix JSON-RPC API.
 * This class provides methods for making API calls, handling authentication,
 * and managing API version compatibility.
 * <p>
 * Use the {@link Builder} to construct instances of this class.
 * </p>
 * Example:
 * <pre>{@code
 * ZabbixApiSync zabbixApi = new ZabbixApiSync.Builder()
 * .url("http://zabbix.example.com")
 * .user("Admin", "zabbix")
 * .build();
 *
 * JsonNode hostGroups = zabbixApi.call("hostgroup.get", Map.of("output", "extend"));
 * System.out.println(hostGroups.toPrettyString());
 *
 * zabbixApi.logout();
 * zabbixApi.close();
 * }</pre>
 *
 * @author ShortRoundDev
 */
public final class ZabbixApiSync implements Closeable {

    private static final Logger logger = LoggerFactory.getLogger(ZabbixApiSync.class);
    private static final String JSON_RPC_VERSION = "2.0";
    private static final String USER_AGENT_PREFIX = "zabbix4j-sync/";


    private final CloseableHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String serverUrl;
    private final boolean managedHttpClient; // True if httpClient was created by the builder

    private volatile String sessionAuthToken;
    private volatile boolean isTokenAuth; // true if authentication is via token, false for user/pass session
    private final String basicAuthCredentials; // Base64 encoded "user:pass"

    private final AtomicReference<APIVersion> apiVersion = new AtomicReference<>(null);
    private final String userAgent;


    private ZabbixApiSync(Builder builder) {
        this.serverUrl = ZabbixUtils.checkUrl(builder.url);
        this.objectMapper = new ObjectMapper(); // Configure as needed
        this.basicAuthCredentials = builder.basicAuthUser != null && builder.basicAuthPassword != null ?
                Base64.getEncoder().encodeToString((builder.basicAuthUser + ":" + builder.basicAuthPassword).getBytes(StandardCharsets.UTF_8))
                : null;

        if (builder.httpClient != null) {
            this.httpClient = builder.httpClient;
            this.managedHttpClient = false;
        } else {
            RequestConfig.Builder configBuilder = RequestConfig.custom();
            if (builder.timeoutSeconds > 0) {
                Timeout t = Timeout.ofSeconds(builder.timeoutSeconds);
                configBuilder.setConnectTimeout(t)
                        .setConnectionRequestTimeout(t)
                        .setResponseTimeout(t); // HC5 uses setResponseTimeout for socket timeout
            }

            PoolingHttpClientConnectionManagerBuilder cmBuilder = PoolingHttpClientConnectionManagerBuilder.create();
            if (builder.sslContext != null) {
                cmBuilder.setSslContext(builder.sslContext);
            }
            // Add other HttpClient configurations here (e.g., proxy, retry handler)

            this.httpClient = HttpClients.custom()
                    .setConnectionManager(cmBuilder.build())
                    .setDefaultRequestConfig(configBuilder.build())
                    .build();
            this.managedHttpClient = true;
        }

        this.userAgent = USER_AGENT_PREFIX + ZabbixApiConstants.LIBRARY_VERSION;

        // Initial API version check (can be skipped by builder)
        try {
            fetchAndCheckApiVersion(builder.skipVersionCheck, true);
        } catch (ZabbixApiException e) {
            // If this fails in constructor and not handled, builder.build() will throw.
            // Close client if managed and init fails critically
            try {
                if (managedHttpClient) close();
            } catch (IOException ex) {
                logger.error("Failed to close HTTP client during constructor failure cleanup", ex);
            }
            throw e; // Re-throw original exception
        }


        // Handle initial authentication from builder
        if (builder.authToken != null) {
            loginWithToken(builder.authToken);
        } else if (builder.user != null && builder.password != null) {
            login(builder.user, builder.password);
        }
    }

    /**
     * Fetches the API version from the server. This method bypasses regular authentication.
     *
     * @return The fetched {@link APIVersion}.
     * @throws CommunicationException if communication with the server fails.
     * @throws ProcessingException    if the response cannot be processed.
     * @throws ApiRequestException    if the Zabbix API returns an error.
     */
    private APIVersion fetchApiVersionFromServer() {
        logger.debug("Fetching API version from server: {}", serverUrl);
        // 'apiinfo.version' does not require authentication
        String oldToken = this.sessionAuthToken;
        boolean oldIsTokenAuth = this.isTokenAuth;
        // Temporarily remove auth for this call if any exists
        this.sessionAuthToken = null;
        try {
            JsonNode response = callInternal("apiinfo.version", Collections.emptyMap(), true);
            String versionString = response.asText();
            if (versionString == null || versionString.isEmpty()) {
                throw new ProcessingException("Received empty or null API version string from server.");
            }
            logger.info("Successfully fetched Zabbix API version: {}", versionString);
            return new APIVersion(versionString);
        } finally {
            // Restore auth
            this.sessionAuthToken = oldToken;
            this.isTokenAuth = oldIsTokenAuth;
        }
    }

    /**
     * Fetches and checks the Zabbix API version against supported versions.
     * Stores the version in {@link #apiVersion} upon successful fetch and validation.
     *
     * @param skipVersionCheck             If true, compatibility check is skipped (warning logged).
     * @param calledFromConstructorOrLogin If true, indicates this method is called during initial setup.
     * @throws ApiNotSupportedException if the version is unsupported and not skipped.
     * @throws CommunicationException   if communication fails.
     * @throws ProcessingException      if response processing fails.
     */
    private void fetchAndCheckApiVersion(boolean skipVersionCheck, boolean calledFromConstructorOrLogin) {
        APIVersion currentVersion = apiVersion.get();
        if (currentVersion != null && !calledFromConstructorOrLogin) { // Don't use cached version if called from constructor
            return; // Already fetched and checked, unless forced by constructor/login
        }

        APIVersion fetchedVersion = fetchApiVersionFromServer();

        if (ZabbixApiConstants.MIN_SUPPORTED_ZABBIX_API_VERSION > 0 &&
                fetchedVersion.isLessThan(ZabbixApiConstants.MIN_SUPPORTED_ZABBIX_API_VERSION)) {
            String msg = String.format("Zabbix API version %s is older than minimum supported version %s.",
                    fetchedVersion.getRawVersion(), ZabbixApiConstants.MIN_SUPPORTED_ZABBIX_API_VERSION);
            if (skipVersionCheck) {
                logger.warn("{}. Version check was skipped by configuration.", msg);
            } else {
                throw new ApiNotSupportedException(msg, fetchedVersion.getRawVersion());
            }
        }

        if (ZabbixApiConstants.MAX_SUPPORTED_ZABBIX_API_VERSION > 0 &&
                fetchedVersion.isGreaterThan(ZabbixApiConstants.MAX_SUPPORTED_ZABBIX_API_VERSION)) {
            String msg = String.format("Zabbix API version %s is newer than maximum supported version %s.",
                    fetchedVersion.getRawVersion(), ZabbixApiConstants.MAX_SUPPORTED_ZABBIX_API_VERSION);
            if (skipVersionCheck) {
                logger.warn("{}. Version check was skipped by configuration.", msg);
            } else {
                throw new ApiNotSupportedException(msg, fetchedVersion.getRawVersion());
            }
        }
        this.apiVersion.set(fetchedVersion);
        logger.info("Zabbix API version {} is supported.", fetchedVersion.getRawVersion());
    }

    /**
     * Gets the Zabbix API version. If not already determined, it fetches the version from the server.
     *
     * @return The {@link APIVersion} of the Zabbix server.
     * @throws CommunicationException if communication with the server fails.
     * @throws ProcessingException    if the response cannot be processed.
     * @throws ApiRequestException    if the Zabbix API returns an error for `apiinfo.version`.
     */
    public APIVersion getApiVersion() {
        APIVersion currentVersion = this.apiVersion.get();
        if (currentVersion == null) {
            // Synchronize to prevent multiple threads from fetching simultaneously if called externally
            synchronized (this.apiVersion) {
                currentVersion = this.apiVersion.get(); // Double-check lock
                if (currentVersion == null) {
                    // Perform check, but don't fail if builder skipped it initially.
                    // The exception would have been thrown in constructor if not skipped.
                    fetchAndCheckApiVersion(true, false); // Allow skipping if it was set in builder
                    currentVersion = this.apiVersion.get();
                    if (currentVersion == null) { // Should not happen if fetchAndCheckApiVersion works
                        throw new ProcessingException("Failed to determine API version after fetch attempt.");
                    }
                }
            }
        }
        return currentVersion;
    }


    /**
     * Logs in to the Zabbix API using user credentials.
     * This method authenticates with the Zabbix server and stores the session token.
     *
     * @param user     The Zabbix username.
     * @param password The Zabbix password.
     * @return The authentication token (session ID).
     * @throws ApiRequestException    if login fails (e.g., invalid credentials).
     * @throws CommunicationException if communication with the server fails.
     * @throws ProcessingException    if the response cannot be processed.
     */
    public String login(String user, String password) {
        Objects.requireNonNull(user, "Username cannot be null for login.");
        Objects.requireNonNull(password, "Password cannot be null for login.");

        // Ensure API version is known before login to handle different param names
        APIVersion version = getApiVersion(); // This will fetch if not already available

        Map<String, Object> params = new HashMap<>();
        if (version.isLessThan(5.4f)) {
            params.put("user", user);
        } else {
            params.put("username", user);
        }
        params.put("password", password);
        // For Zabbix 6.0+, `user.login` can accept `userdata: true` to get more user details.
        // Not strictly required for login itself.

        // Clear any existing token before attempting new login
        synchronized (this) {
            this.sessionAuthToken = null;
            this.isTokenAuth = false;
        }

        JsonNode responseNode = callInternal("user.login", params, true); // Bypass normal auth for login
        String token = responseNode.asText();

        if (token == null || token.isEmpty()) {
            throw new ProcessingException("Received empty or null session token from user.login.");
        }

        synchronized (this) {
            this.sessionAuthToken = token;
            this.isTokenAuth = false; // Session auth, not token auth
        }
        logger.info("Successfully logged in as user '{}'. Session type: User/Password.", user);
        // After successful login, re-check API version if it was skipped by builder,
        // as login might be the first authenticated call that could reveal version issues.
        if (apiVersion.get() == null) { // Should not happen if getApiVersion() was called
             fetchAndCheckApiVersion(false, true);
        }

        return token;
    }

    /**
     * Authenticates using a pre-existing API token.
     *
     * @param token The API token.
     * @throws ApiNotSupportedException if using token authentication with Zabbix API version < 5.4.
     * @throws IllegalArgumentException if the token is null or empty.
     */
    public void loginWithToken(String token) {
        Objects.requireNonNull(token, "Token cannot be null for token authentication.");
        if (token.trim().isEmpty()) {
            throw new IllegalArgumentException("Token cannot be empty for token authentication.");
        }

        APIVersion version = getApiVersion(); // Ensure version is known
        if (version.isLessThan(5.4f)) {
            throw new ApiNotSupportedException("Token authentication is not supported for Zabbix API versions older than 5.4.", version.getRawVersion());
        }

        synchronized (this) {
            this.sessionAuthToken = token;
            this.isTokenAuth = true;
        }
        logger.info("Successfully authenticated using API token. Session type: Token.");
         if (apiVersion.get() == null) {
             fetchAndCheckApiVersion(false, true);
        }
    }

    /**
     * Logs out from the Zabbix API.
     * If authenticated via user/password, it calls the `user.logout` method.
     * If authenticated via token, it only clears the local token (no API call).
     *
     * @return true if logout was successful or not needed, false if API call failed.
     */
    public boolean logout() {
        if (!isAuthenticated()) {
            logger.info("Not authenticated, logout not required.");
            return true;
        }

        synchronized (this) {
            if (!isTokenAuth) { // Only call user.logout if it was a user/password session
                try {
                    // user.logout expects an empty array as params for some versions,
                    // or a map {"sessionids": [token]} for others.
                    // Sending an empty map or list usually works.
                    callInternal("user.logout", Collections.emptyList(), false); // Use existing auth
                    logger.info("Successfully logged out from Zabbix API (user.logout called).");
                } catch (ZabbixApiException e) {
                    logger.error("Failed to call user.logout: {}", e.getMessage(), e);
                    // Still clear local token even if API call fails
                    this.sessionAuthToken = null;
                    return false;
                }
            } else {
                logger.info("Logged out by clearing local API token (token auth was used).");
            }
            this.sessionAuthToken = null;
            // isTokenAuth remains true/false based on last login type until next login
        }
        return true;
    }

    /**
     * Checks if the client is currently authenticated (i.e., has a session token).
     *
     * @return true if authenticated, false otherwise.
     */
    public boolean isAuthenticated() {
        return this.sessionAuthToken != null && !this.sessionAuthToken.isEmpty();
    }

    /**
     * Verifies the current authentication (session or token) with the Zabbix server.
     *
     * @return true if authentication is valid, false otherwise.
     * @throws CommunicationException if communication with the server fails.
     * @throws ProcessingException    if the response cannot be processed.
     *                                (ApiRequestException for invalid session is caught and returns false)
     */
    public boolean checkAuthentication() {
        if (!isAuthenticated()) {
            return false;
        }
        try {
            // user.checkAuthentication does not take parameters.
            // Some docs say it needs {"sessionid": "token"} but official examples show empty.
            // Sending empty params is safer.
            JsonNode result = call("user.checkAuthentication", Collections.emptyMap());
            // Example result: { "jsonrpc": "2.0", "result": { "sessionid": "...", "userip": "..." }, "id": 1 }
            // If session is invalid, Zabbix might return an error, handled by call(), or specific result.
            // For now, assume any non-error response means it's valid.
            // More robust check might inspect the result content if needed.
            return result != null && result.has("sessionid");
        } catch (ApiRequestException e) {
            // Specific error codes might indicate invalid session, e.g., -32602 (Invalid params) if session is bad
            // or -32500 (Application error)
            logger.warn("Authentication check failed due to API error: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Makes a call to the Zabbix API.
     *
     * @param method The Zabbix API method (e.g., "host.get", "user.login").
     * @param params The parameters for the API method. Can be a Map or List, Jackson will serialize.
     * @return The "result" field from the JSON-RPC response as a {@link JsonNode}.
     * @throws ApiRequestException    if the Zabbix API returns an error.
     * @throws CommunicationException if there is a problem communicating with the Zabbix server.
     * @throws ProcessingException    if there is an issue with request/response processing or if not authenticated for a protected method.
     * @throws NullPointerException if method is null.
     */
    public JsonNode call(String method, Object params) {
        return callInternal(method, params, false);
    }

    /**
     * Internal method to make an API call, with an option to bypass auth checks for specific methods like login/apiinfo.
     */
    @SuppressWarnings("unchecked")
    private JsonNode callInternal(String method, Object params, boolean bypassAuthCheck) {
        Objects.requireNonNull(method, "API method cannot be null.");
        if (params == null) {
            params = Collections.emptyMap(); // Default to empty map if params are null
        }

        if (!bypassAuthCheck && !ZabbixUtils.UNAUTH_METHODS.contains(method) && !isAuthenticated()) {
            throw new ProcessingException("Not authenticated. Please login before calling method: " + method);
        }

        String requestId = UUID.randomUUID().toString();
        Map<String, Object> requestPayloadMap = new HashMap<>();
        requestPayloadMap.put("jsonrpc", JSON_RPC_VERSION);
        requestPayloadMap.put("method", method);
        requestPayloadMap.put("params", params);
        requestPayloadMap.put("id", requestId);

        // Authentication: token in header (preferred) or auth in payload
        String currentAuthToken = this.sessionAuthToken; // Local volatile read
        boolean useAuthInPayload = false;
        String authHeaderValue = null;

        if (currentAuthToken != null && !bypassAuthCheck && !ZabbixUtils.UNAUTH_METHODS.contains(method)) {
            APIVersion currentApiVersion = getApiVersion(); // Ensures version is known
            // Zabbix 6.4+ prefers Bearer token.
            // Zabbix 7.0.x requires Bearer token if *not* using HTTP Basic Auth.
            // If HTTP Basic Auth is active, Zabbix 7.0.x expects `auth` in payload for RPC token.
            boolean preferBearer = currentApiVersion.isGreaterThan(6.4f) ||
                                   (currentApiVersion.isEqualTo(6.4f));

            if (this.basicAuthCredentials != null && currentApiVersion.isGreaterThan(7.0f) || currentApiVersion.isEqualTo(7.0f)) {
                 // With Basic Auth on Zabbix 7.0+, RPC-level token goes in 'auth' field.
                useAuthInPayload = true;
            } else if (preferBearer) {
                authHeaderValue = "Bearer " + currentAuthToken;
            } else {
                // Older versions or specific configs might need 'auth' in payload
                useAuthInPayload = true;
            }

            if (useAuthInPayload) {
                requestPayloadMap.put("auth", currentAuthToken);
            }
        }

        String requestJson;
        try {
            requestJson = objectMapper.writeValueAsString(requestPayloadMap);
        } catch (JsonProcessingException e) {
            throw new ProcessingException("Failed to serialize request payload: " + e.getMessage(), e);
        }

        if (logger.isDebugEnabled()) {
            Map<String, Object> paramsToLog = Collections.emptyMap();
            if (params instanceof Map) {
                 paramsToLog = ZabbixUtils.hidePrivate((Map<String,Object>)params);
            } else if (params instanceof List && !((List<?>) params).isEmpty() && ((List<?>) params).get(0) instanceof Map) {
                // Crude way to log if it's a list of maps - log first element only for brevity
                // A better way would be to iterate and mask each map if this is a common pattern.
                // For now, this is a simple approach.
                // paramsToLog = ZabbixUtils.hidePrivate((Map<String,Object>)((List<?>) params).get(0));
                // Or just log the method without detailed params for lists of complex objects
                 logger.debug("Executing Zabbix API request (List params not fully shown for brevity). Method: {}, ID: {}", method, requestId);
            } else {
                 logger.debug("Executing Zabbix API request. Method: {}, Params: {}, ID: {}", method, paramsToLog, requestId);
            }
             // Full request logging (potentially large, use with caution or further filtering)
             // logger.trace("Full request JSON (masked if hidePrivate was effective): {}", ZabbixUtils.hidePrivate(requestPayloadMap));
        }


        HttpPost httpPost = new HttpPost(this.serverUrl);
        httpPost.setHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON_RPC.getMimeType());
        httpPost.setHeader(HttpHeaders.ACCEPT, ContentType.APPLICATION_JSON.getMimeType());
        httpPost.setHeader(HttpHeaders.USER_AGENT, this.userAgent);

        if (this.basicAuthCredentials != null) {
            httpPost.setHeader(HttpHeaders.AUTHORIZATION, "Basic " + this.basicAuthCredentials);
        } else if (authHeaderValue != null) { // Bearer token, not basic auth
            httpPost.setHeader(HttpHeaders.AUTHORIZATION, authHeaderValue);
        }

        httpPost.setEntity(new StringEntity(requestJson, StandardCharsets.UTF_8));

        try (var response = httpClient.execute(httpPost)) {
            int statusCode = response.getCode();
            String responseJson = response.getEntity() != null ?
                    new String(response.getEntity().getContent().readAllBytes(), StandardCharsets.UTF_8) : null;

            if (logger.isDebugEnabled()) {
                String responseToLog = responseJson;
                if (responseJson != null && ZabbixUtils.FILES_METHODS.contains(method)) {
                    responseToLog = "(Response content for files method hidden)";
                } else if (responseJson != null && responseJson.length() > 1000) {
                     responseToLog = responseJson.substring(0, 1000) + "... (truncated)";
                }
                logger.debug("Received Zabbix API response. Status: {}, ID: {}, Response: {}", statusCode, requestId, responseToLog);
            }


            if (statusCode < 200 || statusCode >= 300) {
                throw new CommunicationException(String.format("HTTP request failed with status %d: %s. Response: %s",
                        statusCode, response.getReasonPhrase(), responseJson));
            }

            if (responseJson == null || responseJson.isEmpty()) {
                throw new ProcessingException("Received empty response from Zabbix API.");
            }

            JsonNode responseNode = objectMapper.readTree(responseJson);

            if (responseNode.has("error")) {
                JsonNode errorNode = responseNode.get("error");
                int code = errorNode.path("code").asInt();
                String message = errorNode.path("message").asText();
                String data = errorNode.path("data").asText();
                // For logging, include the original request that caused the error
                Map<String, Object> maskedParams = params instanceof Map ? ZabbixUtils.hidePrivate((Map<String,Object>)params) : Collections.emptyMap();
                String requestContext = String.format("Method: %s, Params: %s", method, maskedParams);

                throw new ApiRequestException(code, message, data, requestContext);
            }

            if (!responseNode.has("result")) {
                throw new ProcessingException("Zabbix API response is missing 'result' field. Response: " + responseJson);
            }

            return responseNode.get("result");

        } catch (IOException e) {
            throw new CommunicationException("Failed to execute API request " + method + ": " + e.getMessage(), e);
        }
    }


    @Override
    public void close() throws IOException {
        if (this.httpClient != null && this.managedHttpClient) {
            this.httpClient.close();
            logger.info("ZabbixApiSync managed HttpClient closed.");
        }
    }

    /**
     * Builder for {@link ZabbixApiSync}.
     */
    public static class Builder {
        private String url;
        private String user;
        private String password;
        private String authToken;
        private String basicAuthUser;
        private String basicAuthPassword;
        private SSLContext sslContext;
        private int timeoutSeconds = -1; // Default: no specific timeout, use HttpClient's default
        private CloseableHttpClient httpClient;
        private boolean skipVersionCheck = false;

        /**
         * Sets the Zabbix API URL. (Required)
         * @param url Example: "http://zabbix.example.com/zabbix" or "https://zabbix.example.com"
         *            The path "/api_jsonrpc.php" will be appended if missing.
         * @return this builder
         */
        public Builder url(String url) {
            this.url = url;
            return this;
        }

        /**
         * Sets user credentials for session-based authentication.
         * If both user/password and token are provided, user/password takes precedence for initial login.
         * @param user The Zabbix username.
         * @param password The Zabbix password.
         * @return this builder
         */
        public Builder user(String user, String password) {
            this.user = user;
            this.password = password;
            return this;
        }

        /**
         * Sets an API token for authentication.
         * @param authToken The Zabbix API token.
         * @return this builder
         */
        public Builder token(String authToken) {
            this.authToken = authToken;
            return this;
        }

        /**
         * Sets credentials for HTTP Basic Authentication.
         * @param username The username for Basic Auth.
         * @param password The password for Basic Auth.
         * @return this builder
         */
        public Builder basicAuth(String username, String password) {
            this.basicAuthUser = username;
            this.basicAuthPassword = password;
            return this;
        }

        /**
         * Sets the SSL context for HTTPS connections.
         * @param sslContext The SSLContext to use.
         * @return this builder
         */
        public Builder sslContext(SSLContext sslContext) {
            this.sslContext = sslContext;
            return this;
        }

        /**
         * Sets connection and socket timeouts for HTTP requests.
         * @param seconds The timeout duration in seconds. If <= 0, default HttpClient timeout is used.
         * @return this builder
         */
        public Builder timeout(int seconds) {
            this.timeoutSeconds = seconds;
            return this;
        }

        /**
         * Allows providing a custom {@link CloseableHttpClient}. If provided, SSL context and timeout
         * settings on this builder will be ignored for client construction, but the client will not be
         * managed (closed) by {@link ZabbixApiSync#close()}.
         * @param httpClient The custom HttpClient instance.
         * @return this builder
         */
        public Builder httpClient(CloseableHttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        /**
         * If set to true, skips the Zabbix API version compatibility check.
         * Use with caution, as it may lead to unexpected errors if the API version is not supported.
         * @param skipVersionCheck true to skip the check, false otherwise (default).
         * @return this builder
         */
        public Builder skipVersionCheck(boolean skipVersionCheck) {
            this.skipVersionCheck = skipVersionCheck;
            return this;
        }

        /**
         * Builds the {@link ZabbixApiSync} instance.
         * @return A new ZabbixApiSync instance.
         * @throws IllegalArgumentException if required parameters (like URL) are missing.
         * @throws ZabbixApiException if initial API version check or login fails.
         */
        public ZabbixApiSync build() {
            if (url == null || url.trim().isEmpty()) {
                throw new IllegalArgumentException("Zabbix API URL must be provided.");
            }
            // Add other validations if necessary
            return new ZabbixApiSync(this);
        }
    }

    // Optional: Fluent API method helpers (as suggested in requirements)
    // For this subtask, focusing on the core 'call' method. These can be added later.
    /*
    public static class ApiMethod {
        private final ZabbixApiSync api;
        private final String methodName;

        public ApiMethod(ZabbixApiSync api, String objectName, String actionName) {
            this.api = api;
            this.methodName = objectName + "." + actionName;
        }
        public JsonNode call(Object params) { return api.call(methodName, params); }
        public JsonNode call() { return api.call(methodName, Collections.emptyMap()); }
    }

    public ApiMethod method(String objectName, String actionName) {
        return new ApiMethod(this, objectName, actionName);
    }
    */
}
