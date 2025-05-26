package io.zabbix4j.api;

import io.zabbix4j.api.exception.ZabbixApiException;
import io.zabbix4j.api.exception.ZabbixApiRequestException;
import io.zabbix4j.api.exception.ZabbixProcessingException;
import io.zabbix4j.api.service.async.AsyncHistoryService; // Added
import io.zabbix4j.api.service.async.AsyncHostService;   // Added
import io.zabbix4j.api.service.async.AsyncItemService;   // Added
import io.zabbix4j.api.service.async.AsyncUserService;
import io.zabbix4j.api.types.ApiVersion;
import io.zabbix4j.api.utils.ZabbixApiUtils;
import org.json.JSONArray; // Added
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.HashMap;
import java.util.List; // Added
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException; // Added for sendRequest
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Main class for interacting asynchronously with the Zabbix JSON-RPC API using Java's HttpClient.
 * Provides methods for login, logout, version fetching, and accessing various Zabbix API services,
 * all returning {@link CompletableFuture}.
 * Instances are configured and created using the {@link Builder}.
 * This class aims to be thread-safe.
 *
 * @author CSJ
 */
public final class AsyncZabbixApi {

    private static final Logger logger = LoggerFactory.getLogger(AsyncZabbixApi.class);
    private static final AtomicInteger nextRequestId = new AtomicInteger(1);

    private final String apiUrl;
    private final String httpUser;
    private final String httpPassword;
    private final boolean skipVersionCheck;
    private final int timeoutMilliseconds;
    private final HttpClient httpClient;

    private final AtomicReference<String> authToken = new AtomicReference<>(null);
    private volatile boolean usingTokenAuth = false;
    private final AtomicReference<ApiVersion> cachedApiVersion = new AtomicReference<>(null);

    private final AsyncUserServiceImpl asyncUserServiceImpl;
    private final AsyncHostServiceImpl asyncHostServiceImpl;
    private final AsyncItemServiceImpl asyncItemServiceImpl;
    private final AsyncHistoryServiceImpl asyncHistoryServiceImpl;

    private AsyncZabbixApi(Builder builder) {
        this.apiUrl = builder.apiUrl;
        this.httpUser = builder.httpUser;
        this.httpPassword = builder.httpPassword;
        this.skipVersionCheck = builder.skipVersionCheck;
        this.timeoutMilliseconds = builder.timeoutMilliseconds;

        HttpClient.Builder httpBuilder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(this.timeoutMilliseconds));

        if (this.httpUser != null && this.httpPassword != null) {
            httpBuilder.authenticator(new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(httpUser, httpPassword.toCharArray());
                }
            });
        }

        if (builder.sslContextSupplier != null) {
            httpBuilder.sslContext(builder.sslContextSupplier.get());
        } else if (!builder.validateCerts) {
            try {
                httpBuilder.sslContext(createTrustAllSslContext());
                logger.warn("Certificate validation is disabled for AsyncZabbixApi. This is insecure and should only be used for testing.");
            } catch (NoSuchAlgorithmException | KeyManagementException e) {
                throw new RuntimeException("Failed to create a trust-all SSLContext for AsyncZabbixApi", e);
            }
        }

        this.httpClient = httpBuilder.build();
        this.asyncUserServiceImpl = new AsyncUserServiceImpl(this);
        this.asyncHostServiceImpl = new AsyncHostServiceImpl(this);
        this.asyncItemServiceImpl = new AsyncItemServiceImpl(this);
        this.asyncHistoryServiceImpl = new AsyncHistoryServiceImpl(this);

        logger.info("AsyncZabbixApi initialized for URL: {}. Timeout: {}ms. Skip version check: {}.",
                    this.apiUrl, this.timeoutMilliseconds, this.skipVersionCheck);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String apiUrl;
        private String httpUser;
        private String httpPassword;
        private boolean skipVersionCheck = false;
        private boolean validateCerts = true;
        private Supplier<SSLContext> sslContextSupplier;
        private int timeoutMilliseconds = 30000;

        private Builder() {}

        public Builder url(String url) {
            this.apiUrl = ZabbixApiUtils.checkUrl(url);
            return this;
        }

        public Builder basicAuth(String user, String password) {
            if (user == null || password == null) {
                throw new IllegalArgumentException("HTTP user and password must not be null for Basic Auth.");
            }
            this.httpUser = user;
            this.httpPassword = password;
            return this;
        }

        public Builder skipVersionCheck(boolean skip) {
            this.skipVersionCheck = skip;
            return this;
        }

        public Builder validateCertificates(boolean validate) {
            this.validateCerts = validate;
            return this;
        }

        public Builder sslContextSupplier(Supplier<SSLContext> supplier) {
            this.sslContextSupplier = supplier;
            return this;
        }

        public Builder timeout(int timeout, TimeUnit unit) {
            if (timeout <= 0) {
                throw new IllegalArgumentException("Timeout must be positive.");
            }
            this.timeoutMilliseconds = (int) unit.toMillis(timeout);
            return this;
        }

        public AsyncZabbixApi build() {
            if (this.apiUrl == null || this.apiUrl.trim().isEmpty()) {
                throw new IllegalArgumentException("Zabbix API URL must be set.");
            }
            return new AsyncZabbixApi(this);
        }
    }

    private static SSLContext createTrustAllSslContext() throws NoSuchAlgorithmException, KeyManagementException {
        TrustManager[] trustAllCerts = new TrustManager[]{
            new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                public void checkServerTrusted(X509Certificate[] certs, String authType) {}
            }
        };
        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(null, trustAllCerts, new java.security.SecureRandom());
        return sc;
    }

    public CompletableFuture<Void> loginWithUserPassword(String user, String password) {
        if (user == null || user.isEmpty() || password == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Username cannot be null or empty, password cannot be null."));
        }
        logger.info("Attempting asynchronous login with username: {}", user);
        Map<String, Object> params = Map.of("user", user, "password", password);
        return performLogin(params, false);
    }

    public CompletableFuture<Void> loginWithToken(String token) {
        if (token == null || token.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("API token cannot be null or empty."));
        }
        logger.info("Attempting to set and use API token.");
        this.authToken.set(token);
        this.usingTokenAuth = true;
        logger.info("API token set. Subsequent requests will use this token.");
        if (!skipVersionCheck) {
            return getApiVersion().thenApply(apiVersion -> null);
        } else {
            return CompletableFuture.completedFuture(null);
        }
    }

    public CompletableFuture<Void> logout() {
        logger.info("Attempting asynchronous logout.");
        if (this.authToken.get() == null) {
            logger.info("Not logged in, logout call skipped.");
            return CompletableFuture.completedFuture(null);
        }
        return sendRequest("user.logout", new HashMap<>(), true)
            .thenAccept(response -> logger.info("Successfully logged out via async call."))
            .whenComplete((v, throwable) -> {
                if (throwable != null) {
                    logger.error("Async logout failed: {}", throwable.getMessage(), throwable);
                }
                this.authToken.set(null);
                this.usingTokenAuth = false;
            });
    }

    public CompletableFuture<Boolean> checkAuth() {
        logger.debug("Checking authentication status asynchronously.");
        if (this.authToken.get() == null) {
            logger.info("No auth token present, async authentication check returns false.");
            return CompletableFuture.completedFuture(false);
        }
        Map<String, Object> params = new HashMap<>();
        if (!this.usingTokenAuth) {
             params.put("sessionid", this.authToken.get());
        }
        return sendRequest("user.checkAuthentication", params, true)
            .thenApply(response -> response != null)
            .exceptionally(e -> {
                logger.warn("Async authentication check failed or session is invalid: {}", e.getMessage());
                return false;
            });
    }

    public CompletableFuture<ApiVersion> getApiVersion() {
        ApiVersion currentVersion = this.cachedApiVersion.get();
        if (currentVersion != null) {
            logger.debug("Returning cached API version asynchronously: {}", currentVersion.getRaw());
            return CompletableFuture.completedFuture(currentVersion);
        }
         if (skipVersionCheck && authToken.get() == null) {
             logger.warn("API version check is skipped and not logged in (async). Cannot determine API version.");
             return CompletableFuture.failedFuture(new ZabbixApiException("API version check is skipped and not logged in. Cannot determine API version without an initial call."));
        }
        logger.info("Fetching Zabbix API version from server asynchronously.");
        return sendRequest("apiinfo.version", new HashMap<>(), false)
            .thenApply(response -> {
                if (response instanceof String) {
                    String versionStr = (String) response;
                    ApiVersion newVersion = new ApiVersion(versionStr);
                    this.cachedApiVersion.set(newVersion);
                    logger.info("Fetched and cached API version (async): {}", newVersion.getRaw());
                    return newVersion;
                } else {
                    String errorMsg = "Unexpected response type for apiinfo.version (async): " + (response != null ? response.getClass().getName() : "null");
                    logger.error(errorMsg);
                    throw new CompletionException(new ZabbixApiException("Failed to retrieve API version: " + errorMsg));
                }
            });
    }

    private CompletableFuture<Void> performLogin(Map<String, Object> params, boolean isTokenLogin) {
        logger.debug("Performing login asynchronously. Token auth: {}", isTokenLogin);
        return sendRequest("user.login", params, false)
            .thenCompose(response -> {
                if (response instanceof String) {
                    String receivedAuthToken = (String) response;
                    if (receivedAuthToken.isEmpty()) {
                        return CompletableFuture.failedFuture(new ZabbixApiException("Login failed (async): Received empty session ID/token."));
                    }
                    this.authToken.set(receivedAuthToken);
                    this.usingTokenAuth = isTokenLogin;
                    logger.info("Login successful (async). Session ID obtained.");
                    if (!skipVersionCheck) {
                        return getApiVersion().thenApply(apiVersion -> null);
                    } else {
                        return CompletableFuture.completedFuture(null);
                    }
                } else {
                    String errorMsg = "Login failed (async): Unexpected response format. Expected auth string, got: " + (response != null ? response.getClass().getName() : "null");
                    logger.error(errorMsg);
                    return CompletableFuture.failedFuture(new ZabbixApiException(errorMsg));
                }
            });
    }

    private CompletableFuture<Object> sendRequest(String method, Object params, boolean needsAuth) {
        logger.debug("Preparing async request. Method: {}, Params: {}, NeedsAuth: {}", method, params, needsAuth);
        if (needsAuth && this.authToken.get() == null) {
            return CompletableFuture.failedFuture(new ZabbixApiException("Authentication required for method '" + method + "', but not logged in."));
        }
        JSONObject requestJson = new JSONObject();
        try {
            requestJson.put("jsonrpc", "2.0");
            requestJson.put("method", method);
            requestJson.put("params", params instanceof Map ? new JSONObject((Map<?,?>)params) : params);
            requestJson.put("id", nextRequestId.getAndIncrement());
            if (needsAuth) {
                requestJson.put("auth", this.authToken.get());
            }
        } catch (JSONException e) {
             return CompletableFuture.failedFuture(new ZabbixProcessingException("Error constructing JSON request: " + e.getMessage(), e));
        }
        String requestBodyString = requestJson.toString();
        if (logger.isTraceEnabled()) {
            logger.trace("Async Request JSON: {}", ZabbixApiUtils.hidePrivate(requestJson.toMap(), ZabbixApiUtils.DEFAULT_PRIVATE_FIELDS));
        }
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(this.apiUrl))
                .timeout(Duration.ofMillis(this.timeoutMilliseconds))
                .header("Content-Type", "application/json-rpc")
                .POST(HttpRequest.BodyPublishers.ofString(requestBodyString));
        return this.httpClient.sendAsync(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
            .thenApply(httpResponse -> {
                logger.trace("Async Response status code: {}", httpResponse.statusCode());
                if (logger.isTraceEnabled()) {
                    try {
                        JSONObject respJsonForLog = new JSONObject(httpResponse.body());
                        logger.trace("Async Response JSON: {}", ZabbixApiUtils.hidePrivate(respJsonForLog.toMap(), ZabbixApiUtils.DEFAULT_PRIVATE_FIELDS));
                    } catch (JSONException e) {
                        logger.trace("Async Response Body (not valid JSON or empty): {}", httpResponse.body());
                    }
                }
                if (httpResponse.statusCode() < 200 || httpResponse.statusCode() >= 300) {
                    throw new CompletionException(new ZabbixApiException("HTTP error " + httpResponse.statusCode() + " calling Zabbix API (async): " + httpResponse.body()));
                }
                String responseBody = httpResponse.body();
                if (responseBody == null || responseBody.trim().isEmpty()) {
                    throw new CompletionException(new ZabbixProcessingException("Received empty response body from Zabbix API (async)."));
                }
                try {
                    JSONObject responseJson = new JSONObject(responseBody);
                    if (responseJson.has("error")) {
                        JSONObject errorObj = responseJson.getJSONObject("error");
                        throw new CompletionException(new ZabbixApiRequestException(
                                String.format("Zabbix API Error (code: %d, message: %s, data: %s)",
                                              errorObj.optInt("code"), errorObj.optString("message"), errorObj.optString("data")),
                                errorObj.optInt("code"), errorObj.optString("message"), errorObj.optString("data"),
                                ZabbixApiUtils.hidePrivate(requestJson.toMap(), ZabbixApiUtils.DEFAULT_PRIVATE_FIELDS).toString()
                        ));
                    }
                    if (!responseJson.has("result")) {
                        throw new CompletionException(new ZabbixProcessingException("Invalid Zabbix API response (async): Missing 'result' field. Response: " + responseBody));
                    }
                    return responseJson.get("result");
                } catch (JSONException e) {
                     throw new CompletionException(new ZabbixProcessingException("JSON parsing error in async response: " + e.getMessage(), e));
                }
            })
            .exceptionally(ex -> {
                logger.error("Error sending async request to Zabbix API: {}", ex.getMessage(), ex);
                if (ex instanceof CompletionException && ex.getCause() instanceof ZabbixApiException) {
                    throw (CompletionException) ex;
                }
                throw new CompletionException(new ZabbixProcessingException("Failed to send async request to Zabbix API: " + ex.getMessage(), ex));
            });
    }

    public AsyncUserService userService() {
        return this.asyncUserServiceImpl;
    }

    /**
     * Provides access to asynchronous Host related API calls.
     * @return An instance of {@link AsyncHostService}.
     * @author CSJ
     */
    public AsyncHostService hostService() {
        return this.asyncHostServiceImpl;
    }

    /**
     * Provides access to asynchronous Item related API calls.
     * @return An instance of {@link AsyncItemService}.
     * @author CSJ
     */
    public AsyncItemService itemService() {
        return this.asyncItemServiceImpl;
    }

    /**
     * Provides access to asynchronous History related API calls.
     * @return An instance of {@link AsyncHistoryService}.
     * @author CSJ
     */
    public AsyncHistoryService historyService() {
        return this.asyncHistoryServiceImpl;
    }

    private static class AsyncUserServiceImpl implements AsyncUserService {
        private final AsyncZabbixApi asyncZabbixApi;
        public AsyncUserServiceImpl(AsyncZabbixApi asyncZabbixApi) { this.asyncZabbixApi = asyncZabbixApi; }

        @Override
        public CompletableFuture<String> login(String user, String password) {
            return asyncZabbixApi.loginWithUserPassword(user, password)
                .thenApply(v -> asyncZabbixApi.authToken.get());
        }
        @Override
        public CompletableFuture<Void> logout() {
            return asyncZabbixApi.logout();
        }
        @Override
        public CompletableFuture<Boolean> checkAuthentication(String currentAuthToken, boolean isTokenValue) {
            // For simplicity, this implementation uses the parent's checkAuth which relies on internal state.
            // If a specific token needs to be checked that's different from internal, this might need adjustment.
            if (currentAuthToken != null && !currentAuthToken.equals(asyncZabbixApi.authToken.get())) {
                 logger.warn("AsyncUserService.checkAuthentication called with a token different from internal; relying on internal state if set.");
            }
            return asyncZabbixApi.checkAuth();
        }
    }

    private static class AsyncHostServiceImpl implements AsyncHostService {
        private final AsyncZabbixApi asyncZabbixApi;
        public AsyncHostServiceImpl(AsyncZabbixApi asyncZabbixApi) { this.asyncZabbixApi = asyncZabbixApi; }

        @Override
        public CompletableFuture<JSONArray> get(Map<String, Object> params) {
            logger.debug("AsyncHostService.get called with params: {}", params);
            return asyncZabbixApi.sendRequest("host.get", params, true)
                .thenApply(result -> (JSONArray) result);
        }
        @Override
        public CompletableFuture<JSONObject> create(Map<String, Object> params) {
            logger.debug("AsyncHostService.create called with params: {}", params);
            return asyncZabbixApi.sendRequest("host.create", params, true)
                .thenApply(result -> (JSONObject) result);
        }
        @Override
        public CompletableFuture<JSONObject> update(Map<String, Object> params) {
            logger.debug("AsyncHostService.update called with params: {}", params);
            return asyncZabbixApi.sendRequest("host.update", params, true)
                .thenApply(result -> (JSONObject) result);
        }
        @Override
        public CompletableFuture<JSONObject> delete(List<String> hostIds) {
            logger.debug("AsyncHostService.delete called for host IDs: {}", hostIds);
            return asyncZabbixApi.sendRequest("host.delete", hostIds, true)
                .thenApply(result -> (JSONObject) result);
        }
    }

    private static class AsyncItemServiceImpl implements AsyncItemService {
        private final AsyncZabbixApi asyncZabbixApi;
        public AsyncItemServiceImpl(AsyncZabbixApi asyncZabbixApi) { this.asyncZabbixApi = asyncZabbixApi; }

        @Override
        public CompletableFuture<JSONArray> get(Map<String, Object> params) {
            logger.debug("AsyncItemService.get called with params: {}", params);
            return asyncZabbixApi.sendRequest("item.get", params, true)
                .thenApply(result -> (JSONArray) result);
        }
        @Override
        public CompletableFuture<JSONObject> create(Map<String, Object> params) {
            logger.debug("AsyncItemService.create called with params: {}", params);
            return asyncZabbixApi.sendRequest("item.create", params, true)
                .thenApply(result -> (JSONObject) result);
        }
        @Override
        public CompletableFuture<JSONObject> update(Map<String, Object> params) {
            logger.debug("AsyncItemService.update called with params: {}", params);
            return asyncZabbixApi.sendRequest("item.update", params, true)
                .thenApply(result -> (JSONObject) result);
        }
        @Override
        public CompletableFuture<JSONObject> delete(List<String> itemIds) { // Changed from JSONArray to JSONObject
            logger.debug("AsyncItemService.delete called for item IDs: {}", itemIds);
            return asyncZabbixApi.sendRequest("item.delete", itemIds, true)
                .thenApply(result -> (JSONObject) result);
        }
    }

    private static class AsyncHistoryServiceImpl implements AsyncHistoryService {
        private final AsyncZabbixApi asyncZabbixApi;
        public AsyncHistoryServiceImpl(AsyncZabbixApi asyncZabbixApi) { this.asyncZabbixApi = asyncZabbixApi; }

        @Override
        public CompletableFuture<JSONArray> get(Map<String, Object> params) {
            logger.debug("AsyncHistoryService.get called with params: {}", params);
            return asyncZabbixApi.sendRequest("history.get", params, true)
                .thenApply(result -> (JSONArray) result);
        }
    }
}
