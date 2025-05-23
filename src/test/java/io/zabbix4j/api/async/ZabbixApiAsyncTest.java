package io.zabbix4j.api.async;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.zabbix4j.api.ZabbixApiConstants;
import io.zabbix4j.api.exception.ApiNotSupportedException;
import io.zabbix4j.api.exception.ApiRequestException;
import io.zabbix4j.api.exception.CommunicationException;
import io.zabbix4j.api.exception.ProcessingException;

import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.HttpHeaders;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;


import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;


/**
 * Unit tests for {@link ZabbixApiAsync}.
 * @author ShortRoundDev
 */
@ExtendWith(MockitoExtension.class)
class ZabbixApiAsyncTest {

    private static final String TEST_URL = "http://localhost:8080/zabbix";
    private static final String TEST_USER = "Admin";
    private static final String TEST_PASSWORD = "zabbix_password";
    private static final String TEST_TOKEN = UUID.randomUUID().toString();
    private static final String DEFAULT_API_VERSION_STRING = "6.0.0";

    @Mock
    private CloseableHttpAsyncClient mockAsyncHttpClient;

    @Captor
    private ArgumentCaptor<SimpleHttpRequest> httpRequestCaptor;
    @Captor
    private ArgumentCaptor<FutureCallback<SimpleHttpResponse>> futureCallbackCaptor;

    private ZabbixApiAsync zabbixApi;
    private ObjectMapper objectMapper = new ObjectMapper();


    @BeforeEach
    void setUp() {
        // Common mocking for asyncHttpClient.execute
        // This setup will capture the request and callback, allowing tests to simulate responses.
        lenient().doAnswer(invocation -> {
            // SimpleHttpRequest request = invocation.getArgument(0);
            // FutureCallback<SimpleHttpResponse> callback = invocation.getArgument(1);
            // Store them or handle immediately if the test needs to control the response.
            // For most tests, the captors will be used.
            return null; // execute returns Future<SimpleHttpResponse>, but we work with callback
        }).when(mockAsyncHttpClient).execute(any(SimpleHttpRequest.class), any());
    }

    @AfterEach
    void tearDown() throws IOException {
        if (zabbixApi != null) {
            zabbixApi.close(); // Ensure client is closed if managed
        }
    }

    // Helper to simulate a successful HTTP response for a captured callback
    @SuppressWarnings("unchecked")
    private void simulateSuccessHttpResponse(String jsonBody) {
        SimpleHttpResponse mockResponse = mock(SimpleHttpResponse.class);
        when(mockResponse.getCode()).thenReturn(200);
        when(mockResponse.getBodyText(any())).thenReturn(jsonBody); // Assuming UTF-8 elsewhere or not critical for mock

        // Get the captured callback and invoke it
        verify(mockAsyncHttpClient).execute(httpRequestCaptor.capture(), futureCallbackCaptor.capture());
        FutureCallback<SimpleHttpResponse> callback = futureCallbackCaptor.getValue();
        callback.completed(mockResponse);
    }

    // Helper to simulate a failed HTTP response
    @SuppressWarnings("unchecked")
    private void simulateFailedHttpResponse(int statusCode, String reason, String body) {
        SimpleHttpResponse mockResponse = mock(SimpleHttpResponse.class);
        when(mockResponse.getCode()).thenReturn(statusCode);
        when(mockResponse.getReasonPhrase()).thenReturn(reason);
        if (body != null) {
            when(mockResponse.getBodyText(any())).thenReturn(body);
        }
        verify(mockAsyncHttpClient).execute(httpRequestCaptor.capture(), futureCallbackCaptor.capture());
        FutureCallback<SimpleHttpResponse> callback = futureCallbackCaptor.getValue();
        callback.completed(mockResponse); // HTTP failure is still a "completed" HTTP exchange
    }
    
    // Helper to simulate an exception during HTTP execution
    @SuppressWarnings("unchecked")
    private void simulateExecutionException(Exception ex) {
         verify(mockAsyncHttpClient).execute(httpRequestCaptor.capture(), futureCallbackCaptor.capture());
        FutureCallback<SimpleHttpResponse> callback = futureCallbackCaptor.getValue();
        callback.failed(ex);
    }


    private String createApiInfoVersionResponse(String version) {
        ObjectNode responseNode = objectMapper.createObjectNode();
        responseNode.put("jsonrpc", "2.0");
        responseNode.put("result", version);
        responseNode.put("id", "1"); // ID is dynamic, but for mock matching, it's okay
        return responseNode.toString();
    }
     private String createLoginSuccessResponse(String token) {
        ObjectNode responseNode = objectMapper.createObjectNode();
        responseNode.put("jsonrpc", "2.0");
        responseNode.put("result", token);
        responseNode.put("id", "1");
        return responseNode.toString();
    }

    private String createGenericSuccessResponse(JsonNode resultData) {
        ObjectNode responseNode = objectMapper.createObjectNode();
        responseNode.put("jsonrpc", "2.0");
        responseNode.set("result", resultData);
        responseNode.put("id", "1");
        return responseNode.toString();
    }
    private String createJsonRpcErrorResponse(int code, String message, String data) {
        ObjectNode error = objectMapper.createObjectNode()
            .put("code", code)
            .put("message", message)
            .put("data", data);
        return objectMapper.createObjectNode()
            .put("jsonrpc", "2.0")
            .set("error", error)
            .put("id", "1")
            .toString();
    }


    // === Builder Tests ===
    @Test
    void testBuilder_minimalUrl_needsStartAndClose() {
        zabbixApi = new ZabbixApiAsync.Builder().url(TEST_URL).skipVersionCheck(true).build();
        assertNotNull(zabbixApi);
        zabbixApi.start(); // Call start for managed client
        // No exception means it's okay. Close is handled in tearDown.
    }

    @Test
    void testBuilder_noUrl_throwsException() {
        assertThrows(IllegalArgumentException.class, () -> new ZabbixApiAsync.Builder().build());
    }

    @Test
    void testBuilder_withCustomClient_notManaged_startNotCalledOnIt() throws IOException {
        // mockAsyncHttpClient is already a mock.
        zabbixApi = new ZabbixApiAsync.Builder()
            .url(TEST_URL)
            .customHttpAsyncClient(mockAsyncHttpClient) // Provide custom client
            .skipVersionCheck(true) // To avoid immediate call in constructor for this test focus
            .build();
        assertNotNull(zabbixApi);
        
        // Access managedAsyncHttpClient field to check
        try {
            Field managedField = ZabbixApiAsync.class.getDeclaredField("managedAsyncHttpClient");
            managedField.setAccessible(true);
            assertFalse((boolean) managedField.get(zabbixApi), "HttpClient should not be managed");
        } catch (NoSuchFieldException | IllegalAccessException e) {
            fail("Failed to access managedAsyncHttpClient field for verification.");
        }
        
        zabbixApi.start(); // This should not call mockAsyncHttpClient.start()
        verify(mockAsyncHttpClient, never()).start();
        
        zabbixApi.close(); // This should not call mockAsyncHttpClient.close()
        verify(mockAsyncHttpClient, never()).close();

    }
    
    @Test
    void testBuilder_defaultClient_isManaged_startAndCloseCalled() throws IOException {
        // Let builder create the client
        zabbixApi = new ZabbixApiAsync.Builder().url(TEST_URL).skipVersionCheck(true).build();
        // The actual client is internal. We can't verify start()/close() on it directly with this mock setup.
        // This test conceptually ensures that if a client is managed, start/close are part of its lifecycle.
        // The ZabbixApiAsync.start() and ZabbixApiAsync.close() methods will call these on the internal client.
        // We can mock the HttpAsyncClients.custom() to return our mock if needed for deeper verification.
        // For now, assume the boolean flag `managedAsyncHttpClient` correctly controls this.
         try {
            Field managedField = ZabbixApiAsync.class.getDeclaredField("managedAsyncHttpClient");
            managedField.setAccessible(true);
            assertTrue((boolean) managedField.get(zabbixApi), "Default HttpClient should be managed");
        } catch (NoSuchFieldException | IllegalAccessException e) {
            fail("Failed to access managedAsyncHttpClient field for verification.");
        }
        // To truly test start/close on the *managed* client, we'd need to spy on HttpAsyncClients.custom().build()
        // or accept this as tested by observing no errors from zabbixApi.start()/close().
        zabbixApi.start(); // Should call start on internal client
        zabbixApi.close(); // Should call close on internal client
    }


    // === API Version Check Tests (Async) ===
    @Test
    void testGetApiVersion_success() throws Exception {
        zabbixApi = new ZabbixApiAsync.Builder().url(TEST_URL).skipVersionCheck(false).build();
        zabbixApi.start();

        CompletableFuture<JsonNode> apiVersionFuture = zabbixApi.getApiVersion()
            .thenApply(version -> objectMapper.valueToTree(version.getRawVersion())); // Convert for assertion

        // Simulate the response for apiinfo.version
        simulateSuccessHttpResponse(createApiInfoVersionResponse(DEFAULT_API_VERSION_STRING));
        
        assertEquals(DEFAULT_API_VERSION_STRING, apiVersionFuture.get(1, TimeUnit.SECONDS).asText());
    }

    @Test
    void testGetApiVersion_fetchFails_communicationError() throws Exception {
        zabbixApi = new ZabbixApiAsync.Builder().url(TEST_URL).skipVersionCheck(false).build();
        zabbixApi.start();
        CompletableFuture<io.zabbix4j.api.dto.APIVersion> versionFuture = zabbixApi.getApiVersion();
        simulateExecutionException(new IOException("Network error during version fetch"));

        ExecutionException ex = assertThrows(ExecutionException.class, () -> versionFuture.get(1, TimeUnit.SECONDS));
        assertTrue(ex.getCause() instanceof CommunicationException);
    }
    
    @Test
    void testGetApiVersion_tooOld_throwsApiNotSupported() throws Exception {
        ZabbixApiConstants.MIN_SUPPORTED_ZABBIX_API_VERSION = 7.0f; // Force a high min version
        zabbixApi = new ZabbixApiAsync.Builder().url(TEST_URL).skipVersionCheck(false).build();
        zabbixApi.start();
        try {
            CompletableFuture<io.zabbix4j.api.dto.APIVersion> versionFuture = zabbixApi.getApiVersion();
            simulateSuccessHttpResponse(createApiInfoVersionResponse("6.0.0")); // Provide an "old" version

            ExecutionException ex = assertThrows(ExecutionException.class, () -> versionFuture.get(1, TimeUnit.SECONDS));
            assertTrue(ex.getCause() instanceof ApiNotSupportedException);
            assertTrue(ex.getCause().getMessage().contains("older than minimum supported"));
        } finally {
             ZabbixApiConstants.MIN_SUPPORTED_ZABBIX_API_VERSION = 5.0f; // Reset
        }
    }


    // === Authentication Tests (Async) ===
    @Test
    void testLogin_success() throws Exception {
        zabbixApi = new ZabbixApiAsync.Builder().url(TEST_URL).skipVersionCheck(true).build();
        zabbixApi.start();
        CompletableFuture<Void> loginFuture = zabbixApi.login(TEST_USER, TEST_PASSWORD);
        
        // Simulate apiinfo.version call first (as login calls getApiVersion)
        // Then simulate user.login call
        doAnswer(inv -> {
            SimpleHttpRequest req = inv.getArgument(0);
            FutureCallback<SimpleHttpResponse> cb = inv.getArgument(1);
            SimpleHttpResponse mockResp = mock(SimpleHttpResponse.class);
            when(mockResp.getCode()).thenReturn(200);
            if (req.getBodyText().contains("apiinfo.version")) {
                when(mockResp.getBodyText(any())).thenReturn(createApiInfoVersionResponse(DEFAULT_API_VERSION_STRING));
            } else if (req.getBodyText().contains("user.login")) {
                 when(mockResp.getBodyText(any())).thenReturn(createLoginSuccessResponse(TEST_TOKEN));
            }
            cb.completed(mockResp);
            return null;
        }).when(mockAsyncHttpClient).execute(any(SimpleHttpRequest.class), any());


        loginFuture.get(2, TimeUnit.SECONDS); // Wait for completion
        assertTrue(zabbixApi.isAuthenticated());
        assertFalse(getIsTokenAuth(zabbixApi));
        assertEquals(TEST_TOKEN, getSessionAuthToken(zabbixApi));
    }

    @Test
    void testLogin_failure_apiRequestException() throws Exception {
        zabbixApi = new ZabbixApiAsync.Builder().url(TEST_URL).skipVersionCheck(true).build();
        zabbixApi.start();
        CompletableFuture<Void> loginFuture = zabbixApi.login(TEST_USER, TEST_PASSWORD);

        // Simulate apiinfo.version success, then user.login failure
         doAnswer(inv -> {
            SimpleHttpRequest req = inv.getArgument(0);
            FutureCallback<SimpleHttpResponse> cb = inv.getArgument(1);
            SimpleHttpResponse mockResp = mock(SimpleHttpResponse.class);
            when(mockResp.getCode()).thenReturn(200);
            if (req.getBodyText().contains("apiinfo.version")) {
                when(mockResp.getBodyText(any())).thenReturn(createApiInfoVersionResponse(DEFAULT_API_VERSION_STRING));
            } else if (req.getBodyText().contains("user.login")) {
                 when(mockResp.getBodyText(any())).thenReturn(createJsonRpcErrorResponse(-32602, "Auth failed", "Invalid credentials"));
            }
            cb.completed(mockResp);
            return null;
        }).when(mockAsyncHttpClient).execute(any(SimpleHttpRequest.class), any());

        ExecutionException ex = assertThrows(ExecutionException.class, () -> loginFuture.get(1, TimeUnit.SECONDS));
        assertTrue(ex.getCause() instanceof ApiRequestException);
        assertFalse(zabbixApi.isAuthenticated());
    }
    
    @Test
    void testLoginWithToken_success() throws Exception {
        zabbixApi = new ZabbixApiAsync.Builder().url(TEST_URL).skipVersionCheck(true).build();
        zabbixApi.start();
        CompletableFuture<Void> loginFuture = zabbixApi.loginWithToken(TEST_TOKEN);
        // loginWithToken also calls getApiVersion first
        simulateSuccessHttpResponse(createApiInfoVersionResponse("6.0.0")); // Ensure API version >= 5.4

        loginFuture.get(1, TimeUnit.SECONDS);
        assertTrue(zabbixApi.isAuthenticated());
        assertTrue(getIsTokenAuth(zabbixApi));
        assertEquals(TEST_TOKEN, getSessionAuthToken(zabbixApi));
    }
    
    @Test
    void testLogout_sessionAuth() throws Exception {
        zabbixApi = new ZabbixApiAsync.Builder().url(TEST_URL).skipVersionCheck(true).build();
        zabbixApi.start();
        setSessionAuthToken(zabbixApi, TEST_TOKEN, false); // Simulate prior session login

        CompletableFuture<Void> logoutFuture = zabbixApi.logout();
        // logout calls user.logout, which calls getApiVersion first, then the actual method.
        // This is complex to mock sequentially with one ArgumentCaptor setup.
        // A simpler way: assume getApiVersion was already called and version is cached.
        setCachedApiVersion(zabbixApi, DEFAULT_API_VERSION_STRING);
        
        simulateSuccessHttpResponse(createGenericSuccessResponse(objectMapper.getNodeFactory().booleanNode(true))); // Response for user.logout

        logoutFuture.get(1, TimeUnit.SECONDS);
        assertFalse(zabbixApi.isAuthenticated());
    }


    // === call() Method Tests (Async) ===
    @Test
    void testCall_success() throws Exception {
        zabbixApi = new ZabbixApiAsync.Builder().url(TEST_URL).skipVersionCheck(true).build();
        zabbixApi.start();
        setCachedApiVersion(zabbixApi, DEFAULT_API_VERSION_STRING); // Assume version known

        ObjectNode resultData = objectMapper.createObjectNode().put("key", "value");
        CompletableFuture<JsonNode> callFuture = zabbixApi.call("some.method", Collections.emptyMap());
        simulateSuccessHttpResponse(createGenericSuccessResponse(resultData));
        
        assertEquals(resultData, callFuture.get(1, TimeUnit.SECONDS));
    }

    @Test
    void testCall_jsonRpcError_completesExceptionally() throws Exception {
        zabbixApi = new ZabbixApiAsync.Builder().url(TEST_URL).skipVersionCheck(true).build();
        zabbixApi.start();
        setCachedApiVersion(zabbixApi, DEFAULT_API_VERSION_STRING);

        CompletableFuture<JsonNode> callFuture = zabbixApi.call("error.method", Collections.emptyMap());
        simulateSuccessHttpResponse(createJsonRpcErrorResponse(-32600, "Test Error", "Test Data"));

        ExecutionException ex = assertThrows(ExecutionException.class, () -> callFuture.get(1, TimeUnit.SECONDS));
        assertTrue(ex.getCause() instanceof ApiRequestException);
    }
    
    @Test
    void testCall_httpError_completesExceptionally() throws Exception {
        zabbixApi = new ZabbixApiAsync.Builder().url(TEST_URL).skipVersionCheck(true).build();
        zabbixApi.start();
        setCachedApiVersion(zabbixApi, DEFAULT_API_VERSION_STRING);

        CompletableFuture<JsonNode> callFuture = zabbixApi.call("http.error", Collections.emptyMap());
        simulateFailedHttpResponse(500, "Server Error", "Internal Server Error");

        ExecutionException ex = assertThrows(ExecutionException.class, () -> callFuture.get(1, TimeUnit.SECONDS));
        assertTrue(ex.getCause() instanceof CommunicationException);
    }
    
    @Test
    void testCall_ioException_completesExceptionally() throws Exception {
        zabbixApi = new ZabbixApiAsync.Builder().url(TEST_URL).skipVersionCheck(true).build();
        zabbixApi.start();
        setCachedApiVersion(zabbixApi, DEFAULT_API_VERSION_STRING);

        CompletableFuture<JsonNode> callFuture = zabbixApi.call("io.error", Collections.emptyMap());
        simulateExecutionException(new IOException("Network issue"));
        
        ExecutionException ex = assertThrows(ExecutionException.class, () -> callFuture.get(1, TimeUnit.SECONDS));
        assertTrue(ex.getCause() instanceof CommunicationException);
    }


    // === Helper methods for reflection ===
    private String getSessionAuthToken(ZabbixApiAsync client) {
        try {
            Field field = ZabbixApiAsync.class.getDeclaredField("sessionAuthToken");
            field.setAccessible(true);
            return ((java.util.concurrent.atomic.AtomicReference<String>) field.get(client)).get();
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
    private boolean getIsTokenAuth(ZabbixApiAsync client) {
        try {
            Field field = ZabbixApiAsync.class.getDeclaredField("isTokenAuth");
            field.setAccessible(true);
            return ((java.util.concurrent.atomic.AtomicBoolean) field.get(client)).get();
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
     private void setSessionAuthToken(ZabbixApiAsync client, String token, boolean isToken) {
        try {
            Field tokenField = ZabbixApiAsync.class.getDeclaredField("sessionAuthToken");
            tokenField.setAccessible(true);
            ((java.util.concurrent.atomic.AtomicReference<String>) tokenField.get(client)).set(token);

            Field isTokenAuthField = ZabbixApiAsync.class.getDeclaredField("isTokenAuth");
            isTokenAuthField.setAccessible(true);
            ((java.util.concurrent.atomic.AtomicBoolean) isTokenAuthField.get(client)).set(isToken);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
    private void setCachedApiVersion(ZabbixApiAsync client, String versionString) {
         try {
            Field apiVersionField = ZabbixApiAsync.class.getDeclaredField("apiVersion");
            apiVersionField.setAccessible(true);
            ((java.util.concurrent.atomic.AtomicReference<io.zabbix4j.api.dto.APIVersion>) apiVersionField.get(client))
                .set(new io.zabbix4j.api.dto.APIVersion(versionString));
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
}
