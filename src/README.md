# Zabbix4j API Client

## Introduction
This library is a Java client for interacting with the Zabbix monitoring system. It is a conversion of the Python `zabbix_utils` library, providing similar functionalities for Java applications. It supports Zabbix API versions from 5.0 to 7.2 (as defined in `Version.java`) and requires Java 11 or later. Author: CSJ.

## Features
*   Synchronous and Asynchronous Zabbix API clients (`ZabbixApi`, `AsyncZabbixApi`) using Java's built-in HttpClient.
*   Synchronous and Asynchronous Zabbix Sender (`Sender`, `AsyncSender`) for sending trapper items.
*   Synchronous and Asynchronous Zabbix Getter (`Getter`, `AsyncGetter`) for retrieving item values directly from agents.
*   Support for Zabbix JSON-RPC API and Zabbix binary protocols (Sender/Getter).
*   Fluent Builder pattern for easy configuration and instantiation of clients.
*   Parsing of Zabbix agent configuration files for Sender settings.
*   Logging via SLF4J, with a Logback `PatternLayoutConverter` (`SensitiveDataConverter`) for sanitizing sensitive data in logs.
*   Comprehensive Javadoc.

## Requirements
*   Java 11 or higher.
*   Maven 3.6+ or Gradle 6+ (for building/dependency management).

## Installation

### Maven
```xml
<dependency>
    <groupId>io.zabbix4j.api</groupId>
    <artifactId>zabbix-java-api</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### Gradle
```gradle
implementation 'io.zabbix4j.api:zabbix-java-api:1.0.0-SNAPSHOT'
```

## Usage Examples

### 1. ZabbixApi (Synchronous Client)

**Initialization:**
```java
ZabbixApi zabbixApi = ZabbixApi.builder()
    .url("http://your-zabbix-server/zabbix/") // Or use ZabbixApiUtils.checkUrl()
    .timeout(30, TimeUnit.SECONDS)
    .build();
```

**Login with User/Password:**
```java
try {
    // Assumes MIN_SUPPORTED_ZABBIX_API and MAX_SUPPORTED_ZABBIX_API are suitable for login method
    zabbixApi.loginWithUserPassword("Admin", "zabbix");
    System.out.println("Login successful. Auth token: " + zabbixApi.getAuthToken());
} catch (ZabbixApiException e) {
    System.err.println("Login failed: " + e.getMessage());
}
```

**Login with Token (Zabbix 5.4+):**
```java
try {
    // Ensure API version is >= 5.4 before using token auth directly
    if (zabbixApi.getApiVersion().isGreaterThanOrEqualTo("5.4.0")) {
        zabbixApi.loginWithToken("your_api_token_here");
        System.out.println("Login with token successful.");
    } else {
        System.out.println("Token authentication requires Zabbix API 5.4+");
    }
} catch (ZabbixApiException e) {
    System.err.println("Token login failed: " + e.getMessage());
}
```

**Making an API Call (e.g., Get Hosts):**
```java
try {
    Map<String, Object> params = new HashMap<>();
    params.put("output", "extend");
    params.put("selectInterfaces", "extend");
    JSONArray hosts = zabbixApi.hostService().get(params);
    System.out.println("Hosts: " + hosts.toString(2)); // Pretty print
} catch (ZabbixApiException e) {
    System.err.println("API call failed: " + e.getMessage());
}
```

**Logout:**
```java
try {
    if (zabbixApi.getAuthToken() != null) { // Check if logged in
        zabbixApi.logout();
        System.out.println("Logout successful.");
    }
} catch (ZabbixApiException e) {
    System.err.println("Logout failed: " + e.getMessage());
}
```

### 2. AsyncZabbixApi (Asynchronous Client)

**Initialization:**
```java
AsyncZabbixApi asyncZabbixApi = AsyncZabbixApi.builder()
    .url("http://your-zabbix-server/zabbix/")
    .build();
```

**Login and API Call (Chained):**
```java
asyncZabbixApi.loginWithUserPassword("Admin", "zabbix")
    .thenCompose(authToken -> {
        System.out.println("Async login successful. Auth token: " + authToken);
        Map<String, Object> params = new HashMap<>();
        params.put("output", "extend");
        return asyncZabbixApi.hostService().get(params);
    })
    .thenAccept(hosts -> System.out.println("Async Hosts: " + hosts.toString(2)))
    .exceptionally(ex -> {
        System.err.println("Async operation failed: " + ex.getCause().getMessage());
        return null;
    })
    .thenCompose(v -> { // Logout after operations
        if (asyncZabbixApi.getAuthToken() != null) {
             return asyncZabbixApi.logout();
        }
        return CompletableFuture.completedFuture(null);
    })
    .join(); // Wait for completion in this example
```

### 3. Sender (Synchronous)

**Initialization:**
```java
// To specific server
Sender sender = Sender.builder().server("your-zabbix-server", 10051).build();

// Or from agent configuration file
// Sender senderFromConfig = Sender.builder()
//    .agentConfigPath("/etc/zabbix/zabbix_agentd.conf")
//    .build();
```

**Sending Data:**
```java
ItemValue item1 = new ItemValue("TestHost", "item.key1", "123");
ItemValue item2 = new ItemValue("TestHost", "item.key2", "hello", System.currentTimeMillis() / 1000L, null);
List<ItemValue> items = Arrays.asList(item1, item2);

try {
    TrapperResponse response = sender.send(items);
    System.out.println("Sender Response: " + response);
} catch (ZabbixProcessingException e) {
    System.err.println("Failed to send items: " + e.getMessage());
}
```

### 4. AsyncSender (Asynchronous)
```java
AsyncSender asyncSender = AsyncSender.builder().server("your-zabbix-server", 10051).build();
List<ItemValue> asyncItems = Arrays.asList(new ItemValue("AsyncHost", "async.key", "data"));

asyncSender.send(asyncItems)
    .thenAccept(response -> System.out.println("AsyncSender Response: " + response))
    .exceptionally(ex -> {
        System.err.println("Async send failed: " + ex.getCause().getMessage());
        return null;
    })
    .join();
```

### 5. Getter (Synchronous)
```java
Getter getter = new Getter("zabbix-agent-host", 10050);
try {
    AgentResponse response = getter.get("agent.ping");
    if (response.hasError()) {
        System.err.println("Getter error: " + response.getError());
    } else {
        System.out.println("agent.ping: " + response.getValue());
    }
} catch (ZabbixProcessingException e) {
    System.err.println("Getter failed: " + e.getMessage());
}
```

### 6. AsyncGetter (Asynchronous)
```java
AsyncGetter asyncGetter = new AsyncGetter("zabbix-agent-host", 10050);
asyncGetter.get("agent.version")
    .thenAccept(response -> {
        if (response.hasError()) {
            System.err.println("AsyncGetter error: " + response.getError());
        } else {
            System.out.println("agent.version: " + response.getValue());
        }
    })
    .exceptionally(ex -> {
        System.err.println("AsyncGetter failed: " + ex.getCause().getMessage());
        return null;
    })
    .join();
```

### Logging
This library uses SLF4J for logging. You can include your preferred SLF4J binding (e.g., Logback, Log4j2) in your project.
To enable sanitization of sensitive data in logs with Logback, you can register the `SensitiveDataConverter`:
In your `logback.xml`:
```xml
<configuration>
    <conversionRule conversionWord="mask" converterClass="io.zabbix4j.api.logging.SensitiveDataConverter" />

    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <!-- Use %mask instead of %m or %message for sensitive data masking -->
            <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %mask%n</pattern>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="STDOUT" />
    </root>

    <!-- Example: Set specific logger level for this library -->
    <logger name="io.zabbix4j.api" level="DEBUG"/>
</configuration>
```

### Building from Source
```bash
git clone <repository-url> # Replace <repository-url> with the actual URL
cd zabbix-java-api       # Or the project's root directory name
mvn clean install
```

### License
This library is released under the MIT License.
```
