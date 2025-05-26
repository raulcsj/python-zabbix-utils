package io.zabbix4j.api;

import io.zabbix4j.api.exception.ZabbixApiException;
import io.zabbix4j.api.exception.ZabbixApiNotSupportedException;
import io.zabbix4j.api.exception.ZabbixApiRequestException;
import io.zabbix4j.api.exception.ZabbixProcessingException;
import io.zabbix4j.api.types.ApiVersion;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;


import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Unit tests for the {@link AsyncZabbixApi} class using {@link MockWebServer}.
 *
 * @author CSJ
 */
@Timeout(10) // Default timeout for tests in seconds
class AsyncZabbixApiTest {

    private MockWebServer mockWebServer;
    private String baseMockUrl;
    private AsyncZabbixApi asyncZabbixApi;

    private static SSLContext createTrustAllSslContextForTest() throws NoSuchAlgorithmException, KeyManagementException {
        TrustManager[] trustAllCerts = new TrustManager[]{
            new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                public void checkServerTrusted(X509Certificate[] certs, String authType) {}
            }
        };
        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(null, trustAllCerts, new SecureRandom());
        return sc;
    }

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        baseMockUrl = mockWebServer.url("/").toString();

        Logger utilsLogger = (Logger) LoggerFactory.getLogger(io.zabbix4j.api.utils.ZabbixApiUtils.class);
        utilsLogger.setLevel(Level.WARN);
        Logger apiLogger = (Logger) LoggerFactory.getLogger(AsyncZabbixApi.class);
        apiLogger.setLevel(Level.DEBUG); // Enable debug for AsyncZabbixApi to see request/response logs
    }

    @AfterEach
    void tearDown() throws IOException {
        if (mockWebServer != null) {
            mockWebServer.shutdown();
        }
    }

    private String createJsonResponse(Object result, Object error, int id) {
        JSONObject response = new JSONObject();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        if (error != null) {
            response.put("error", error);
        } else {
            response.put("result", result);
        }
        return response.toString();
    }

    private String createErrorResponse(int code, String message, String data, int id) {
        JSONObject errorObj = new JSONObject();
        errorObj.put("code", code);
        errorObj.put("message", message);
        errorObj.put("data", data);
        return createJsonResponse(null, errorObj, id);
    }

    @Nested
    @DisplayName("Builder and Constructor Tests (Async)")
    class BuilderAndConstructorAsyncTests {

        @Test
        @DisplayName("Builder should correctly normalize URL (Async)")
        void testBuilder_UrlNormalization_Async() throws Exception {
            AsyncZabbixApi.Builder builder = AsyncZabbixApi.builder().url("localhost/zabbix/async");
            mockWebServer.enqueue(new MockResponse().setBody(createJsonResponse("7.0.0", null, 1)));
            AsyncZabbixApi api = builder.skipVersionCheck(true).build();
            assertNotNull(api);
            
            api.getApiVersion().join(); // Trigger a call
            RecordedRequest request = mockWebServer.takeRequest(1, TimeUnit.SECONDS);
            assertNotNull(request);
            assertEquals("/api_jsonrpc.php", request.getPath());
        }

        @Test
        @DisplayName("Builder with basicAuth should configure HttpClient (Async)")
        void testBuilder_BasicAuth_Async() throws Exception {
            mockWebServer.enqueue(new MockResponse().setBody(createJsonResponse("sessionAsync123", null, 1))); // For login
            mockWebServer.enqueue(new MockResponse().setBody(createJsonResponse("7.0.0", null, 2)));      // For api version

            asyncZabbixApi = AsyncZabbixApi.builder()
                .url(baseMockUrl)
                .basicAuth("asyncUser", "asyncPass")
                .build();
            
            asyncZabbixApi.loginWithUserPassword("apiUserAsync", "apiPassAsync").join();

            RecordedRequest loginRequest = mockWebServer.takeRequest(1, TimeUnit.SECONDS); // login
            assertNotNull(loginRequest);
            String expectedAuth = "Basic " + Base64.getEncoder().encodeToString("asyncUser:asyncPass".getBytes());
            assertEquals(expectedAuth, loginRequest.getHeader("Authorization"));
        }

        @Test
        @DisplayName("Builder with validateCerts=false should use trust-all SSLContext (Async)")
        void testBuilder_ValidateCertsFalse_Async() throws Exception {
            SSLContext trustAllCtx = createTrustAllSslContextForTest();
            mockWebServer.useHttps(trustAllCtx.getSocketFactory(), false);
            String httpsMockUrl = mockWebServer.url("/").toString();
            mockWebServer.enqueue(new MockResponse().setBody(createJsonResponse("7.0.0", null, 1)));
            
            AsyncZabbixApi api = AsyncZabbixApi.builder()
                .url(httpsMockUrl)
                .validateCertificates(false)
                .skipVersionCheck(false)
                .build();

            assertNotNull(api.getApiVersion().join());
            assertEquals(1, mockWebServer.getRequestCount());
        }
    }

    @Nested
    @DisplayName("Async Login and Logout Tests")
    class LoginLogoutAsyncTests {
        @Test
        @DisplayName("loginWithUserPassword success should set auth token (Async)")
        void testLoginWithUserPassword_Success_Async() throws Exception {
            String sessionId = "asyncSessionId789";
            mockWebServer.enqueue(new MockResponse().setBody(createJsonResponse(sessionId, null, 1))); // login
            mockWebServer.enqueue(new MockResponse().setBody(createJsonResponse("7.0.0", null, 2)));      // api version

            asyncZabbixApi = AsyncZabbixApi.builder().url(baseMockUrl).skipVersionCheck(false).build();
            asyncZabbixApi.loginWithUserPassword("AsyncAdmin", "zabbixAsync").join();

            RecordedRequest loginRequest = mockWebServer.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(loginRequest);
            JSONObject loginJson = new JSONObject(loginRequest.getBody().readUtf8());
            assertEquals("user.login", loginJson.getString("method"));
            
            assertTrue(asyncZabbixApi.checkAuth().join(), "checkAuth should be true after successful async login.");
            assertFalse(asyncZabbixApi.usingTokenAuth, "usingTokenAuth should be false for session ID.");
        }

        @Test
        @DisplayName("loginWithUserPassword failure should complete exceptionally (Async)")
        void testLoginWithUserPassword_Failure_Async() {
            mockWebServer.enqueue(new MockResponse().setBody(createErrorResponse(-32602, "Invalid params", "Async invalid user/pass.", 1)));
            asyncZabbixApi = AsyncZabbixApi.builder().url(baseMockUrl).skipVersionCheck(true).build();
            
            CompletableFuture<Void> loginFuture = asyncZabbixApi.loginWithUserPassword("AsyncAdmin", "wrongPassAsync");
            ExecutionException ex = assertThrows(ExecutionException.class, () -> loginFuture.get(2, TimeUnit.SECONDS));
            assertTrue(ex.getCause() instanceof ZabbixApiRequestException);
            ZabbixApiRequestException zex = (ZabbixApiRequestException) ex.getCause();
            assertTrue(zex.getData().contains("Async invalid user/pass."));
            assertEquals(-32602, zex.getCode());
        }

        @Test
        @DisplayName("loginWithToken success should set auth token and usingTokenAuth (Async)")
        void testLoginWithToken_Success_Async() throws Exception {
            String apiToken = "asyncToken0123456789abcdef0123";
            mockWebServer.enqueue(new MockResponse().setBody(createJsonResponse("7.1.0", null, 1))); // api version

            asyncZabbixApi = AsyncZabbixApi.builder().url(baseMockUrl).skipVersionCheck(false).build();
            asyncZabbixApi.loginWithToken(apiToken).join();

            assertTrue(asyncZabbixApi.usingTokenAuth, "usingTokenAuth should be true for API token.");
            
            mockWebServer.enqueue(new MockResponse().setBody(createJsonResponse(new JSONObject().put("sessionid", apiToken), null, 2)));
            assertTrue(asyncZabbixApi.checkAuth().join());
            RecordedRequest checkAuthRequest = mockWebServer.takeRequest(2, TimeUnit.SECONDS); // Skips the apiinfo.version if already fetched
            assertNotNull(checkAuthRequest);
            assertEquals(apiToken, new JSONObject(checkAuthRequest.getBody().readUtf8()).getString("auth"));
        }

        @Test
        @DisplayName("logout success should clear auth token (Async)")
        void testLogout_Success_Async() throws Exception {
            mockWebServer.enqueue(new MockResponse().setBody(createJsonResponse("asyncSession123", null, 1))); // login
            mockWebServer.enqueue(new MockResponse().setBody(createJsonResponse("7.0.0", null, 2)));      // api version
            asyncZabbixApi = AsyncZabbixApi.builder().url(baseMockUrl).build();
            asyncZabbixApi.loginWithUserPassword("AsyncUser", "AsyncPass").join();
             mockWebServer.takeRequest(); // login
             mockWebServer.takeRequest(); // api version

            mockWebServer.enqueue(new MockResponse().setBody(createJsonResponse(true, null, 3))); // logout
            asyncZabbixApi.logout().join();

            RecordedRequest logoutRequest = mockWebServer.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(logoutRequest);
            assertEquals("user.logout", new JSONObject(logoutRequest.getBody().readUtf8()).getString("method"));
            
            assertFalse(asyncZabbixApi.checkAuth().join(), "checkAuth should be false after async logout.");
        }
    }

    @Nested
    @DisplayName("Async getApiVersion Tests")
    class GetApiVersionAsyncTests {
        @Test
        @DisplayName("getApiVersion success and caching (Async)")
        void testGetApiVersion_SuccessAndCaching_Async() throws Exception {
            String versionStr = "7.0.1";
            mockWebServer.enqueue(new MockResponse().setBody(createJsonResponse(versionStr, null, 1)));
            asyncZabbixApi = AsyncZabbixApi.builder().url(baseMockUrl).skipVersionCheck(true).build();

            ApiVersion version1 = asyncZabbixApi.getApiVersion().join();
            assertEquals(versionStr, version1.getRaw());
            assertEquals(1, mockWebServer.getRequestCount());

            ApiVersion version2 = asyncZabbixApi.getApiVersion().join();
            assertEquals(versionStr, version2.getRaw());
            assertEquals(1, mockWebServer.getRequestCount(), "Second call should use cached version.");
            assertTrue(version1 == version2, "Should return the same cached instance.");
        }
    }
    
    @Nested
    @DisplayName("Async Service Method Tests (e.g., AsyncHostService)")
    class ServiceMethodAsyncTests {
        @Test
        @DisplayName("asyncHostService().get() should send correct request and parse response")
        void testHostServiceGet_Async() throws Exception {
            String sessionId = "sessionForAsyncHostService";
            mockWebServer.enqueue(new MockResponse().setBody(createJsonResponse(sessionId, null, 1))); // Login
            mockWebServer.enqueue(new MockResponse().setBody(createJsonResponse("7.0.0", null, 2)));      // API Version
            asyncZabbixApi = AsyncZabbixApi.builder().url(baseMockUrl).build();
            asyncZabbixApi.loginWithUserPassword("asyncSvcUser", "pass").join();
            mockWebServer.takeRequest(); // login
            mockWebServer.takeRequest(); // api version

            JSONArray hostResultArray = new JSONArray().put(new JSONObject().put("hostid", "2001").put("host", "Async Test Host"));
            mockWebServer.enqueue(new MockResponse().setBody(createJsonResponse(hostResultArray, null, 3)));

            Map<String, Object> params = Collections.singletonMap("output", "extend");
            JSONArray hosts = asyncZabbixApi.hostService().get(params).join();

            RecordedRequest hostGetRequest = mockWebServer.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(hostGetRequest);
            JSONObject requestJson = new JSONObject(hostGetRequest.getBody().readUtf8());
            assertEquals("host.get", requestJson.getString("method"));
            assertEquals(sessionId, requestJson.getString("auth"));

            assertNotNull(hosts);
            assertEquals(1, hosts.length());
            assertEquals("2001", hosts.getJSONObject(0).getString("hostid"));
        }
    }

    @Nested
    @DisplayName("Error Handling in async sendRequest")
    class SendRequestErrorHandlingAsyncTests {
        @Test
        @DisplayName("sendRequest with API error response should complete exceptionally (Async)")
        void testSendRequest_ApiError_Async() {
            mockWebServer.enqueue(new MockResponse().setBody(createErrorResponse(-32600, "Invalid Async Request", "Details here.", 1)));
            asyncZabbixApi = AsyncZabbixApi.builder().url(baseMockUrl).skipVersionCheck(true).build();

            CompletableFuture<Object> future = asyncZabbixApi.sendRequest("error.method", Collections.emptyMap(), false);
            ExecutionException ex = assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
            assertTrue(ex.getCause() instanceof ZabbixApiRequestException);
            ZabbixApiRequestException zex = (ZabbixApiRequestException) ex.getCause();
            assertEquals(-32600, zex.getCode());
            assertTrue(zex.getApiErrorMessage().contains("Invalid Async Request"));
        }

        @Test
        @DisplayName("sendRequest with HTTP 500 error should complete exceptionally (Async)")
        void testSendRequest_Http500Error_Async() {
            mockWebServer.enqueue(new MockResponse().setResponseCode(HttpURLConnection.HTTP_INTERNAL_ERROR).setBody("Async Server Error"));
            asyncZabbixApi = AsyncZabbixApi.builder().url(baseMockUrl).skipVersionCheck(true).build();

            CompletableFuture<Object> future = asyncZabbixApi.sendRequest("error.method", Collections.emptyMap(), false);
            ExecutionException ex = assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
            assertTrue(ex.getCause() instanceof ZabbixApiException); // Or ZabbixProcessingException if that's how sendRequest wraps it
            assertTrue(ex.getCause().getMessage().contains("HTTP error 500"));
        }
    }
}
