package io.zabbix4j.api;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class PlaceholderTest {

    @Test
    public void testSayHello() {
        Placeholder placeholder = new Placeholder();
        String expected = "Hello, Zabbix4J!";
        String actual = placeholder.sayHello();
        assertEquals(expected, actual, "The sayHello method should return the correct greeting.");
    }
}
