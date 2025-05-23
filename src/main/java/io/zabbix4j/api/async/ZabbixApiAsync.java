package io.zabbix4j.api.async;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zabbix4j.api.ZabbixApiConstants;
import io.zabbix4j.api.dto.APIVersion;
import io.zabbix4j.api.exception.ApiNotSupportedException;
import io.zabbix4j.api.exception.ApiRequestException;
import io.zabbix4j.api.exception.CommunicationException;
import io.zabbix4j.api.exception.ProcessingException;
import io.zabbix4j.api.utils.ZabbixUtils;
import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleHttpRequests;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.Method;
import org.apache.hc.core5.reactor.IOReactorConfig;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Asynchronous client for interacting with the Zabbix JSON-RPC API.
 * This class provides methods for making API calls asynchronously, handling authentication,
 * and managing API version compatibility.
 * <p>
 * Use the {@link Builder} to construct instances of this class.
 * Remember to call {@link #start()} on the built client if it's managed by this class.
 * </p>
 * Example:
 * <pre>{@code
 * ZabbixApiAsync zabbixApi = new ZabbixApiAsync.Builder()
 * .url("http://zabbix.example.com")
 * .build();
 * zabbixApi.start(); // Important if client is managed
 *
 * zabbixApi.login("Admin", "zabbix")
 * .thenCompose(v -> zabbixApi.call("hostgroup.get", Map.of("output", "extend")))
 * .thenAccept(hostGroups -> System.out.println(hostGroups.toPrettyString()))
 * .exceptionally(ex -> {
 * ex.printStackTrace();
 * return null;
 * })
 * .join(); // Wait for completion in example
 *
 * zabbixApi.logout().join();
 * zabbixApi.close();
 * }</pre>
 *
 * @author ShortRoundDev
 */
public final class ZabbixApiAsync implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ZabbixApiAsync.class);
    private static final String JSON_RPC_VERSION = "2.0";
    private static final String USER_AGENT_PREFIX = "zabbix4j-async/";

    private final CloseableHttpAsyncClient asyncHttpClient;
    private final ObjectMapper objectMapper;
    private final String serverUrl;
    private final AtomicReference<String> sessionAuthToken = new AtomicReference<>();
    private final AtomicBoolean isTokenAuth = new AtomicBoolean(false);
    private final String basicAuthCredentials; // Base64 encoded "user:pass"

    private final AtomicReference<APIVersion> apiVersion = new AtomicReference<>(null);
    private final boolean managedAsyncHttpClient;
    private final String userAgent;
    private final boolean skipVersionCheckInitial; // To pass to getApiVersion first time

    private ZabbixApiAsync(Builder builder) {
        this.serverUrl = ZabbixUtils.checkUrl(builder.url);
        this.objectMapper = new ObjectMapper(); // Configure as needed
        this.basicAuthCredentials = builder.basicAuthUser != null && builder.basicAuthPassword != null ?
                Base64.getEncoder().encodeToString((builder.basicAuthUser + ":" + builder.basicAuthPassword).getBytes(StandardCharsets.UTF_8))
                : null;
        this.skipVersionCheckInitial = builder.skipVersionCheck;

        if (builder.asyncHttpClient != null) {
            this.asyncHttpClient = builder.asyncHttpClient;
            this.managedAsyncHttpClient = false;
        } else {
            RequestConfig.Builder configBuilder = RequestConfig.custom();
            if (builder.requestTimeoutSeconds > 0) {
                configBuilder.setResponseTimeout(Timeout.ofSeconds(builder.requestTimeoutSeconds));
            }
            if (builder.connectTimeoutSeconds > 0) {
                configBuilder.setConnectTimeout(Timeout.ofSeconds(builder.connectTimeoutSeconds));
            }
            // ConnectionRequestTimeout is also available if needed

            PoolingAsyncClientConnectionManagerBuilder cmBuilder = PoolingAsyncClientConnectionManagerBuilder.create();
            if (builder.sslContext != null) {
                cmBuilder.setSslContext(builder.sslContext);
            }
            // IOReactorConfig for thread count etc.
            IOReactorConfig.Builder ioReactorConfigBuilder = IOReactorConfig.custom();
            // Adjust default IO thread count if needed, e.g., ioReactorConfigBuilder.setIoThreadCount(Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
            // Default is usually Runtime.getRuntime().availableProcessors()

            this.asyncHttpClient = HttpAsyncClients.custom()
                    .setConnectionManager(cmBuilder.build())
                    .setDefaultRequestConfig(configBuilder.build())
                    .setIOReactorConfig(ioReactorConfigBuilder.build())
                    .build();
            this.managedAsyncHttpClient = true;
        }
        this.userAgent = USER_AGENT_PREFIX + ZabbixApiConstants.LIBRARY_VERSION;

        // Initial authentication if provided in builder
        // Note: These are synchronous operations on AtomicReferences, actual API calls are async.
        if (builder.authToken != null) {
            // For token auth, version check is crucial. We defer it to getApiVersion()
            // which is called before any actual API interaction needing the token.
            this.sessionAuthToken.set(builder.authToken);
            this.isTokenAuth.set(true);
        } else if (builder.user != null && builder.password != null) {
            // For user/pass, login() will handle API version and then log in.
            // No immediate action here, login() must be called explicitly.
        }
    }

    /**
     * Starts the underlying HTTP client if it's managed by this instance.
     * This method MUST be called after creating an instance if the client is managed,
     * before making any API calls.
     */
    public void start() {
        if (this.managedAsyncHttpClient) {
            this.asyncHttpClient.start();
            logger.info("Managed ZabbixApiAsync HttpClient started.");
        }
    }


    /**
     * Fetches the API version from the server asynchronously.
     * This method bypasses regular authentication.
     *
     * @return A CompletableFuture that resolves to the fetched {@link APIVersion}.
     */
    private CompletableFuture<APIVersion> fetchApiVersionFromServerAsync() {
        logger.debug("Fetching API version asynchronously from server: {}", serverUrl);

        // Temporarily clear auth for this call.
        // This is tricky with async. The callInternal method needs to handle this.
        // For apiinfo.version, auth should ideally not be sent at all.
        return callInternal("apiinfo.version", Collections.emptyMap(), true, true)
                .thenApply(responseNode -> {
                    String versionString = responseNode.asText();
                    if (versionString == null || versionString.isEmpty()) {
                        throw new ProcessingException("Received empty or null API version string from server.");
                    }
                    logger.info("Successfully fetched Zabbix API version: {}", versionString);
                    return new APIVersion(versionString);
                });
    }

    /**
     * Checks the fetched API version for compatibility.
     *
     * @param version      The fetched APIVersion.
     * @param skipChecks   If true, compatibility check failures are logged as warnings instead of throwing.
     * @return A CompletableFuture that completes normally if compatible or skipped,
     *         or exceptionally with {@link ApiNotSupportedException} if incompatible and not skipped.
     */
    private CompletableFuture<Void> checkApiVersionCompatibilityAsync(APIVersion version, boolean skipChecks) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        if (ZabbixApiConstants.MIN_SUPPORTED_ZABBIX_API_VERSION > 0 &&
                version.isLessThan(ZabbixApiConstants.MIN_SUPPORTED_ZABBIX_API_VERSION)) {
            String msg = String.format("Zabbix API version %s is older than minimum supported version %s.",
                    version.getRawVersion(), ZabbixApiConstants.MIN_SUPPORTED_ZABBIX_API_VERSION);
            if (skipChecks) {
                logger.warn("{}. Version check was skipped by configuration.", msg);
                future.complete(null);
            } else {
                future.completeExceptionally(new ApiNotSupportedException(msg, version.getRawVersion()));
                return future;
            }
        }

        if (ZabbixApiConstants.MAX_SUPPORTED_ZABBIX_API_VERSION > 0 &&
                version.isGreaterThan(ZabbixApiConstants.MAX_SUPPORTED_ZABBIX_API_VERSION)) {
            String msg = String.format("Zabbix API version %s is newer than maximum supported version %s.",
                    version.getRawVersion(), ZabbixApiConstants.MAX_SUPPORTED_ZABBIX_API_VERSION);
            if (skipChecks) {
                logger.warn("{}. Version check was skipped by configuration.", msg);
                future.complete(null);
            } else {
                future.completeExceptionally(new ApiNotSupportedException(msg, version.getRawVersion()));
                return future;
            }
        }

        if (!future.isDone()) { // If no issues were found and completed exceptionally
            future.complete(null);
        }
        return future;
    }


    /**
     * Gets the Zabbix API version. If not already determined, it fetches the version from the server asynchronously.
     * This version is checked for compatibility.
     *
     * @return A {@link CompletableFuture} resolving to the {@link APIVersion} of the Zabbix server.
     */
    public CompletableFuture<APIVersion> getApiVersion() {
        APIVersion current = apiVersion.get();
        if (current != null) {
            return CompletableFuture.completedFuture(current);
        }

        // Perform the fetch and check sequence
        return fetchApiVersionFromServerAsync().thenCompose(fetchedVersion ->
                checkApiVersionCompatibilityAsync(fetchedVersion, this.skipVersionCheckInitial)
                        .thenApply(v -> {
                            if (apiVersion.compareAndSet(null, fetchedVersion)) {
                                logger.info("Zabbix API version {} is supported and cached.", fetchedVersion.getRawVersion());
                            }
                            return apiVersion.get(); // Return the now cached (or concurrently set) version
                        })
        ).exceptionally(ex -> {
            // Ensure that if checkApiVersionCompatibilityAsync fails, the overall future also fails correctly.
            // And if fetchApiVersionFromServerAsync fails, it propagates.
            if (ex instanceof RuntimeException) throw (RuntimeException)ex;
            if (ex instanceof Error) throw (Error)ex;
            throw new CompletionException(ex); // Wrap checked exceptions if any, though our exceptions are runtime
        });
    }


    /**
     * Logs in to the Zabbix API using user credentials asynchronously.
     * This method authenticates with the Zabbix server and stores the session token.
     *
     * @param user     The Zabbix username.
     * @param password The Zabbix password.
     * @return A {@link CompletableFuture<Void>} that completes when login is successful.
     */
    public CompletableFuture<Void> login(String user, String password) {
        Objects.requireNonNull(user, "Username cannot be null for login.");
        Objects.requireNonNull(password, "Password cannot be null for login.");

        // Ensure API version is known before login to handle different param names
        return getApiVersion().thenCompose(version -> {
            Map<String, Object> params = new HashMap<>();
            if (version.isLessThan(5.4f)) {
                params.put("user", user);
            } else {
                params.put("username", user);
            }
            params.put("password", password);

            // Clear any existing token before attempting new login
            this.sessionAuthToken.set(null);
            this.isTokenAuth.set(false);

            return callInternal("user.login", params, true, false) // Bypass normal auth for login
                    .thenAccept(responseNode -> {
                        String token = responseNode.asText();
                        if (token == null || token.isEmpty()) {
                            throw new ProcessingException("Received empty or null session token from user.login.");
                        }
                        this.sessionAuthToken.set(token);
                        // isTokenAuth is already false
                        logger.info("Successfully logged in as user '{}'. Session type: User/Password.", user);
                    });
        });
    }

    /**
     * Authenticates using a pre-existing API token asynchronously.
     *
     * @param token The API token.
     * @return A {@link CompletableFuture<Void>} that completes when token is set.
     *         Completes exceptionally if token authentication is not supported by the API version.
     */
    public CompletableFuture<Void> loginWithToken(String token) {
        Objects.requireNonNull(token, "Token cannot be null for token authentication.");
        if (token.trim().isEmpty()) {
            throw new IllegalArgumentException("Token cannot be empty for token authentication.");
        }

        return getApiVersion().thenAccept(version -> {
            if (version.isLessThan(5.4f)) {
                throw new ApiNotSupportedException("Token authentication is not supported for Zabbix API versions older than 5.4.", version.getRawVersion());
            }
            this.sessionAuthToken.set(token);
            this.isTokenAuth.set(true);
            logger.info("Successfully authenticated using API token. Session type: Token.");
        });
    }

    /**
     * Logs out from the Zabbix API asynchronously.
     * If authenticated via user/password, it calls the `user.logout` method.
     * If authenticated via token, it only clears the local token (no API call).
     *
     * @return A {@link CompletableFuture<Void>} that completes when logout is done.
     */
    public CompletableFuture<Void> logout() {
        if (!isAuthenticated()) {
            logger.info("Not authenticated, logout not required.");
            return CompletableFuture.completedFuture(null);
        }

        if (!this.isTokenAuth.get()) { // Only call user.logout if it was a user/password session
            return callInternal("user.logout", Collections.emptyList(), false, false)
                    .thenAccept(responseNode -> {
                        logger.info("Successfully logged out from Zabbix API (user.logout called).");
                    })
                    .exceptionally(e -> {
                        logger.error("Failed to call user.logout: {}", e.getMessage(), e);
                        // Still clear local token even if API call fails
                        return null; // Swallow exception for logout, but log it
                    })
                    .thenRun(() -> this.sessionAuthToken.set(null));
        } else {
            logger.info("Logged out by clearing local API token (token auth was used).");
            this.sessionAuthToken.set(null);
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * Checks if the client is currently authenticated (i.e., has a session token).
     * This is a synchronous, non-blocking check.
     *
     * @return true if authenticated, false otherwise.
     */
    public boolean isAuthenticated() {
        String currentToken = this.sessionAuthToken.get();
        return currentToken != null && !currentToken.isEmpty();
    }

    /**
     * Verifies the current authentication (session or token) with the Zabbix server asynchronously.
     *
     * @return A {@link CompletableFuture<Boolean>} resolving to true if authentication is valid, false otherwise.
     */
    public CompletableFuture<Boolean> checkAuthentication() {
        if (!isAuthenticated()) {
            return CompletableFuture.completedFuture(false);
        }
        return call("user.checkAuthentication", Collections.emptyMap())
                .thenApply(resultNode -> resultNode != null && resultNode.has("sessionid"))
                .exceptionally(e -> {
                    if (e.getCause() instanceof ApiRequestException) {
                         logger.warn("Authentication check failed due to API error: {}", e.getCause().getMessage());
                    } else {
                        logger.warn("Authentication check failed due to other error: {}", e.getMessage());
                    }
                    return false; // API error or other issues mean auth is not valid
                });
    }


    /**
     * Makes an asynchronous call to the Zabbix API.
     *
     * @param method The Zabbix API method (e.g., "host.get", "user.login").
     * @param params The parameters for the API method. Can be a Map or List.
     * @return A {@link CompletableFuture} that will be completed with the "result" field
     *         from the JSON-RPC response as a {@link JsonNode}, or completed exceptionally.
     * @throws NullPointerException if method is null.
     */
    public CompletableFuture<JsonNode> call(String method, Object params) {
        return callInternal(method, params, false, false);
    }

    /**
     * Internal method to make an API call, with options to bypass auth checks and version fetching.
     *
     * @param method                The Zabbix API method.
     * @param params                Parameters for the method.
     * @param isAuthBypassMethod    True if this method (like user.login) bypasses normal auth token requirements.
     * @param isVersionFetchCall    True if this call is specifically for fetching the API version itself.
     * @return A CompletableFuture resolving to the result JsonNode.
     */
    @SuppressWarnings("unchecked")
    private CompletableFuture<JsonNode> callInternal(String method, Object params, boolean isAuthBypassMethod, boolean isVersionFetchCall) {
        Objects.requireNonNull(method, "API method cannot be null.");
        if (params == null) {
            params = Collections.emptyMap(); // Default to empty map if params are null
        }

        if (!isAuthBypassMethod && !ZabbixUtils.UNAUTH_METHODS.contains(method) && !isAuthenticated()) {
            return CompletableFuture.failedFuture(new ProcessingException("Not authenticated. Please login before calling method: " + method));
        }

        CompletableFuture<JsonNode> resultFuture = new CompletableFuture<>();

        // Get API version first, but only if it's not the version fetch call itself
        // and not an auth bypass method that doesn't depend on version-specific auth logic (like user.login params)
        CompletableFuture<APIVersion> versionFuture = (isVersionFetchCall || ZabbixUtils.UNAUTH_METHODS.contains(method)) ?
                CompletableFuture.completedFuture(this.apiVersion.get()) : // Use cached if available, or null if very first call
                getApiVersion();


        final Object finalParams = params; // For lambda
        versionFuture.whenComplete((currentApiVersion, versionEx) -> {
            if (versionEx != null) {
                resultFuture.completeExceptionally(versionEx);
                return;
            }

            String requestId = UUID.randomUUID().toString();
            Map<String, Object> requestPayloadMap = new HashMap<>();
            requestPayloadMap.put("jsonrpc", JSON_RPC_VERSION);
            requestPayloadMap.put("method", method);
            requestPayloadMap.put("params", finalParams);
            requestPayloadMap.put("id", requestId);

            String currentAuthToken = this.sessionAuthToken.get();
            boolean useAuthInPayload = false;
            String authHeaderValue = null;

            if (currentAuthToken != null && !isAuthBypassMethod && !ZabbixUtils.UNAUTH_METHODS.contains(method)) {
                // currentApiVersion might be null if it's an unauth method and version hasn't been fetched yet.
                // This is okay as unauth methods don't need version-specific auth logic.
                // For auth'd methods, getApiVersion() ensures currentApiVersion is non-null.
                boolean preferBearer = currentApiVersion != null && (currentApiVersion.isGreaterThan(6.4f) || currentApiVersion.isEqualTo(6.4f));

                if (this.basicAuthCredentials != null && currentApiVersion != null && (currentApiVersion.isGreaterThan(7.0f) || currentApiVersion.isEqualTo(7.0f))) {
                    useAuthInPayload = true;
                } else if (preferBearer) {
                    authHeaderValue = "Bearer " + currentAuthToken;
                } else {
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
                resultFuture.completeExceptionally(new ProcessingException("Failed to serialize request payload: " + e.getMessage(), e));
                return;
            }

            if (logger.isDebugEnabled()) {
                Map<String, Object> paramsToLog = Collections.emptyMap();
                if (finalParams instanceof Map) {
                    paramsToLog = ZabbixUtils.hidePrivate((Map<String,Object>)finalParams);
                }
                logger.debug("Executing Zabbix API request (Async). Method: {}, Params: {}, ID: {}", method, paramsToLog, requestId);
            }

            SimpleHttpRequest httpRequest = SimpleHttpRequests.create(Method.POST, this.serverUrl);
            httpRequest.setHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON_RPC.getMimeType());
            httpRequest.setHeader(HttpHeaders.ACCEPT, ContentType.APPLICATION_JSON.getMimeType());
            httpRequest.setHeader(HttpHeaders.USER_AGENT, this.userAgent);

            if (this.basicAuthCredentials != null) {
                httpRequest.setHeader(HttpHeaders.AUTHORIZATION, "Basic " + this.basicAuthCredentials);
            } else if (authHeaderValue != null) {
                httpRequest.setHeader(HttpHeaders.AUTHORIZATION, authHeaderValue);
            }
            httpRequest.setBody(requestJson, ContentType.APPLICATION_JSON_RPC);


            asyncHttpClient.execute(httpRequest, new FutureCallback<SimpleHttpResponse>() {
                @Override
                public void completed(SimpleHttpResponse response) {
                    try {
                        int statusCode = response.getCode();
                        String responseBody = response.getBodyText(StandardCharsets.UTF_8);

                        if (logger.isDebugEnabled()) {
                            String responseToLog = responseBody;
                             if (responseBody != null && ZabbixUtils.FILES_METHODS.contains(method)) {
                                responseToLog = "(Response content for files method hidden)";
                            } else if (responseBody != null && responseBody.length() > 1000) {
                                 responseToLog = responseBody.substring(0, 1000) + "... (truncated)";
                            }
                            logger.debug("Received Zabbix API response (Async). Status: {}, ID: {}, Response: {}", statusCode, requestId, responseToLog);
                        }

                        if (statusCode < 200 || statusCode >= 300) {
                            resultFuture.completeExceptionally(new CommunicationException(
                                    String.format("HTTP request failed with status %d: %s. Response: %s",
                                            statusCode, response.getReasonPhrase(), responseBody)));
                            return;
                        }

                        if (responseBody == null || responseBody.isEmpty()) {
                            resultFuture.completeExceptionally(new ProcessingException("Received empty response from Zabbix API."));
                            return;
                        }

                        JsonNode responseNode = objectMapper.readTree(responseBody);

                        if (responseNode.has("error")) {
                            JsonNode errorNode = responseNode.get("error");
                            int code = errorNode.path("code").asInt();
                            String message = errorNode.path("message").asText();
                            String data = errorNode.path("data").asText();
                            Map<String, Object> maskedParams = finalParams instanceof Map ? ZabbixUtils.hidePrivate((Map<String,Object>)finalParams) : Collections.emptyMap();
                            String requestContext = String.format("Method: %s, Params: %s", method, maskedParams);
                            resultFuture.completeExceptionally(new ApiRequestException(code, message, data, requestContext));
                        } else if (!responseNode.has("result")) {
                            resultFuture.completeExceptionally(new ProcessingException("Zabbix API response is missing 'result' field. Response: " + responseBody));
                        } else {
                            resultFuture.complete(responseNode.get("result"));
                        }
                    } catch (Exception e) { // Catch JsonProcessingException from readTree or any other
                        resultFuture.completeExceptionally(new ProcessingException("Failed to process Zabbix API response: " + e.getMessage(), e));
                    }
                }

                @Override
                public void failed(Exception ex) {
                    resultFuture.completeExceptionally(new CommunicationException("Failed to execute API request " + method + ": " + ex.getMessage(), ex));
                }

                @Override
                public void cancelled() {
                    resultFuture.completeExceptionally(new CommunicationException("API request " + method + " was cancelled."));
                }
            });
        });
        return resultFuture;
    }

    @Override
    public void close() throws IOException {
        if (this.asyncHttpClient != null && this.managedAsyncHttpClient) {
            this.asyncHttpClient.close(org.apache.hc.core5.io.CloseMode.GRACEFUL); // Or IMMEDIATE
            logger.info("ZabbixApiAsync managed HttpClient closed.");
        }
    }

    /**
     * Builder for {@link ZabbixApiAsync}.
     */
    public static class Builder {
        private String url;
        private String user;
        private String password;
        private String authToken;
        private String basicAuthUser;
        private String basicAuthPassword;
        private SSLContext sslContext;
        private int requestTimeoutSeconds = -1; // Default: use HttpClient's default
        private int connectTimeoutSeconds = -1; // Default: use HttpClient's default
        private CloseableHttpAsyncClient asyncHttpClient;
        private boolean skipVersionCheck = false;

        public Builder url(String url) {
            this.url = url;
            return this;
        }

        public Builder user(String user, String password) {
            this.user = user;
            this.password = password;
            return this;
        }

        public Builder token(String authToken) {
            this.authToken = authToken;
            return this;
        }

        public Builder basicAuth(String username, String password) {
            this.basicAuthUser = username;
            this.basicAuthPassword = password;
            return this;
        }

        public Builder sslContext(SSLContext sslContext) {
            this.sslContext = sslContext;
            return this;
        }

        public Builder requestTimeout(int seconds) {
            this.requestTimeoutSeconds = seconds;
            return this;
        }
        public Builder connectTimeout(int seconds) {
            this.connectTimeoutSeconds = seconds;
            return this;
        }

        public Builder customHttpAsyncClient(CloseableHttpAsyncClient asyncHttpClient) {
            this.asyncHttpClient = asyncHttpClient;
            return this;
        }

        public Builder skipVersionCheck(boolean skipVersionCheck) {
            this.skipVersionCheck = skipVersionCheck;
            return this;
        }

        public ZabbixApiAsync build() {
            if (url == null || url.trim().isEmpty()) {
                throw new IllegalArgumentException("Zabbix API URL must be provided.");
            }
            return new ZabbixApiAsync(this);
        }
    }
}
