package io.zabbix4j.api;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.zabbix4j.api.common.DataMaskingUtils;
import io.zabbix4j.api.common.ZabbixApiConstants;
import io.zabbix4j.api.exceptions.ApiNotSupportedException;
import io.zabbix4j.api.exceptions.ApiRequestException;
import io.zabbix4j.api.exceptions.ProcessingException;
import io.zabbix4j.api.exceptions.ZabbixApiException;
import io.zabbix4j.api.types.ApiVersion;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.io.IOException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Main class for interacting with the Zabbix API.
 * <p>
 * This class provides methods for sending requests to the Zabbix API,
 * handling authentication, and managing API versions.
 * Use the {@link ZabbixApiBuilder} to construct instances of this class.
 * </p>
 * This class implements {@link AutoCloseable} for resource management,
 * primarily for logging out of sessions when used in a try-with-resources block.
 *
 * @author CSJ
 */
public class ZabbixApi implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ZabbixApi.class);

    private final String apiUrl;
    private final OkHttpClient httpClient;
    private final boolean skipVersionCheck;
    private String authToken; // Can be API token or session ID
    private boolean useTokenAuth; // True if authToken is an API token
    private ApiVersion zabbixApiVersion; // Cached API version
    private final String basicAuthCredentials; // Base64 encoded "user:pass" for HTTP Basic Auth
    private final Gson gson;

    // Store user/pass if provided via builder for potential re-login logic (though not explicitly used for re-login yet)
    private String loginUser;
    private String loginPassword;


    // Supported Zabbix API versions
    private static final double MIN_SUPPORTED_VERSION = 5.0;
    private static final double MAX_TESTED_VERSION = 7.0;


    /**
     * Private constructor. Use {@link ZabbixApiBuilder} to create instances.
     *
     * @param builder The builder instance with configuration.
     */
    private ZabbixApi(ZabbixApiBuilder builder) {
        this.apiUrl = builder.url; // Already sanitized by builder
        this.skipVersionCheck = builder.skipVersionCheck;
        this.basicAuthCredentials = builder.basicAuthCredentials; // Set by builder
        this.loginUser = builder.user; // Store from builder
        this.loginPassword = builder.password; // Store from builder

        // Configure OkHttpClient
        OkHttpClient.Builder okHttpClientBuilder = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(builder.timeoutSeconds))
                .readTimeout(Duration.ofSeconds(builder.timeoutSeconds))
                .writeTimeout(Duration.ofSeconds(builder.timeoutSeconds));

        if (builder.sslContext != null) {
            // Find the X509TrustManager from the SSLContext if possible, or use default
            X509TrustManager trustManager = null;
            for (TrustManager tm : builder.sslContext.getSocketFactory().getDefaultCipherSuites() == null ? // this is a bit of a hack to check if default CAs are loaded
                    new TrustManager[]{createTrustAllManager()} : builder.sslContext.getTrustManagers()) {
                if (tm instanceof X509TrustManager) {
                    trustManager = (X509TrustManager) tm;
                    break;
                }
            }
            if (trustManager == null) { // Should not happen with standard SSLContexts
                logger.warn("Could not find X509TrustManager in provided SSLContext. Using default TrustManager logic for OkHttp.");
                 okHttpClientBuilder.sslSocketFactory(builder.sslContext.getSocketFactory(), (X509TrustManager) createTrustAllManager()); // OkHttp needs X509TrustManager explicitly
            } else {
                 okHttpClientBuilder.sslSocketFactory(builder.sslContext.getSocketFactory(), trustManager);
            }


        } else if (!builder.validateCerts) {
            try {
                final TrustManager[] trustAllCerts = new TrustManager[]{createTrustAllManager()};
                final SSLContext sslContext = SSLContext.getInstance("SSL");
                sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
                final SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();
                okHttpClientBuilder.sslSocketFactory(sslSocketFactory, (X509TrustManager) trustAllCerts[0]);
                okHttpClientBuilder.hostnameVerifier((hostname, session) -> true);
                logger.warn("Certificate validation is disabled. This is insecure and should only be used in trusted environments.");
            } catch (NoSuchAlgorithmException | KeyManagementException e) {
                // This should not happen with standard algorithms.
                throw new RuntimeException("Failed to configure trust-all SSL context", e);
            }
        }
        // If builder.sslContext is null AND builder.validateCerts is true, OkHttp uses default SSL settings.

        this.httpClient = okHttpClientBuilder.build();

        if (builder.token != null) {
            this.authToken = builder.token;
            this.useTokenAuth = true;
        } else if (builder.user != null && builder.password != null) {
            // User/password login will be handled by an explicit login() call later,
            // which will then set authToken and useTokenAuth=false.
            // For now, authToken remains null.
            this.useTokenAuth = false; // Will be set by login method
        }


        this.gson = new Gson();
        // Note: API version is not fetched here. It's fetched on demand by getApiVersion().
        // Authentication (login with user/pass) is also not done here.
        // Auto-login is handled by the builder after instance creation.
    }

    /**
     * Sanitizes the provided URL to ensure it's valid for Zabbix API requests.
     * It checks if the URL starts with "http://" or "https://" and ends with "/api_jsonrpc.php".
     * If the URL does not end with "/api_jsonrpc.php", it attempts to append it.
     *
     * @param url The URL to sanitize.
     * @return The sanitized URL.
     * @throws IllegalArgumentException if the URL is null, empty, does not start with http/https,
     *                                  or is otherwise malformed.
     */
    private static String sanitizeUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("API URL cannot be null or empty.");
        }
        String trimmedUrl = url.trim();
        if (!(trimmedUrl.startsWith("http://") || trimmedUrl.startsWith("https://"))) {
            throw new IllegalArgumentException("API URL must start with http:// or https://. Received: " + trimmedUrl);
        }
        if (!trimmedUrl.endsWith(ZabbixApiConstants.JSONRPC_FILE)) {
            if (trimmedUrl.endsWith("/")) {
                trimmedUrl += ZabbixApiConstants.JSONRPC_FILE;
            } else {
                trimmedUrl += "/" + ZabbixApiConstants.JSONRPC_FILE;
            }
            logger.debug("Appended '{}' to the API URL. New URL: {}", ZabbixApiConstants.JSONRPC_FILE, trimmedUrl);
        }
        return trimmedUrl;
    }

    private static X509TrustManager createTrustAllManager() {
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[]{};
            }
        };
    }

    /**
     * Gets the Zabbix API version.
     * <p>
     * The version is fetched from the Zabbix server on the first call and then cached.
     * This method may perform a network request if the version is not already cached.
     * </p>
     *
     * @return The {@link ApiVersion} object representing the Zabbix server's API version.
     * @throws ZabbixApiException if there's an error fetching the API version (e.g., network issue, API error).
     */
    public ApiVersion getApiVersion() throws ZabbixApiException {
        if (this.zabbixApiVersion == null) {
            fetchAndCacheApiVersion();
        }
        return this.zabbixApiVersion;
    }

    /**
     * Fetches the API version from the Zabbix server, caches it, and checks compatibility.
     * <p>
     * For Part 1 of this subtask, this is a placeholder.
     * </p>
     *
     * @throws ZabbixApiException if there's an error during the API request or version checking.
     */
    private void fetchAndCacheApiVersion() throws ZabbixApiException {
        logger.debug("Fetching Zabbix API version...");
        try {
            Object versionResult = sendApiRequestInternal("apiinfo.version", null, false);
            if (!(versionResult instanceof String)) {
                throw new ProcessingException(String.format("Expected string response for apiinfo.version, but got %s",
                        versionResult != null ? versionResult.getClass().getName() : "null"));
            }
            this.zabbixApiVersion = new ApiVersion((String) versionResult);
            logger.info("Successfully fetched and cached Zabbix API version: {}", this.zabbixApiVersion);
            checkZabbixVersionCompatibility();
        } catch (ZabbixApiException e) {
            // Log specific error related to version fetching
            logger.error("Failed to fetch Zabbix API version. Client may not work as expected.", e);
            throw e; // Re-throw the exception to the caller
        }
    }


    /**
     * Core method for sending requests to the Zabbix API.
     *
     * @param method       The Zabbix API method name (e.g., "item.get", "apiinfo.version").
     * @param params       The parameters for the API method. Can be a Map, List, or other object serializable by Gson.
     *                     If null, an empty map will be used.
     * @param requiresAuth True if the API method requires authentication, false otherwise.
     * @return The "result" field from the Zabbix JSON-RPC response, as an Object.
     * @throws ZabbixApiException If any error occurs during the API request, processing, or if Zabbix API returns an error.
     */
    private Object sendApiRequestInternal(String method, Object params, boolean requiresAuth) throws ZabbixApiException {
        // 1. Ensure API version is known if auth is required for version-specific logic
        if (requiresAuth && this.zabbixApiVersion == null && this.authToken != null) {
            // If authToken is present but version is not, it implies we might need version for auth logic.
            // This call will fetch and cache the version if not already done.
            // If authToken is null, getApiVersion() will be called later if needed or error out.
            getApiVersion();
        }

        // 2. JSON-RPC Request Body Construction
        Map<String, Object> requestBodyMap = new HashMap<>();
        requestBodyMap.put("jsonrpc", "2.0");
        requestBodyMap.put("method", method);
        requestBodyMap.put("params", params != null ? params : Collections.emptyMap()); // Use empty map if params is null
        requestBodyMap.put("id", UUID.randomUUID().toString());

        boolean tokenInHeader = false;

        if (requiresAuth) {
            if (this.authToken == null) {
                throw new ProcessingException("Authentication required for method '" + method + "', but not logged in or token not set.");
            }
            // Ensure version is available for auth logic
            ApiVersion version = getApiVersion(); // This will fetch if not already cached

            if (version.getMajor() < 6.4 || (version.getMajor() <= 7.0 && this.basicAuthCredentials != null) ) {
                 requestBodyMap.put(ZabbixApiConstants.FIELD_AUTH, this.authToken);
                 logger.debug("Using 'auth' field in JSON body for authentication token for Zabbix version {}", version);
            } else {
                // Token goes into HTTP Authorization: Bearer header
                tokenInHeader = true;
                logger.debug("Using 'Authorization: Bearer' header for authentication token for Zabbix version {}", version);
            }
        }

        String jsonRequestString = gson.toJson(requestBodyMap);

        // Log request (masked)
        // Using hidePrivateFields on the map before serialization for logging, as hidePrivateFieldsInJsonString is placeholder.
        Map<String, Object> loggedRequestBody = DataMaskingUtils.hidePrivateFields(new HashMap<>(requestBodyMap)); // Create copy for masking
        logger.debug("Sending API request. Method: '{}', Body (masked): {}", method, gson.toJson(loggedRequestBody));


        // 3. OkHttp Request Setup
        RequestBody okHttpRequestBody = RequestBody.create(jsonRequestString, MediaType.get("application/json-rpc"));
        Request.Builder requestBuilder = new Request.Builder()
                .url(this.apiUrl)
                .post(okHttpRequestBody)
                .addHeader("Accept", "application/json")
                // Content-Type is set by RequestBody.create
                .addHeader("User-Agent", "io.zabbix4j.api/" + ZabbixApiConstants.CLIENT_LIB_VERSION);

        // HTTP Basic Authentication Header
        if (this.basicAuthCredentials != null) {
            requestBuilder.header("Authorization", "Basic " + this.basicAuthCredentials);
            logger.debug("Added HTTP Basic Authentication header.");
        }

        // Bearer Token Authentication Header (potentially overwrites Basic Auth if both were set for Authorization)
        if (tokenInHeader) { // tokenInHeader is true if requiresAuth, authToken is set, and version >= 6.4
            requestBuilder.header("Authorization", "Bearer " + this.authToken); // Overwrites if Basic was also added
            logger.debug("Added/Replaced 'Authorization: Bearer' header for API token.");
        }


        // 4. Execute Request and Get Response
        try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
            String responseBodyString = response.body() != null ? response.body().string() : null;

            if (!response.isSuccessful()) {
                String errorMsg = String.format("API request failed: HTTP %d - %s. Response body: %s",
                        response.code(), response.message(), responseBodyString != null ? responseBodyString : "N/A");
                logger.error(errorMsg);
                throw new ProcessingException(errorMsg);
            }

            if (responseBodyString == null || responseBodyString.isEmpty()) {
                throw new ProcessingException("Received empty response body from API.");
            }

            // Log response (masked/clipped)
            if (ZabbixApiConstants.FILE_CONTENT_METHODS.contains(method)) {
                logger.debug("Received API response for method '{}' (content clipped): {}...",
                        method, responseBodyString.substring(0, Math.min(responseBodyString.length(), 200)));
            } else {
                // For non-file methods, consider masking if response could be sensitive.
                // For now, logging directly, assuming general responses are not overly sensitive or are covered by field masking.
                // A more robust solution would parse to map, mask, then log.
                logger.debug("Received API response for method '{}': {}", method, responseBodyString);
            }

            // 5. Parse JSON Response
            Map<String, Object> responseMap = gson.fromJson(responseBodyString, new TypeToken<Map<String, Object>>() {}.getType());

            if (responseMap.containsKey("error")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> errorObject = (Map<String, Object>) responseMap.get("error");
                String errorMessage = (String) errorObject.get("message");
                // Code can be integer or string in some older Zabbix versions, robustly parse
                Object codeObj = errorObject.get("code");
                Integer errorCode = null;
                if (codeObj instanceof Number) {
                    errorCode = ((Number) codeObj).intValue();
                } else if (codeObj instanceof String) {
                    try {
                        errorCode = Integer.parseInt((String) codeObj);
                    } catch (NumberFormatException e) {
                        logger.warn("Could not parse error code from string: {}", codeObj, e);
                    }
                }
                String errorData = (String) errorObject.get("data");

                logger.error("Zabbix API Error. Method: '{}', Code: {}, Message: '{}', Data: '{}'",
                        method, errorCode, errorMessage, errorData);
                throw new ApiRequestException(errorMessage, errorCode, errorData, requestBodyMap); // Pass original request for context
            }

            if (!responseMap.containsKey("result")) {
                throw new ProcessingException("Invalid API response: Missing 'result' field. Response: " + responseBodyString);
            }

            return responseMap.get("result");

        } catch (IOException e) {
            logger.error("IOException during API request to {}. Method: '{}'", this.apiUrl, method, e);
            throw new ProcessingException("Failed to execute API request due to network or I/O error: " + e.getMessage(), e);
        } catch (com.google.gson.JsonSyntaxException e) {
            logger.error("JSON Syntax exception while parsing API response for method '{}'", method, e);
            throw new ProcessingException("Failed to parse API response JSON: " + e.getMessage(), e);
        }
    }


    /**
     * Checks if the cached Zabbix API version is within the supported range.
     *
     * @throws ApiNotSupportedException if the version is not supported and skipVersionCheck is false.
     */
    private void checkZabbixVersionCompatibility() throws ApiNotSupportedException {
        if (skipVersionCheck) {
            logger.debug("Zabbix API version check is skipped.");
            return;
        }

        if (this.zabbixApiVersion == null) {
            // This should ideally not happen if fetchAndCacheApiVersion was called first.
            logger.warn("Cannot check Zabbix version compatibility: API version not fetched yet.");
            return;
        }

        double currentVersionMajor = this.zabbixApiVersion.getMajor(); // e.g., 6.0

        if (currentVersionMajor < MIN_SUPPORTED_VERSION) {
            throw new ApiNotSupportedException(
                    String.format("Zabbix API version %s is not supported. Minimum supported version is %s.",
                            this.zabbixApiVersion.toString(), MIN_SUPPORTED_VERSION),
                    this.zabbixApiVersion.toString()
            );
        }
        if (currentVersionMajor > MAX_TESTED_VERSION) {
            // In Python, this was a warning. Task asks to throw an exception.
            throw new ApiNotSupportedException(
                    String.format("Zabbix API version %s is newer than the maximum tested version %s. " +
                                    "Full compatibility is not guaranteed.",
                            this.zabbixApiVersion.toString(), MAX_TESTED_VERSION),
                    this.zabbixApiVersion.toString()
            );
        }
        logger.info("Zabbix API version {} is compatible.", this.zabbixApiVersion);
    }


    /**
     * Closes this Zabbix API client, performing cleanup such as logging out if authenticated.
     * This method is called automatically when the client is used in a try-with-resources statement.
     *
     * @throws ZabbixApiException if an error occurs during logout.
     */
    @Override
    public void close() throws ZabbixApiException {
        logout(); // Attempt to logout
        // Clear sensitive fields that logout might not clear (though authToken is cleared by logout)
        this.loginPassword = null; // Clear stored password if any
        logger.info("ZabbixApi client closed and resources cleaned up.");
    }

    /**
     * Logs in to the Zabbix API using username and password.
     * If an API token was previously configured, it will be replaced by the new session ID.
     *
     * @param user     The Zabbix username.
     * @param password The Zabbix password.
     * @throws ZabbixApiException If login fails or an API error occurs.
     * @throws IllegalArgumentException if user or password is null or empty.
     */
    public void login(String user, String password) throws ZabbixApiException {
        if (user == null || user.isEmpty()) {
            throw new IllegalArgumentException("Username cannot be null or empty for login.");
        }
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("Password cannot be null or empty for login.");
        }

        getApiVersion(); // Ensure API version is fetched

        Map<String, Object> params;
        if (this.zabbixApiVersion.getMajor() < 5.4) {
            params = Map.of("user", user, "password", password);
        } else {
            params = Map.of("username", user, "password", password);
        }

        logger.info("Attempting to login with user: '{}'", user);
        Object result = sendApiRequestInternal("user.login", params, false);

        if (!(result instanceof String)) {
            throw new ProcessingException(String.format("Expected string (session ID) from user.login, but got %s",
                    result != null ? result.getClass().getName() : "null"));
        }

        this.authToken = (String) result;
        this.useTokenAuth = false; // This is a session ID
        this.loginUser = user; // Store current successfully logged-in user
        this.loginPassword = password; // Store current successfully logged-in password (consider security implications)

        logger.info("Successfully logged in with user: '{}'. Session ID obtained.", user);
    }

    /**
     * Configures the client to use an API token for authentication.
     * If a session was previously established via user/password login, it will be overridden.
     *
     * @param token The Zabbix API token.
     * @throws ZabbixApiException If token authentication is not supported by the Zabbix API version.
     * @throws IllegalArgumentException if token is null or empty.
     */
    public void loginWithToken(String token) throws ZabbixApiException {
        if (token == null || token.isEmpty()) {
            throw new IllegalArgumentException("API token cannot be null or empty.");
        }

        getApiVersion(); // Ensure API version is fetched

        if (this.zabbixApiVersion.getMajor() < 5.4) {
            throw new ApiNotSupportedException("API token authentication (user.checkAuthentication with token parameter) " +
                    "is not reliably supported by Zabbix API versions older than 5.4.", this.zabbixApiVersion.toString());
        }

        this.authToken = token;
        this.useTokenAuth = true;
        logger.info("API token configured for authentication.");
        // Optionally, could call user.checkAuthentication here to validate the token immediately.
        // For now, matching Python client's behavior of just setting it.
    }

    /**
     * Logs out from the Zabbix API.
     * If using an API token, this attempts to invalidate the token/session on the server.
     * If using user/password session, this invalidates the session.
     *
     * @throws ZabbixApiException If logout fails or an API error occurs.
     */
    public void logout() throws ZabbixApiException {
        if (this.authToken == null) {
            logger.info("Not logged in or token not set. Logout not required.");
            return;
        }

        String authTypeMessage = useTokenAuth ? "API token" : "session";
        logger.info("Attempting to logout (invalidate {})...", authTypeMessage);

        try {
            // Parameters for user.logout are typically empty.
            // The server uses the provided auth token (from header or body) to identify the session/token to logout.
            Object result = sendApiRequestInternal("user.logout", Collections.emptyMap(), true);

            // Zabbix user.logout typically returns a boolean true for session logout,
            // or a map like {"sessionid": "token_value", "userid": "user_id"} for token logout on >=6.0
            // For simplicity, we'll just check if an exception was thrown.
            // A more specific check on 'result' could be done if needed.
            if (result instanceof Boolean && (Boolean) result || result instanceof Map) {
                 logger.info("Successfully logged out ({} invalidated).", authTypeMessage);
            } else {
                // This case might indicate an unexpected response format but not necessarily an error that sendApiRequestInternal would catch.
                logger.warn("Logout command sent, but response was not the typical boolean true or map: {}", result);
            }

        } catch (ApiRequestException e) {
            // Specific API errors during logout (e.g., token already invalid)
            logger.warn("API error during logout ({} may have already been invalid): {}", authTypeMessage, e.getMessage());
        } catch (ZabbixApiException e) {
            // Other errors like network issues
            logger.error("Failed to logout (invalidate {}) due to an API exception.", authTypeMessage, e);
            throw e; // Re-throw if it's a critical failure
        } finally {
            // Always clear local auth state regardless of server response for logout
            this.authToken = null;
            this.useTokenAuth = false;
            this.loginUser = null; // Clear user
            this.loginPassword = null; // Clear password
        }
    }

    /**
     * Checks if the current client is authenticated with the Zabbix API.
     * This is done by calling the `user.checkAuthentication` method.
     *
     * @return True if authenticated, false otherwise.
     * @throws ZabbixApiException if an error occurs during the API call, other than an auth error for checkAuthentication itself.
     */
    public boolean isAuthenticated() throws ZabbixApiException {
        if (this.authToken == null) {
            return false;
        }

        Map<String, String> params;
        if (this.useTokenAuth) {
            // For token-based auth, Zabbix 5.4+ expects 'token' in params for user.checkAuthentication.
            // Versions before that don't officially support token auth this way.
            // loginWithToken already checks for version >= 5.4.
            params = Map.of("token", this.authToken);
        } else {
            params = Map.of("sessionid", this.authToken);
        }

        try {
            // user.checkAuthentication itself does not require authentication if checking the current session/token.
            // The token/sessionid is passed in params.
            Object result = sendApiRequestInternal("user.checkAuthentication", params, false);

            if (result instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> resultMap = (Map<String, Object>) result;
                // Successful authentication usually returns a map containing 'sessionid' and 'userid'.
                // The presence of 'userid' and it being non-empty can be a good indicator.
                Object userIdObj = resultMap.get("userid");
                return userIdObj != null && !String.valueOf(userIdObj).isEmpty();
            }
            return false; // Should not happen if API call was successful and no error
        } catch (ApiRequestException e) {
            // An ApiRequestException (e.g., code -32602 "Invalid params", "Not authorised", etc.)
            // during checkAuthentication typically means the token/session is invalid.
            logger.debug("Authentication check failed with API error: {}. Assuming not authenticated.", e.getMessage());
            return false;
        }
        // Other ZabbixApiException types (ProcessingException, etc.) will propagate.
    }

    /**
     * A generic method to call any Zabbix API endpoint.
     * Authentication requirement is determined automatically based on the method name.
     *
     * @param method The Zabbix API method name (e.g., "item.get").
     * @param params The parameters for the API method. Can be null.
     * @return The "result" field from the Zabbix JSON-RPC response.
     * @throws ZabbixApiException If any error occurs.
     */
    public Object call(String method, Object params) throws ZabbixApiException {
        boolean requiresAuth = !ZabbixApiConstants.UNAUTHENTICATED_METHODS.contains(method.toLowerCase());
        return call(method, params, requiresAuth);
    }

    /**
     * A generic method to call any Zabbix API endpoint with no parameters.
     * Authentication requirement is determined automatically.
     *
     * @param method The Zabbix API method name.
     * @return The "result" field from the Zabbix JSON-RPC response.
     * @throws ZabbixApiException If any error occurs.
     */
    public Object call(String method) throws ZabbixApiException {
        return call(method, null);
    }

    /**
     * A generic method to call any Zabbix API endpoint, explicitly stating authentication requirement.
     *
     * @param method       The Zabbix API method name.
     * @param params       The parameters for the API method. Can be null.
     * @param requiresAuth True if the method requires authentication, false otherwise.
     * @return The "result" field from the Zabbix JSON-RPC response.
     * @throws ZabbixApiException If any error occurs.
     */
    public Object call(String method, Object params, boolean requiresAuth) throws ZabbixApiException {
        return sendApiRequestInternal(method, params, requiresAuth);
    }


    /**
     * Builder class for {@link ZabbixApi}.
     * Provides a fluent API for constructing {@code ZabbixApi} instances with custom configurations.
     */
    public static class ZabbixApiBuilder {
        private String url;
        private String user;
        private String password;
        private String token;
        private String httpUser;
        private String httpPassword;
        private String basicAuthCredentials; // For OkHttp basic auth
        private boolean skipVersionCheck = false;
        private boolean validateCerts = true;
        private int timeoutSeconds = 30;
        private SSLContext sslContext;

        /**
         * Sets the Zabbix API URL.
         * The URL will be sanitized to ensure it ends with "/api_jsonrpc.php".
         *
         * @param url The Zabbix API endpoint URL (e.g., "http://zabbix.example.com/zabbix").
         * @return This builder instance for chaining.
         */
        public ZabbixApiBuilder url(String url) {
            this.url = sanitizeUrl(url); // Sanitize and store
            return this;
        }

        /**
         * Sets the Zabbix API username for password-based authentication.
         * If a token is also set, token authentication will take precedence.
         *
         * @param user The Zabbix username.
         * @return This builder instance for chaining.
         */
        public ZabbixApiBuilder user(String user) {
            this.user = user;
            return this;
        }

        /**
         * Sets the Zabbix API password for password-based authentication.
         *
         * @param password The Zabbix password.
         * @return This builder instance for chaining.
         */
        public ZabbixApiBuilder password(String password) {
            this.password = password;
            return this;
        }

        /**
         * Sets the Zabbix API token for token-based authentication.
         * If set, this will override user/password authentication.
         *
         * @param token The Zabbix API token.
         * @return This builder instance for chaining.
         */
        public ZabbixApiBuilder token(String token) {
            this.token = token;
            return this;
        }

        /**
         * Sets the username for HTTP Basic Authentication.
         *
         * @param httpUser The HTTP Basic Auth username.
         * @return This builder instance for chaining.
         */
        public ZabbixApiBuilder httpUser(String httpUser) {
            this.httpUser = httpUser;
            return this;
        }

        /**
         * Sets the password for HTTP Basic Authentication.
         *
         * @param httpPassword The HTTP Basic Auth password.
         * @return This builder instance for chaining.
         */
        public ZabbixApiBuilder httpPassword(String httpPassword) {
            this.httpPassword = httpPassword;
            return this;
        }

        /**
         * If set to true, skips the Zabbix API version compatibility check.
         * Defaults to false.
         *
         * @param skipVersionCheck True to skip version check, false otherwise.
         * @return This builder instance for chaining.
         */
        public ZabbixApiBuilder skipVersionCheck(boolean skipVersionCheck) {
            this.skipVersionCheck = skipVersionCheck;
            return this;
        }

        /**
         * If set to false, disables SSL certificate validation.
         * Use with caution, only in trusted environments. Defaults to true.
         *
         * @param validateCerts False to disable certificate validation, true otherwise.
         * @return This builder instance for chaining.
         */
        public ZabbixApiBuilder validateCerts(boolean validateCerts) {
            this.validateCerts = validateCerts;
            return this;
        }

        /**
         * Sets the connection, read, and write timeout for HTTP requests.
         * Defaults to 30 seconds.
         *
         * @param timeoutSeconds The timeout duration in seconds.
         * @return This builder instance for chaining.
         */
        public ZabbixApiBuilder timeoutSeconds(int timeoutSeconds) {
            if (timeoutSeconds <= 0) {
                throw new IllegalArgumentException("Timeout must be positive.");
            }
            this.timeoutSeconds = timeoutSeconds;
            return this;
        }

        /**
         * Sets a custom SSLContext for HTTPS connections.
         * If set, this will be used for SSL configuration.
         *
         * @param sslContext The custom SSLContext.
         * @return This builder instance for chaining.
         */
        public ZabbixApiBuilder sslContext(SSLContext sslContext) {
            this.sslContext = sslContext;
            return this;
        }

        /**
         * Builds the {@link ZabbixApi} instance with the configured settings.
         *
         * @return A new {@code ZabbixApi} instance.
         * @throws IllegalArgumentException if the configuration is invalid (e.g., URL not set,
         *                                  conflicting authentication methods).
         * @throws ZabbixApiException if auto-login (if configured) fails.
         */
        public ZabbixApi build() throws ZabbixApiException {
            if (this.url == null || this.url.trim().isEmpty()) {
                throw new IllegalArgumentException("Zabbix API URL must be set.");
            }

            // Validate authentication methods
            boolean userPassAuth = (this.user != null && !this.user.isEmpty()) && (this.password != null && !this.password.isEmpty());
            boolean tokenAuth = (this.token != null && !this.token.isEmpty());

            if (userPassAuth && tokenAuth) {
                throw new IllegalArgumentException("Cannot configure both token-based and user/password-based authentication. Please choose one.");
            }
            // If neither is provided, it's okay; some methods might be public or login might be called later.

            // Handle HTTP Basic Authentication
            if ((this.httpUser != null && !this.httpUser.isEmpty()) && (this.httpPassword != null && !this.httpPassword.isEmpty())) {
                String credentials = this.httpUser + ":" + this.httpPassword;
                this.basicAuthCredentials = Base64.getEncoder().encodeToString(credentials.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } else if ((this.httpUser != null && !this.httpUser.isEmpty()) || (this.httpPassword != null && !this.httpPassword.isEmpty())) {
                throw new IllegalArgumentException("Both HTTP username and HTTP password must be provided for Basic Authentication, or neither.");
            }

            if (this.sslContext != null && !this.validateCerts) {
                logger.warn("A custom SSLContext is provided, but validateCerts is also set to false. The custom SSLContext will be used; validateCerts setting might be redundant or lead to unexpected behavior depending on the SSLContext's configuration.");
            }

            ZabbixApi api = new ZabbixApi(this);

            // Perform auto-login if credentials/token were provided
            // This requires the ZabbixApi instance to be created first.
            // Errors during this initial login will propagate from build().
            try {
                if (this.token != null && !this.token.isEmpty()) {
                    api.loginWithToken(this.token);
                } else if (this.user != null && !this.user.isEmpty() &&
                           this.password != null && !this.password.isEmpty()) {
                    api.login(this.user, this.password);
                }
            } catch (ZabbixApiException e) {
                // Wrap in a more specific exception or rethrow if build() is declared to throw ZabbixApiException
                // For now, rethrowing ZabbixApiException as per updated method signature.
                throw new ZabbixApiException("Auto-login failed during ZabbixApi construction: " + e.getMessage(), e);
            }
            // If no auth provided, API client is created unauthenticated. User must call login() or use token later.

            return api;
        }
    }
}
