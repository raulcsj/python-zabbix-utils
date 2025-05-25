package io.zabbix4j.api;

import com.google.gson.Gson;
import io.zabbix4j.api.common.ZabbixApiConstants;
import io.zabbix4j.api.exceptions.ApiNotSupportedException;
import io.zabbix4j.api.exceptions.ApiRequestException;
import io.zabbix4j.api.exceptions.ProcessingException;
import io.zabbix4j.api.exceptions.ZabbixApiException;
import io.zabbix4j.api.types.ApiVersion;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ZabbixApi}.
 *
 * @author CSJ
 */
@ExtendWith(MockitoExtension.class)
class ZabbixApiTest {

    @Mock
    private OkHttpClient mockHttpClient;
    @Mock
    private Call mockCall;
    @Mock
    private Response mockOkHttpResponse;
    @Mock
    private ResponseBody mockResponseBody;

    @Captor
    private ArgumentCaptor<Request> requestCaptor;

    private ZabbixApi.ZabbixApiBuilder apiBuilder;
    private final String DUMMY_URL = "http://localhost/zabbix/api_jsonrpc.php";
    private final Gson gson = new Gson(); // For crafting request/response bodies

    @BeforeEach
    void setUp() {
        apiBuilder = new ZabbixApi.ZabbixApiBuilder()
                .url(DUMMY_URL) // Valid URL by default
                .httpClient(mockHttpClient); // Inject mock client

        // Common mock setup for successful OkHttp execution
        lenient().when(mockHttpClient.newCall(any(Request.class))).thenReturn(mockCall);
        lenient().when(mockCall.execute()).thenReturn(mockOkHttpResponse);
        lenient().when(mockOkHttpResponse.isSuccessful()).thenReturn(true);
        lenient().when(mockOkHttpResponse.body()).thenReturn(mockResponseBody);
    }

    private void mockApiResponse(String jsonResult, String id) throws IOException {
        String jsonResponse = String.format("{\"jsonrpc\":\"2.0\",\"result\":%s,\"id\":\"%s\"}", jsonResult, id);
        when(mockResponseBody.string()).thenReturn(jsonResponse);
    }
    
    private void mockApiResponse(String jsonResult) throws IOException {
         mockApiResponse(jsonResult, "test-id-123"); // Default ID if not specified
    }


    private void mockApiErrorResponse(int code, String message, String data, String id) throws IOException {
        String errorJson = String.format("{\"code\":%d,\"message\":\"%s\",\"data\":\"%s\"}", code, message, data);
        String jsonResponse = String.format("{\"jsonrpc\":\"2.0\",\"error\":%s,\"id\":\"%s\"}", errorJson, id);
        when(mockResponseBody.string()).thenReturn(jsonResponse);
    }
     private void mockApiErrorResponse(int code, String message, String data) throws IOException {
        mockApiErrorResponse(code, message, data, "test-id-123");
    }


    private void mockNetworkError() throws IOException {
        when(mockCall.execute()).thenThrow(new IOException("Simulated network error"));
    }

    private void mockHttpError(int httpCode, String httpMessage) throws IOException {
        when(mockOkHttpResponse.isSuccessful()).thenReturn(false);
        when(mockOkHttpResponse.code()).thenReturn(httpCode);
        when(mockOkHttpResponse.message()).thenReturn(httpMessage);
        // Mock empty body for error to prevent NullPointerException when it tries to read body
        lenient().when(mockResponseBody.string()).thenReturn("{\"error\":\"HTTP error\"}");
    }

    @Nested
    class BuilderTests {
        @Test
        void testValidBuilder_urlOnly() throws ZabbixApiException {
            ZabbixApi api = new ZabbixApi.ZabbixApiBuilder().url(DUMMY_URL).httpClient(mockHttpClient).build();
            assertNotNull(api);
        }

        @Test
        void testValidBuilder_urlAndUserPass() throws ZabbixApiException, IOException {
            mockApiResponse("\"sessionid123\"");
            ZabbixApi api = new ZabbixApi.ZabbixApiBuilder()
                    .url(DUMMY_URL)
                    .user("Admin")
                    .password("zabbix")
                    .httpClient(mockHttpClient)
                    .build();
            assertNotNull(api);
            assertTrue(api.isAuthenticated());
        }

        @Test
        void testValidBuilder_urlAndToken() throws ZabbixApiException, IOException {
            // loginWithToken version check needs apiinfo.version
            mockApiResponse("\"6.0.0\""); // Mock apiinfo.version call first

            ZabbixApi api = new ZabbixApi.ZabbixApiBuilder()
                    .url(DUMMY_URL)
                    .token("myapitoken123")
                    .httpClient(mockHttpClient)
                    .build();
            assertNotNull(api);
            // isAuthenticated for token needs another call
            mockApiResponse("{\"userid\":\"1\", \"sessionid\":\"myapitoken123\"}");
            assertTrue(api.isAuthenticated());
        }

        @Test
        void testValidBuilder_urlAndHttpBasicAuth() throws ZabbixApiException {
            ZabbixApi api = new ZabbixApi.ZabbixApiBuilder()
                    .url(DUMMY_URL)
                    .httpUser("httpuser")
                    .httpPassword("httppass")
                    .httpClient(mockHttpClient)
                    .build();
            assertNotNull(api);
        }

        @Test
        void testUrlSanitization_needsSuffix() {
            String sanitized = ZabbixApi.ZabbixApiBuilder.class.getDeclaredMethod("sanitizeUrl", String.class)
                .setAccessible(true); // Access private static method
            String result = (String) ZabbixApi.class.getDeclaredMethod("sanitizeUrl", String.class)
                                .invoke(null, "http://localhost/zabbix");
            assertEquals("http://localhost/zabbix/api_jsonrpc.php", result);
        }
        
        @Test
        void testUrlSanitization_needsSuffixWithSlash() throws Exception {
             Method sanitizeUrlMethod = ZabbixApi.class.getDeclaredMethod("sanitizeUrl", String.class);
             sanitizeUrlMethod.setAccessible(true);
             String result = (String) sanitizeUrlMethod.invoke(null, "http://localhost/zabbix/");
             assertEquals("http://localhost/zabbix/api_jsonrpc.php", result);
        }


        @Test
        void testInvalidBuilder_nullUrl() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> new ZabbixApi.ZabbixApiBuilder().httpClient(mockHttpClient).build());
            assertEquals("Zabbix API URL must be set.", e.getMessage());
        }
        
        @Test
        void testInvalidBuilder_emptyUrl() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> new ZabbixApi.ZabbixApiBuilder().url("").httpClient(mockHttpClient).build());
            assertEquals("API URL cannot be null or empty.", e.getMessage());
        }


        @Test
        void testInvalidBuilder_conflictingAuth() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> new ZabbixApi.ZabbixApiBuilder()
                            .url(DUMMY_URL)
                            .user("Admin")
                            .password("zabbix")
                            .token("mytoken")
                            .httpClient(mockHttpClient)
                            .build());
            assertEquals("Cannot configure both token-based and user/password-based authentication. Please choose one.", e.getMessage());
        }

        @Test
        void testInvalidBuilder_invalidTimeout() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> new ZabbixApi.ZabbixApiBuilder().url(DUMMY_URL).timeoutSeconds(0));
            assertEquals("Timeout must be positive.", e.getMessage());
        }

        @Test
        void testSkipVersionCheck_configured() throws ZabbixApiException, IOException {
            mockApiResponse("\"1.0.0\""); // Version that would normally fail
            ZabbixApi api = apiBuilder.skipVersionCheck(true).build();
            ApiVersion version = api.getApiVersion(); // Should not throw due to skip
            assertEquals("1.0.0", version.toString());
        }
    }

    @Nested
    class ApiVersionFetchingTests {
        @Test
        void testGetApiVersion_successfulFetchAndCache() throws ZabbixApiException, IOException {
            mockApiResponse("\"6.0.12\"");
            ZabbixApi api = apiBuilder.build();

            ApiVersion version1 = api.getApiVersion();
            assertEquals("6.0.12", version1.toString());

            ApiVersion version2 = api.getApiVersion(); // Should be cached
            assertSame(version1, version2);

            verify(mockHttpClient, times(1)).newCall(any(Request.class)); // Called only once
        }

        @Test
        void testCheckCompatibility_versionTooLow() throws IOException {
            mockApiResponse("\"4.0.0\""); // Min supported is 5.0
            ZabbixApi api = apiBuilder.build();
            ApiNotSupportedException e = assertThrows(ApiNotSupportedException.class, api::getApiVersion);
            assertTrue(e.getMessage().contains("is not supported. Minimum supported version is 5.0"));
        }

        @Test
        void testCheckCompatibility_versionTooHigh() throws IOException {
            mockApiResponse("\"99.0.0\""); // Max tested is 7.0
            ZabbixApi api = apiBuilder.build();
            ApiNotSupportedException e = assertThrows(ApiNotSupportedException.class, api::getApiVersion);
            assertTrue(e.getMessage().contains("is newer than the maximum tested version 7.0"));
        }

        @Test
        void testCheckCompatibility_compatibleVersion() throws ZabbixApiException, IOException {
            mockApiResponse("\"6.4.0\"");
            ZabbixApi api = apiBuilder.build();
            ApiVersion version = api.getApiVersion();
            assertEquals("6.4.0", version.toString()); // No exception
        }
        
        @Test
        void testApiErrorDuringVersionFetch() throws IOException {
            mockApiErrorResponse(-32600, "Invalid Request", "Error during apiinfo.version");
            ZabbixApi api = apiBuilder.build();
            ApiRequestException e = assertThrows(ApiRequestException.class, api::getApiVersion);
            assertEquals(-32600, e.getCode());
            assertTrue(e.getMessage().contains("Invalid Request"));
        }

        @Test
        void testNetworkErrorDuringVersionFetch() throws IOException {
            mockNetworkError();
            ZabbixApi api = apiBuilder.build();
            ProcessingException e = assertThrows(ProcessingException.class, api::getApiVersion);
            assertTrue(e.getMessage().contains("Simulated network error"));
        }
    }
    
    @Nested
    class AuthenticationTests {

        @Test
        void testLoginUserPass_success_apiVersion5_0() throws ZabbixApiException, IOException {
            mockApiResponse("\"5.0.0\""); // apiinfo.version response
            ZabbixApi api = apiBuilder.build(); // Call getApiVersion
            api.getApiVersion(); 

            mockApiResponse("\"sessionid12345\""); // user.login response
            api.login("Admin", "zabbix");

            assertTrue(api.isAuthenticated());
            assertFalse(api.useTokenAuth);
            assertEquals("sessionid12345", api.authToken);

            verify(mockHttpClient, times(2)).newCall(requestCaptor.capture());
            Request loginRequest = requestCaptor.getAllValues().get(1); // 0 is version, 1 is login
            String requestBody = ((okhttp3.internal.duplex.RequestBodyDuplex) loginRequest.body()).body().readUtf8();

            assertTrue(requestBody.contains("\"method\":\"user.login\""));
            assertTrue(requestBody.contains("\"user\":\"Admin\"")); // Zabbix < 5.4 uses "user"
        }

        @Test
        void testLoginUserPass_success_apiVersion6_0() throws ZabbixApiException, IOException {
            mockApiResponse("\"6.0.0\""); // apiinfo.version
            ZabbixApi api = apiBuilder.build();
            api.getApiVersion();

            mockApiResponse("\"sessionid67890\""); // user.login
            api.login("Admin", "zabbix");

            assertTrue(api.isAuthenticated());
            assertFalse(api.useTokenAuth);
            assertEquals("sessionid67890", api.authToken);

            verify(mockHttpClient, times(2)).newCall(requestCaptor.capture());
            Request loginRequest = requestCaptor.getAllValues().get(1);
            String requestBody = ((okhttp3.internal.duplex.RequestBodyDuplex) loginRequest.body()).body().readUtf8();
            assertTrue(requestBody.contains("\"method\":\"user.login\""));
            assertTrue(requestBody.contains("\"username\":\"Admin\"")); // Zabbix >= 5.4 uses "username"
        }
        
        @Test
        void testLoginUserPass_apiError() throws ZabbixApiException, IOException {
            mockApiResponse("\"6.0.0\""); // apiinfo.version
            ZabbixApi api = apiBuilder.build();
            api.getApiVersion();

            mockApiErrorResponse(-32602, "Invalid params", "Incorrect user or password"); // user.login error
            ApiRequestException e = assertThrows(ApiRequestException.class, () -> api.login("Admin", "wrongpass"));
            assertEquals(-32602, e.getCode());
        }
        
        @Test
        void testLoginWithToken_success() throws ZabbixApiException, IOException {
            mockApiResponse("\"6.0.0\""); // apiinfo.version
            ZabbixApi api = apiBuilder.build();
            api.getApiVersion();

            api.loginWithToken("validapitoken");
            
            mockApiResponse("{\"userid\":\"1\", \"sessionid\":\"validapitoken\"}"); // for isAuthenticated
            assertTrue(api.isAuthenticated());
            assertTrue(api.useTokenAuth);
            assertEquals("validapitoken", api.authToken);
        }

        @Test
        void testLoginWithToken_versionNotSupported() throws ZabbixApiException, IOException {
            mockApiResponse("\"5.0.0\""); // apiinfo.version
            ZabbixApi api = apiBuilder.build();
            api.getApiVersion();

            ApiNotSupportedException e = assertThrows(ApiNotSupportedException.class, () -> api.loginWithToken("anytoken"));
            assertTrue(e.getMessage().contains("API token authentication") && e.getMessage().contains("not reliably supported"));
        }
        
        @Test
        void testAutoLogin_userPass_viaBuilder() throws ZabbixApiException, IOException {
            mockApiResponseOnce("\"6.0.0\""); // For initial version check during login
            mockApiResponseOnce("\"sessionbuilder123\""); // For user.login call

            ZabbixApi api = new ZabbixApi.ZabbixApiBuilder()
                .url(DUMMY_URL)
                .user("BuilderUser")
                .password("BuilderPass")
                .httpClient(mockHttpClient)
                .build();
            
            assertNotNull(api);
            mockApiResponseOnce("{\"userid\":\"1\", \"sessionid\":\"sessionbuilder123\"}"); // For isAuthenticated
            assertTrue(api.isAuthenticated());
            assertFalse(api.useTokenAuth);
            assertEquals("sessionbuilder123", api.authToken);
            verify(mockHttpClient, times(3)).newCall(any(Request.class)); // version, login, isAuthenticated
        }

        @Test
        void testLogout_sessionBased() throws ZabbixApiException, IOException {
            mockApiResponse("\"6.0.0\""); // version
            ZabbixApi api = apiBuilder.build();
            api.getApiVersion();

            mockApiResponse("\"session123\""); // login
            api.login("User", "Pass");
            assertTrue(api.isAuthenticated());

            mockApiResponse("true"); // logout response
            api.logout();

            assertNull(api.authToken);
            assertFalse(api.useTokenAuth);
            mockApiResponse("{\"error\":{}}"); // isAuthenticated will fail now
            assertFalse(api.isAuthenticated());
        }
        
        @Test
        void testLogout_tokenBased() throws ZabbixApiException, IOException {
            mockApiResponse("\"6.0.0\""); // version
            ZabbixApi api = apiBuilder.build();
            api.getApiVersion();
            api.loginWithToken("cGFzc3dvcmQ="); // login
            
            mockApiResponse("{\"sessionid\":\"cGFzc3dvcmQ=\", \"userid\":\"1\"}"); // logout response (for token based)
            api.logout();

            assertNull(api.authToken);
            assertFalse(api.useTokenAuth);
        }
        
        @Test
        void testLogout_notLoggedIn() throws ZabbixApiException {
            ZabbixApi api = apiBuilder.build();
            api.logout(); // Should not throw, just log
            verify(mockHttpClient, never()).newCall(any(Request.class)); // No API call made
        }
        
        @Test
        void testIsAuthenticated_success_session() throws ZabbixApiException, IOException {
            mockApiResponse("\"6.0.0\"");
            ZabbixApi api = apiBuilder.build();
            api.getApiVersion();
            mockApiResponse("\"sessionAbc\"");
            api.login("User", "Pass");

            mockApiResponse("{\"userid\":\"1\", \"sessionid\":\"sessionAbc\"}");
            assertTrue(api.isAuthenticated());
        }

        @Test
        void testIsAuthenticated_failure_token() throws ZabbixApiException, IOException {
            mockApiResponse("\"6.0.0\"");
            ZabbixApi api = apiBuilder.build();
            api.getApiVersion();
            api.loginWithToken("invalidToken");
            
            // Mock API error for user.checkAuthentication
            mockApiErrorResponse(-32602, "Not authorised", "Session ID is missing or invalid.", "auth-check-id");
            assertFalse(api.isAuthenticated());
        }
        
        @Test
        void testIsAuthenticated_notLoggedIn() throws ZabbixApiException {
            ZabbixApi api = apiBuilder.build();
            assertFalse(api.isAuthenticated()); // authToken is null
        }
    }

    @Nested
    class GenericCallTests {
        private ZabbixApi api;

        @BeforeEach
        void setUpApi() throws ZabbixApiException, IOException {
            mockApiResponse("\"6.0.0\""); // for getApiVersion()
            api = apiBuilder.build();
            api.getApiVersion(); // Ensure version is fetched
            
            mockApiResponse("\"testsession\""); // for login
            api.login("TestUser", "TestPass"); // Ensure authenticated for some tests
        }

        @Test
        void testCall_explicitAuthRequired_success() throws ZabbixApiException, IOException {
            Map<String, String> params = Collections.singletonMap("hostid", "10010");
            String expectedResultJson = "{\"itemid\":\"22308\",\"name\":\"CPU Load\"}";
            mockApiResponse(expectedResultJson);

            Object result = api.call("item.get", params, true);
            assertNotNull(result);
            assertTrue(result instanceof Map);
            assertEquals("22308", ((Map)result).get("itemid"));

            verify(mockHttpClient, times(3)).newCall(requestCaptor.capture()); // version, login, item.get
            Request captured = requestCaptor.getValue();
            String body = ((okhttp3.internal.duplex.RequestBodyDuplex) captured.body()).body().readUtf8();
            assertTrue(body.contains("\"method\":\"item.get\""));
            assertTrue(body.contains("\"auth\":\"testsession\"")); // Auth token in body for 6.0.0
        }

        @Test
        void testCall_unauthenticatedMethod_autoDetect() throws ZabbixApiException, IOException {
            // Re-mock apiinfo.version as it's called again by this specific test path
            mockApiResponse("\"6.0.1\""); // For the actual call to apiinfo.version
            Object result = api.call("apiinfo.version", null); // requiresAuth will be false
            assertEquals("6.0.1", result);
            
            verify(mockHttpClient, times(3)).newCall(requestCaptor.capture()); // Initial Version, Login, apiinfo.version
            Request captured = requestCaptor.getValue(); // Last call
            String body = ((okhttp3.internal.duplex.RequestBodyDuplex) captured.body()).body().readUtf8();
            assertFalse(body.contains("\"auth\":")); // No auth token
        }
        
        @Test
        void testCall_authenticatedMethod_autoDetect() throws ZabbixApiException, IOException {
            mockApiResponse("[]"); // Empty array for "host.get"
            Object result = api.call("host.get", Collections.emptyMap()); // requiresAuth will be true
            assertNotNull(result);

            verify(mockHttpClient, atLeastOnce()).newCall(requestCaptor.capture());
            Request captured = requestCaptor.getValue();
            String body = ((okhttp3.internal.duplex.RequestBodyDuplex) captured.body()).body().readUtf8();
            assertTrue(body.contains("\"method\":\"host.get\""));
            assertTrue(body.contains("\"auth\":\"testsession\""));
        }
        
        @Test
        void testCall_noParams_autoDetect() throws ZabbixApiException, IOException {
            mockApiResponse("[]");
            api.call("usergroup.get"); // requiresAuth true, params null
            
            verify(mockHttpClient, atLeastOnce()).newCall(requestCaptor.capture());
            Request captured = requestCaptor.getValue();
            String body = ((okhttp3.internal.duplex.RequestBodyDuplex) captured.body()).body().readUtf8();
            assertTrue(body.contains("\"params\":{}")); // Params should be empty map
        }

        @Test
        void testCall_apiError() throws IOException {
            mockApiErrorResponse(-32602, "Invalid params", "Some error data");
            ApiRequestException e = assertThrows(ApiRequestException.class, 
                () -> api.call("some.method", Collections.emptyMap(), true));
            assertEquals(-32602, e.getCode());
        }
        
        @Test
        void testCall_authRequired_notLoggedIn() throws ZabbixApiException {
            api.logout(); // Ensure logged out
            ProcessingException e = assertThrows(ProcessingException.class,
                () -> api.call("item.get", null, true));
            assertTrue(e.getMessage().contains("Authentication required") && e.getMessage().contains("not logged in"));
        }
    }

    @Nested
    class SendApiRequestInternalAuthLogicTests {
        // Focus on how Authorization header or "auth" in body is chosen

        @Test
        void testAuthLogic_version5_0_basicAuthNotSet() throws ZabbixApiException, IOException {
            mockApiResponse("\"5.0.0\"");
            ZabbixApi api = apiBuilder.build();
            api.getApiVersion();
            mockApiResponse("\"session123\"");
            api.login("User", "Pass");

            mockApiResponse("true"); // Generic response for a dummy call
            api.call("dummy.method", null, true);

            verify(mockHttpClient, times(3)).newCall(requestCaptor.capture());
            Request captured = requestCaptor.getAllValues().get(2); // 0=ver, 1=login, 2=dummy.method
            assertNull(captured.header("Authorization")); // No Authorization header
            String body = ((okhttp3.internal.duplex.RequestBodyDuplex) captured.body()).body().readUtf8();
            assertTrue(body.contains("\"auth\":\"session123\"")); // Auth in body
        }
        
        @Test
        void testAuthLogic_version6_0_basicAuthSet() throws ZabbixApiException, IOException {
            mockApiResponse("\"6.0.0\"");
            ZabbixApi api = apiBuilder.httpUser("bUser").httpPassword("bPass").build();
            api.getApiVersion();
            mockApiResponse("\"session456\"");
            api.login("User", "Pass");

            mockApiResponse("true");
            api.call("dummy.method", null, true);

            verify(mockHttpClient, times(3)).newCall(requestCaptor.capture());
            Request captured = requestCaptor.getAllValues().get(2);
            assertNotNull(captured.header("Authorization")); // Basic Auth header should be present
            assertTrue(captured.header("Authorization").startsWith("Basic "));
            String body = ((okhttp3.internal.duplex.RequestBodyDuplex) captured.body()).body().readUtf8();
            assertTrue(body.contains("\"auth\":\"session456\"")); // Auth in body due to Basic Auth presence
        }

        @Test
        void testAuthLogic_version6_4_basicAuthNotSet() throws ZabbixApiException, IOException {
            mockApiResponse("\"6.4.0\"");
            ZabbixApi api = apiBuilder.build();
            api.getApiVersion();
            mockApiResponse("\"session789\"");
            api.login("User", "Pass");

            mockApiResponse("true");
            api.call("dummy.method", null, true);

            verify(mockHttpClient, times(3)).newCall(requestCaptor.capture());
            Request captured = requestCaptor.getAllValues().get(2);
            assertNotNull(captured.header("Authorization"));
            assertEquals("Bearer session789", captured.header("Authorization")); // Bearer token
            String body = ((okhttp3.internal.duplex.RequestBodyDuplex) captured.body()).body().readUtf8();
            assertFalse(body.contains("\"auth\":")); // No auth in body
        }
        
         @Test
        void testAuthLogic_version7_0_basicAuthSet() throws ZabbixApiException, IOException {
            mockApiResponse("\"7.0.0\"");
            ZabbixApi api = apiBuilder.httpUser("bUser").httpPassword("bPass").build();
            api.getApiVersion();
            mockApiResponse("\"sessionAbc\"");
            api.login("User", "Pass");

            mockApiResponse("true");
            api.call("dummy.method", null, true);

            verify(mockHttpClient, times(3)).newCall(requestCaptor.capture());
            Request captured = requestCaptor.getAllValues().get(2);
            assertNotNull(captured.header("Authorization")); 
            assertTrue(captured.header("Authorization").startsWith("Basic "));
            String body = ((okhttp3.internal.duplex.RequestBodyDuplex) captured.body()).body().readUtf8();
            assertTrue(body.contains("\"auth\":\"sessionAbc\"")); // Auth in body
        }

        @Test
        void testAuthLogic_version7_2_basicAuthNotSet_tokenAuth() throws ZabbixApiException, IOException {
            // Test with API token for versions >= 6.4
            mockApiResponse("\"7.2.0\"");
            ZabbixApi api = apiBuilder.build();
            api.getApiVersion();
            api.loginWithToken("myValidApiTokenFor7_2");

            mockApiResponse("true");
            api.call("dummy.method", null, true);

            verify(mockHttpClient, times(2)).newCall(requestCaptor.capture()); // version, dummy.method
            Request captured = requestCaptor.getAllValues().get(1);
            assertNotNull(captured.header("Authorization"));
            assertEquals("Bearer myValidApiTokenFor7_2", captured.header("Authorization"));
            String body = ((okhttp3.internal.duplex.RequestBodyDuplex) captured.body()).body().readUtf8();
            assertFalse(body.contains("\"auth\":"));
        }
    }

    @Nested
    class CloseMethodTests {
        @Test
        void testClose_callsLogout_ifAuthenticated() throws ZabbixApiException, IOException {
            mockApiResponse("\"6.0.0\"");
            ZabbixApi api = apiBuilder.build();
            api.getApiVersion();
            mockApiResponse("\"sessionToClose\"");
            api.login("User", "Pass"); // Now authenticated

            mockApiResponse("true"); // For logout call
            api.close();

            assertNull(api.authToken); // Should be cleared by logout
            // Verify logout was called (e.g., by checking if user.logout was sent)
            // This is implicitly tested if authToken is null. For more direct, spy on logout() or check mock call for "user.logout"
             verify(mockHttpClient, times(3)).newCall(requestCaptor.capture()); // version, login, logout
             Request logoutRequest = requestCaptor.getAllValues().get(2);
             String body = ((okhttp3.internal.duplex.RequestBodyDuplex) logoutRequest.body()).body().readUtf8();
             assertTrue(body.contains("\"method\":\"user.logout\""));
        }

        @Test
        void testClose_doesNotCallLogout_ifNotAuthenticated() throws ZabbixApiException {
            ZabbixApi api = apiBuilder.build();
            // No login call
            api.close();
            verify(mockHttpClient, never()).newCall(any(Request.class)); // No API calls
        }
    }
    
    // Helper to mock responses in sequence for tests that make multiple calls
    private void mockApiResponseOnce(String jsonResult) throws IOException {
        when(mockResponseBody.string()).thenReturn(
            String.format("{\"jsonrpc\":\"2.0\",\"result\":%s,\"id\":\"mock-id\"}", jsonResult)
        );
    }

}
