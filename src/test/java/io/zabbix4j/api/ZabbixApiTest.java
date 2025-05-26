package io.zabbix4j.api;

import io.zabbix4j.api.exception.ZabbixApiException;
import io.zabbix4j.api.exception.ZabbixApiNotSupportedException;
import io.zabbix4j.api.exception.ZabbixApiRequestException;
import io.zabbix4j.api.exception.ZabbixProcessingException;
import io.zabbix4j.api.service.UserLoginResponse;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.util.List;
import java.util.Map;
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
 * Unit tests for the {@link ZabbixApi} class using {@link MockWebServer}.
 *
 * @author CSJ
 */
@Timeout(10) // Default timeout for tests
class ZabbixApiTest {

    private MockWebServer mockWebServer;
    private String baseMockUrl;
    private ZabbixApi zabbixApi;

    // Helper to create a trust-all SSLContext
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
        baseMockUrl = mockWebServer.url("/").toString(); // e.g., http://localhost:12345/

        // Silence ZabbixApiUtils logs if they are too verbose for tests
        Logger utilsLogger = (Logger) LoggerFactory.getLogger(io.zabbix4j.api.utils.ZabbixApiUtils.class);
        utilsLogger.setLevel(Level.WARN);
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
    @DisplayName("Builder and Constructor Tests")
    class BuilderAndConstructorTests {

        @Test
        @DisplayName("Builder should correctly normalize URL")
        void testBuilder_UrlNormalization() {
            ZabbixApi.Builder builder = ZabbixApi.builder().url("localhost/zabbix");
            // Accessing apiUrl directly is not possible, but build() uses it.
            // We can check if a simple call works with the mock server.
            mockWebServer.enqueue(new MockResponse().setBody(createJsonResponse("6.0.0", null, 1)));
            ZabbixApi api = builder.skipVersionCheck(true).build(); // skip version check for this simple build test
            
            assertNotNull(api);
            // The actual URL used by HttpClient is what matters.
            // We can't directly get it from ZabbixApi instance easily without reflection or a getter.
            // Test by making a call.
            try {
                api.getApiVersion(); // This will make a call if not skipping.
                RecordedRequest request = mockWebServer.takeRequest(1, TimeUnit.SECONDS);
                assertNotNull(request);
                assertEquals("/api_jsonrpc.php", request.getPath()); // ZabbixApiUtils appends this
            } catch (Exception e) {
                fail("API call failed with normalized URL: " + e.getMessage());
            }
        }

        @Test
        @DisplayName("Builder with basicAuth should configure HttpClient")
        void testBuilder_BasicAuth() throws Exception {
            mockWebServer.enqueue(new MockResponse().setBody(createJsonResponse("sessionid123", null, 1))); // For login
            mockWebServer.enqueue(new MockResponse().setBody(createJsonResponse("6.0.0", null, 2)));      // For api version

            zabbixApi = ZabbixApi.builder()
                .url(baseMockUrl)
                .basicAuth("testuser", "testpass")
                .build();
            
            zabbixApi.loginWithUserPassword("apiuser", "apipass"); // This makes a request

            RecordedRequest loginRequest = mockWebServer.takeRequest(1, TimeUnit.SECONDS);
            assertNotNull(loginRequest);
            String expectedAuth = "Basic " + Base64.getEncoder().encodeToString("testuser:testpass".getBytes());
            assertEquals(expectedAuth, loginRequest.getHeader("Authorization"));
        }

        @Test
        @DisplayName("Builder with validateCerts=false should use trust-all SSLContext")
        void testBuilder_ValidateCertsFalse() throws Exception {
            // Configure MockWebServer for HTTPS
            SSLContext trustAllCtx = createTrustAllSslContextForTest();
            SSLSocketFactory clientSocketFactory = trustAllCtx.getSocketFactory(); // For client, if needed by MockWebServer config
            mockWebServer.useHttps(clientSocketFactory, false); // false for no client auth
            String httpsMockUrl = mockWebServer.url("/").toString();


            mockWebServer.enqueue(new MockResponse().setBody(createJsonResponse("6.0.0", null, 1)));
            
            ZabbixApi api = ZabbixApi.builder()
                .url(httpsMockUrl)
                .validateCertificates(false) // This should trigger usage of the internal trust-all context
                .skipVersionCheck(false) // Make it call apiinfo.version
                .build();

            assertNotNull(api.getApiVersion()); // This will make an HTTPS call
            assertEquals(1, mockWebServer.getRequestCount());
        }
        
        @Test
        @DisplayName("Builder with custom SSLContextSupplier")
        void testBuilder_CustomSslContext() throws Exception {
            SSLContext customContext = createTrustAllSslContextForTest(); // Use our trust-all for simplicity
            Supplier<SSLContext> sslContextSupplier = () -> customContext;

            mockWebServer.useHttps(customContext.getSocketFactory(), false);
            String httpsMockUrl = mockWebServer.url("/").toString();
            mockWebServer.enqueue(new MockResponse().setBody(createJsonResponse("6.0.1", null, 1)));

            ZabbixApi api = ZabbixApi.builder()
                .url(httpsMockUrl)
                .sslContextSupplier(sslContextSupplier)
                .skipVersionCheck(false)
                .build();
            
            assertNotNull(api.getApiVersion());
            assertEquals(1, mockWebServer.getRequestCount());
        }


        @Test
        @DisplayName("Builder with null URL should throw IllegalArgumentException on build")
        void testBuilder_NullUrl_ThrowsException() {
            ZabbixApi.Builder builder = ZabbixApi.builder();
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, builder::build);
            assertEquals("Zabbix API URL must be set.", e.getMessage());
        }

        @Test
        @DisplayName("Builder with invalid timeout should throw IllegalArgumentException")
        void testBuilder_InvalidTimeout_ThrowsException() {
            ZabbixApi.Builder builder = ZabbixApi.builder().url(baseMockUrl);
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> builder.timeout(0, TimeUnit.SECONDS));
            assertEquals("Timeout must be positive.", e.getMessage());
        }
    }

    @Nested
    @DisplayName("Login and Logout Tests")
    class LoginLogoutTests {
        @Test
        @DisplayName("loginWithUserPassword success should set auth token")
        void testLoginWithUserPassword_Success() throws Exception {
            String sessionId = "testsessionid12345";
            mockWebServer.enqueue(new MockResponse().setBody(createJsonResponse(sessionId, null, 1))); // For login
            mockWebServer.enqueue(new MockResponse().setBody(createJsonResponse("6.0.0", null, 2)));      // For api version

            zabbixApi = ZabbixApi.builder().url(baseMockUrl).skipVersionCheck(false).build();
            zabbixApi.loginWithUserPassword("Admin", "zabbix");

            RecordedRequest loginRequest = mockWebServer.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(loginRequest);
            String loginRequestBody = loginRequest.getBody().readUtf8();
            JSONObject loginJson = new JSONObject(loginRequestBody);
            assertEquals("user.login", loginJson.getString("method"));
            assertEquals("Admin", loginJson.getJSONObject("params").getString("user"));

            // Auth token is private, cannot directly assert. Test via checkAuth or subsequent calls.
            assertTrue(zabbixApi.checkAuth(), "checkAuth should be true after successful login.");
            assertFalse(zabbixApi.usingTokenAuth, "usingTokenAuth should be false for session ID.");
        }

        @Test
        @DisplayName("loginWithUserPassword failure should throw ZabbixApiRequestException")
        void testLoginWithUserPassword_Failure() {
            mockWebServer.enqueue(new MockResponse().setBody(createErrorResponse(-32602, "Invalid params", "Invalid user or password.", 1)));
            zabbixApi = ZabbixApi.builder().url(baseMockUrl).skipVersionCheck(true).build();
            
            ZabbixApiRequestException e = assertThrows(ZabbixApiRequestException.class, 
                () -> zabbixApi.loginWithUserPassword("Admin", "wrongpass"));
            assertTrue(e.getMessage().contains("Invalid user or password."));
            assertEquals(-32602, e.getCode());
        }

        @Test
        @DisplayName("loginWithToken success should set auth token and usingTokenAuth")
        void testLoginWithToken_Success() throws Exception {
            String apiToken = "abcdef0123456789abcdef0123456789";
            // For token "login", we might do an apiinfo.version call to validate
            mockWebServer.enqueue(new MockResponse().setBody(createJsonResponse("6.2.0", null, 1))); 

            zabbixApi = ZabbixApi.builder().url(baseMockUrl).skipVersionCheck(false).build();
            zabbixApi.loginWithToken(apiToken);

            RecordedRequest apiInfoRequest = mockWebServer.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(apiInfoRequest);
            String apiInfoRequestBody = apiInfoRequest.getBody().readUtf8();
            JSONObject apiInfoJson = new JSONObject(apiInfoRequestBody);
            assertEquals("apiinfo.version", apiInfoJson.getString("method"));
            // If getApiVersion was called due to loginWithToken, it might not have "auth" if token logic is to add it later
            // But if getApiVersion internally calls checkAuth() or sendRequest with needsAuth=true, it would include it.
            // Let's assume getApiVersion after token set uses the token.
            // The current ZabbixApi.getApiVersion calls sendRequest with needsAuth=false for apiinfo.version.
            // The check for token validity happens on the *next* authenticated call.
            // So, to verify token usage, make an authenticated call.

            assertTrue(zabbixApi.usingTokenAuth, "usingTokenAuth should be true for API token.");
            
            // Make a dummy authenticated call to check if token is sent
            mockWebServer.enqueue(new MockResponse().setBody(createJsonResponse(new JSONObject().put("sessionid", apiToken), null, 2)));
            zabbixApi.checkAuth();
            RecordedRequest checkAuthRequest = mockWebServer.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(checkAuthRequest);
            String checkAuthBody = checkAuthRequest.getBody().readUtf8();
            JSONObject checkAuthJson = new JSONObject(checkAuthBody);
            assertEquals(apiToken, checkAuthJson.getString("auth"));
        }

        @Test
        @DisplayName("logout success should clear auth token")
        void testLogout_Success() throws Exception {
            // Login first
            mockWebServer.enqueue(new MockResponse().setBody(createJsonResponse("sessionid123", null, 1)));
            mockWebServer.enqueue(new MockResponse().setBody(createJsonResponse("6.0.0", null, 2))); // api version
            zabbixApi = ZabbixApi.builder().url(baseMockUrl).build();
            zabbixApi.loginWithUserPassword("TestUser", "TestPass");
            assertTrue(zabbixApi.checkAuth(), "Should be logged in before logout."); // Consumes one request due to checkAuth

            // Enqueue logout response
            mockWebServer.enqueue(new MockResponse().setBody(createJsonResponse(true, null, 3))); // user.logout result is boolean true
            zabbixApi.logout();

            RecordedRequest logoutRequest = mockWebServer.takeRequest(2, TimeUnit.SECONDS); // after login, version, checkAuth
            assertNotNull(logoutRequest);
            String logoutRequestBody = logoutRequest.getBody().readUtf8();
            JSONObject logoutJson = new JSONObject(logoutRequestBody);
            assertEquals("user.logout", logoutJson.getString("method"));
            assertEquals("sessionid123", logoutJson.getString("auth"));

            assertFalse(zabbixApi.checkAuth(), "checkAuth should be false after logout."); // This will try to call and fail or return false due to no token
        }

        @Test
        @DisplayName("logout when not logged in should be a no-op")
        void testLogout_NotLoggedIn() throws Exception {
            zabbixApi = ZabbixApi.builder().url(baseMockUrl).build();
            zabbixApi.logout(); // Should not throw, should not make a request
            assertEquals(0, mockWebServer.getRequestCount(), "No HTTP request should be made if not logged in.");
        }
    }
    
    @Nested
    @DisplayName("getApiVersion Tests")
    class GetApiVersionTests {
        @Test
        @DisplayName("getApiVersion success and caching")
        void testGetApiVersion_SuccessAndCaching() throws Exception {
            String versionStr = "5.4.12";
            mockWebServer.enqueue(new MockResponse().setBody(createJsonResponse(versionStr, null, 1)));
            zabbixApi = ZabbixApi.builder().url(baseMockUrl).skipVersionCheck(true).build(); // Skip initial check

            ApiVersion version1 = zabbixApi.getApiVersion();
            assertEquals(versionStr, version1.getRaw());
            assertEquals(1, mockWebServer.getRequestCount(), "First call should make a request.");

            ApiVersion version2 = zabbixApi.getApiVersion();
            assertEquals(versionStr, version2.getRaw());
            assertEquals(1, mockWebServer.getRequestCount(), "Second call should use cached version.");
            assertTrue(version1 == version2, "Should return the same cached instance.");
        }

        @Test
        @DisplayName("getApiVersion should be called on build if skipVersionCheck is false (default)")
        void testGetApiVersion_CalledOnBuild() throws Exception {
            mockWebServer.enqueue(new MockResponse().setBody(createJsonResponse("6.0.0", null, 1)));
            zabbixApi = ZabbixApi.builder().url(baseMockUrl).build(); // skipVersionCheck is false by default
            // The call happens in ZabbixApi constructor if not skipping and login was successful,
            // or if login is not required for getApiVersion (which is true for apiinfo.version).
            // If using user/pass login, it happens after login. If no login, it happens directly.
            // The current ZabbixApi.performLogin calls getApiVersion if !skipVersionCheck.
            // If no login is performed, getApiVersion() is called when first needed.
            // The constructor itself doesn't call it. Let's test after login.
            
            mockWebServer.enqueue(new MockResponse().setBody(createJsonResponse("sessionidxxx", null, 1)));
            // The getApiVersion call will be triggered by performLogin
            zabbixApi.loginWithUserPassword("user", "pass");

            assertEquals(2, mockWebServer.getRequestCount(), "apiinfo.version should be called by login if not skipping.");
            assertNotNull(zabbixApi.cachedApiVersion.get());
        }
    }

    // Placeholder for service method tests - e.g., hostService().get()
    @Nested
    @DisplayName("Service Method Tests (e.g., HostService)")
    class ServiceMethodTests {
        @Test
        @DisplayName("hostService().get() should send correct request and parse response")
        void testHostServiceGet() throws Exception {
            // 1. Login
            String sessionId = "sessionForHostService";
            mockWebServer.enqueue(new MockResponse().setBody(createJsonResponse(sessionId, null, 1))); // Login
            mockWebServer.enqueue(new MockResponse().setBody(createJsonResponse("6.0.0", null, 2)));      // API Version
            zabbixApi = ZabbixApi.builder().url(baseMockUrl).build();
            zabbixApi.loginWithUserPassword("user", "pass");
             mockWebServer.takeRequest(); // login
             mockWebServer.takeRequest(); // api version from login

            // 2. Enqueue response for host.get
            JSONArray hostResultArray = new JSONArray().put(new JSONObject().put("hostid", "1001").put("host", "Test Host"));
            mockWebServer.enqueue(new MockResponse().setBody(createJsonResponse(hostResultArray, null, 3)));

            // 3. Call service method
            Map<String, Object> params = new HashMap<>();
            params.put("output", "extend");
            params.put("filter", Collections.singletonMap("host", "Test Host"));
            JSONArray hosts = zabbixApi.hostService().get(params);

            // 4. Verify request
            RecordedRequest hostGetRequest = mockWebServer.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(hostGetRequest);
            String requestBody = hostGetRequest.getBody().readUtf8();
            JSONObject requestJson = new JSONObject(requestBody);
            assertEquals("host.get", requestJson.getString("method"));
            assertEquals(sessionId, requestJson.getString("auth"));
            assertTrue(requestJson.getJSONObject("params").has("output"));

            // 5. Verify response
            assertNotNull(hosts);
            assertEquals(1, hosts.length());
            assertEquals("1001", hosts.getJSONObject(0).getString("hostid"));
        }
    }
    
    @Nested
    @DisplayName("Error Handling in sendRequest")
    class SendRequestErrorHandlingTests {
        @Test
        @DisplayName("sendRequest with API error response should throw ZabbixApiRequestException")
        void testSendRequest_ApiError() throws Exception {
            mockWebServer.enqueue(new MockResponse().setBody(createErrorResponse(-32600, "Invalid Request", "The JSON sent is not a valid Request object.", 1)));
            zabbixApi = ZabbixApi.builder().url(baseMockUrl).skipVersionCheck(true).build();

            ZabbixApiRequestException ex = assertThrows(ZabbixApiRequestException.class, 
                () -> zabbixApi.sendRequest("some.method", Collections.emptyMap(), false));
            
            assertEquals(-32600, ex.getCode());
            assertTrue(ex.getApiErrorMessage().contains("Invalid Request"));
            assertTrue(ex.getData().contains("The JSON sent is not a valid Request object."));
            assertNotNull(ex.getRequestBody(), "Request body should be included in exception");
        }

        @Test
        @DisplayName("sendRequest with HTTP 500 error should throw ZabbixApiException (or ZabbixProcessingException)")
        void testSendRequest_Http500Error() {
            mockWebServer.enqueue(new MockResponse().setResponseCode(HttpURLConnection.HTTP_INTERNAL_ERROR).setBody("Server Error"));
            zabbixApi = ZabbixApi.builder().url(baseMockUrl).skipVersionCheck(true).build();

            ZabbixApiException ex = assertThrows(ZabbixApiException.class, 
                () -> zabbixApi.sendRequest("some.method", Collections.emptyMap(), false));
            assertTrue(ex.getMessage().contains("HTTP error 500"));
        }
        
        @Test
        @DisplayName("sendRequest with network error (read timeout) should throw ZabbixProcessingException")
        void testSendRequest_NetworkReadTimeout() {
            // MockWebServer doesn't easily simulate read timeout on its own.
            // HttpClient configured with a short timeout and server delaying response is better.
            mockWebServer.enqueue(new MockResponse().setBody(createJsonResponse("ok", null, 1)).setBodyDelay(500, TimeUnit.MILLISECONDS));
            zabbixApi = ZabbixApi.builder().url(baseMockUrl).skipVersionCheck(true).timeout(100, TimeUnit.MILLISECONDS).build();

            ZabbixProcessingException ex = assertThrows(ZabbixProcessingException.class, 
                () -> zabbixApi.sendRequest("some.method", Collections.emptyMap(), false));
            assertTrue(ex.getCause() instanceof java.net.http.HttpTimeoutException, "Cause should be HttpTimeoutException");
        }
    }
     // API Version Compatibility tests are hard to do without actual MIN/MAX versions being different from test response.
    // Test logic would be:
    // 1. Enqueue apiinfo.version response (e.g., "4.0.0")
    // 2. Create ZabbixApi with skipVersionCheck=false
    // 3. Call getApiVersion() or login (which calls getApiVersion)
    // 4. Assert ZabbixApiNotSupportedException is thrown if "4.0.0" < Version.MIN_SUPPORTED_ZABBIX_API
    // Need to ensure Version.MIN_SUPPORTED_ZABBIX_API is something like "5.0.0" for this test.
}
