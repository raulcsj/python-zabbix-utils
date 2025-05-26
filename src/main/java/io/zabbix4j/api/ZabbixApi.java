package io.zabbix4j.api;

import io.zabbix4j.api.exception.ZabbixApiException;
import io.zabbix4j.api.exception.ZabbixApiRequestException; // Added for sendRequest
import io.zabbix4j.api.exception.ZabbixProcessingException; // Added for sendRequest
import io.zabbix4j.api.service.HistoryService;
import io.zabbix4j.api.service.HostService;
import io.zabbix4j.api.service.ItemService;
import io.zabbix4j.api.service.UserLoginResponse;
import io.zabbix4j.api.service.UserService;
import io.zabbix4j.api.types.ApiVersion;
import io.zabbix4j.api.utils.ZabbixApiUtils;
import org.json.JSONArray; // Added
import org.json.JSONObject;
import org.json.JSONException; // Added for sendRequest
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
import java.util.HashMap; // Added
import java.util.List;   // Added
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Main class for interacting with the Zabbix JSON-RPC API.
 * Provides methods for login, logout, version fetching, and accessing various Zabbix API services.
 * Instances are configured and created using the {@link Builder}.
 * This class aims to be thread-safe.
 *
 * @author CSJ
 */
public final class ZabbixApi {

    private static final Logger logger = LoggerFactory.getLogger(ZabbixApi.class);
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

    private final UserServiceImpl userServiceImpl;
    private final HostServiceImpl hostServiceImpl;
    private final ItemServiceImpl itemServiceImpl;
    private final HistoryServiceImpl historyServiceImpl;


    private ZabbixApi(Builder builder) {
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
                logger.warn("Certificate validation is disabled. This is insecure and should only be used for testing.");
            } catch (NoSuchAlgorithmException | KeyManagementException e) {
                throw new RuntimeException("Failed to create a trust-all SSLContext", e);
            }
        }

        this.httpClient = httpBuilder.build();
        this.userServiceImpl = new UserServiceImpl(this);
        this.hostServiceImpl = new HostServiceImpl(this);
        this.itemServiceImpl = new ItemServiceImpl(this);
        this.historyServiceImpl = new HistoryServiceImpl(this);


        logger.info("ZabbixApi initialized for URL: {}. Timeout: {}ms. Skip version check: {}.",
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

        public ZabbixApi build() {
            if (this.apiUrl == null || this.apiUrl.trim().isEmpty()) {
                throw new IllegalArgumentException("Zabbix API URL must be set.");
            }
            return new ZabbixApi(this);
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

    public void loginWithUserPassword(String user, String password) throws ZabbixApiException {
        if (user == null || user.isEmpty() || password == null) {
            throw new IllegalArgumentException("Username cannot be null or empty, password cannot be null.");
        }
        logger.info("Attempting login with username: {}", user);
        Map<String, Object> params = Map.of("user", user, "password", password);
        performLogin(params, false);
    }

    public void loginWithToken(String token) throws ZabbixApiException {
        if (token == null || token.isEmpty()) {
            throw new IllegalArgumentException("API token cannot be null or empty.");
        }
        logger.info("Attempting login with API token.");
        this.authToken.set(token);
        this.usingTokenAuth = true;
        logger.info("API token set. Subsequent requests will use this token.");
        if (!skipVersionCheck) {
            getApiVersion();
        }
    }

    public void logout() throws ZabbixApiException {
        logger.info("Attempting logout.");
        if (this.authToken.get() == null) {
            logger.info("Not logged in, logout call skipped.");
            return;
        }
        try {
            sendRequest("user.logout", new HashMap<>(), true);
            logger.info("Successfully logged out.");
        } catch (ZabbixApiException e) {
            logger.error("Logout failed: {}", e.getMessage(), e);
            throw e;
        } finally {
            this.authToken.set(null);
            this.usingTokenAuth = false;
        }
    }

    public boolean checkAuth() throws ZabbixApiException {
        logger.debug("Checking authentication status.");
        if (this.authToken.get() == null) {
            logger.info("No auth token present, authentication check returns false.");
            return false;
        }
        try {
            Map<String, Object> params = new HashMap<>();
            if (!this.usingTokenAuth) {
                 params.put("sessionid", this.authToken.get());
            }
            Object response = sendRequest("user.checkAuthentication", params, true);
            return response != null;
        } catch (ZabbixApiException e) {
            logger.warn("Authentication check failed or session is invalid: {}", e.getMessage());
            return false;
        }
    }

    public ApiVersion getApiVersion() throws ZabbixApiException {
        ApiVersion version = this.cachedApiVersion.get();
        if (version != null) {
            logger.debug("Returning cached API version: {}", version.getRaw());
            return version;
        }
        if (skipVersionCheck && authToken.get() == null) {
             logger.warn("API version check is skipped and not logged in. Cannot determine API version.");
             throw new ZabbixApiException("API version check is skipped and not logged in. Cannot determine API version without an initial call.");
        }
        logger.info("Fetching Zabbix API version from server.");
        Object response = sendRequest("apiinfo.version", new HashMap<>(), false);
        if (response instanceof String) {
            String versionStr = (String) response;
            ApiVersion newVersion = new ApiVersion(versionStr);
            this.cachedApiVersion.set(newVersion);
            logger.info("Fetched and cached API version: {}", newVersion.getRaw());
            return newVersion;
        } else {
            logger.error("Unexpected response type for apiinfo.version: {}", response != null ? response.getClass().getName() : "null");
            throw new ZabbixApiException("Failed to retrieve API version: Unexpected response format.");
        }
    }

    private void performLogin(Map<String, Object> params, boolean isTokenAuth) throws ZabbixApiException {
        logger.debug("Performing login. Token auth: {}", isTokenAuth);
        Object response = sendRequest("user.login", params, false);
        if (response instanceof String) {
            String receivedAuthToken = (String) response;
            if (receivedAuthToken.isEmpty()) {
                throw new ZabbixApiException("Login failed: Received empty session ID/token.");
            }
            this.authToken.set(receivedAuthToken);
            this.usingTokenAuth = isTokenAuth;
            logger.info("Login successful. Session ID obtained.");
            if (!skipVersionCheck) {
                try {
                    getApiVersion();
                } catch (ZabbixApiException e) {
                    logger.warn("Successfully logged in, but failed to retrieve API version post-login: {}", e.getMessage());
                }
            }
        } else {
             logger.error("Login failed: Unexpected response format. Expected auth string, got: {}", response != null ? response.getClass().getName() : "null");
            throw new ZabbixApiException("Login failed: Unexpected response format from server.");
        }
    }

    private Object sendRequest(String method, Object params, boolean needsAuth) throws ZabbixApiException {
        logger.debug("Sending request. Method: {}, Params: {}, NeedsAuth: {}", method, params, needsAuth);
        if (needsAuth && this.authToken.get() == null) {
            throw new ZabbixApiException("Authentication required for method '" + method + "', but not logged in.");
        }
        JSONObject requestJson = new JSONObject();
        requestJson.put("jsonrpc", "2.0");
        requestJson.put("method", method);
        requestJson.put("params", params instanceof Map ? new JSONObject((Map<?,?>)params) : params);
        requestJson.put("id", nextRequestId.getAndIncrement());
        if (needsAuth) {
            requestJson.put("auth", this.authToken.get());
        }
        String requestBodyString = requestJson.toString();
        logger.trace("Request JSON: {}", ZabbixApiUtils.hidePrivate(requestJson.toMap(), ZabbixApiUtils.DEFAULT_PRIVATE_FIELDS));


        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(this.apiUrl))
                .timeout(Duration.ofMillis(this.timeoutMilliseconds))
                .header("Content-Type", "application/json-rpc")
                .POST(HttpRequest.BodyPublishers.ofString(requestBodyString));
        try {
            HttpResponse<String> httpResponse = this.httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            logger.trace("Response status code: {}", httpResponse.statusCode());
            if (logger.isTraceEnabled()) {
                 try {
                    JSONObject respJsonForLog = new JSONObject(httpResponse.body());
                    logger.trace("Response JSON: {}", ZabbixApiUtils.hidePrivate(respJsonForLog.toMap(), ZabbixApiUtils.DEFAULT_PRIVATE_FIELDS));
                } catch (JSONException e) {
                    logger.trace("Response Body (not valid JSON or empty): {}", httpResponse.body());
                }
            }


            if (httpResponse.statusCode() < 200 || httpResponse.statusCode() >= 300) {
                throw new ZabbixApiException("HTTP error " + httpResponse.statusCode() + " calling Zabbix API: " + httpResponse.body());
            }
            String responseBody = httpResponse.body();
            if (responseBody == null || responseBody.trim().isEmpty()) {
                throw new ZabbixProcessingException("Received empty response body from Zabbix API.");
            }
            JSONObject responseJson = new JSONObject(responseBody);
            if (responseJson.has("error")) {
                JSONObject errorObj = responseJson.getJSONObject("error");
                throw new ZabbixApiRequestException(
                        String.format("Zabbix API Error (code: %d, message: %s, data: %s)",
                                      errorObj.optInt("code"), errorObj.optString("message"), errorObj.optString("data")),
                        errorObj.optInt("code"), errorObj.optString("message"), errorObj.optString("data"),
                        ZabbixApiUtils.hidePrivate(requestJson.toMap(), ZabbixApiUtils.DEFAULT_PRIVATE_FIELDS).toString() // Pass sanitized request
                );
            }
            if (!responseJson.has("result")) {
                 throw new ZabbixProcessingException("Invalid Zabbix API response: Missing 'result' field. Response: " + responseBody);
            }
            return responseJson.get("result");
        } catch (IOException | InterruptedException e) {
            logger.error("Error sending request to Zabbix API: {}", e.getMessage(), e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new ZabbixProcessingException("Failed to send request to Zabbix API: " + e.getMessage(), e);
        } catch (JSONException e) {
            logger.error("Error parsing JSON request or response: {}", e.getMessage(), e);
            throw new ZabbixProcessingException("JSON parsing error: " + e.getMessage(), e);
        }
    }

    public UserService userService() {
        return this.userServiceImpl;
    }

    /**
     * Provides access to Host related API calls.
     * @return An instance of {@link HostService}.
     * @author CSJ
     */
    public HostService hostService() {
        return this.hostServiceImpl;
    }

    /**
     * Provides access to Item related API calls.
     * @return An instance of {@link ItemService}.
     * @author CSJ
     */
    public ItemService itemService() {
        return this.itemServiceImpl;
    }

    /**
     * Provides access to History related API calls.
     * @return An instance of {@link HistoryService}.
     * @author CSJ
     */
    public HistoryService historyService() {
        return this.historyServiceImpl;
    }

    private static class UserServiceImpl implements UserService {
        private final ZabbixApi zabbixApi;
        public UserServiceImpl(ZabbixApi zabbixApi) { this.zabbixApi = zabbixApi; }
        @Override
        public UserLoginResponse login(String user, String password) throws ZabbixApiException {
            zabbixApi.loginWithUserPassword(user, password);
            return new UserLoginResponse(zabbixApi.authToken.get(), null);
        }
    }

    private static class HostServiceImpl implements HostService {
        private final ZabbixApi zabbixApi;
        public HostServiceImpl(ZabbixApi zabbixApi) { this.zabbixApi = zabbixApi; }

        @Override
        public JSONArray get(Map<String, Object> params) throws ZabbixApiException {
            logger.debug("HostService.get called with params: {}", params);
            return (JSONArray) zabbixApi.sendRequest("host.get", params, true);
        }
        @Override
        public JSONObject create(Map<String, Object> params) throws ZabbixApiException {
            logger.debug("HostService.create called with params: {}", params);
            return (JSONObject) zabbixApi.sendRequest("host.create", params, true);
        }
        @Override
        public JSONObject update(Map<String, Object> params) throws ZabbixApiException {
            logger.debug("HostService.update called with params: {}", params);
            return (JSONObject) zabbixApi.sendRequest("host.update", params, true);
        }
        @Override
        public JSONObject delete(List<String> hostIds) throws ZabbixApiException {
            logger.debug("HostService.delete called for host IDs: {}", hostIds);
            // Zabbix API for host.delete expects an array of host IDs as params.
            return (JSONObject) zabbixApi.sendRequest("host.delete", hostIds, true);
        }
    }

    private static class ItemServiceImpl implements ItemService {
        private final ZabbixApi zabbixApi;
        public ItemServiceImpl(ZabbixApi zabbixApi) { this.zabbixApi = zabbixApi; }

        @Override
        public JSONArray get(Map<String, Object> params) throws ZabbixApiException {
            logger.debug("ItemService.get called with params: {}", params);
            return (JSONArray) zabbixApi.sendRequest("item.get", params, true);
        }
        @Override
        public JSONObject create(Map<String, Object> params) throws ZabbixApiException {
            logger.debug("ItemService.create called with params: {}", params);
            return (JSONObject) zabbixApi.sendRequest("item.create", params, true);
        }
        @Override
        public JSONObject update(Map<String, Object> params) throws ZabbixApiException {
            logger.debug("ItemService.update called with params: {}", params);
            return (JSONObject) zabbixApi.sendRequest("item.update", params, true);
        }
        @Override
        public JSONObject delete(List<String> itemIds) throws ZabbixApiException {
            logger.debug("ItemService.delete called for item IDs: {}", itemIds);
            // Zabbix API for item.delete expects an array of item IDs as params.
            return (JSONObject) zabbixApi.sendRequest("item.delete", itemIds, true);
        }
    }

    private static class HistoryServiceImpl implements HistoryService {
        private final ZabbixApi zabbixApi;
        public HistoryServiceImpl(ZabbixApi zabbixApi) { this.zabbixApi = zabbixApi; }

        @Override
        public JSONArray get(Map<String, Object> params) throws ZabbixApiException {
            logger.debug("HistoryService.get called with params: {}", params);
            return (JSONArray) zabbixApi.sendRequest("history.get", params, true);
        }
    }
}
