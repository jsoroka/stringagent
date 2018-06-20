package io.github.jsoroka.stringagent;

import io.javalin.Context;
import io.javalin.Handler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.javalin.Javalin;

import java.io.IOException;
import java.net.URL;

import static org.junit.jupiter.api.Assertions.*;

public class TestAgentMain {

    private Javalin server;
    private int port = 1024 + (int) (Math.random()*30000);

    @BeforeEach
    public void startWebServer() {
        server = Javalin.create().port(port).start();
        server.get("/", new Handler() { @Override public void handle(Context ctx) {
            ctx.header("X-HelloWorld", "Hello World!");
        }});
    }

    @AfterEach
    public void stopWebServer() {
        server.stop();
    }

    @Test
    public void getHelloWorld() throws IOException {
        String message = new URL("http://localhost:" + port + "/").openConnection().getHeaderField("X-HelloWorld");
        assertEquals("Hello World!", message);
    }

    @Test
    public void eachResponseShouldHaveAUniqueId() throws IOException {
        String uniqueId1 = new URL("http://localhost:" + port).openConnection().getHeaderField("X-StringAgent-ID");
        assertNotNull(uniqueId1);
        String uniqueId2 = new URL("http://localhost:" + port).openConnection().getHeaderField("X-StringAgent-ID");
        assertNotEquals(uniqueId1, uniqueId2, "Each response should have a different X-StringAgent-ID");
    }

    @Test
    public void eachResponseShouldHaveANonZeroAllocationCountHeader() throws IOException {
        String allocationCount = new URL("http://localhost:" + port).openConnection().getHeaderField("X-StringAgent-Count");
        assertNotNull(allocationCount, "Each response should have a X-StringAgent-Count value");
        assertNotEquals("0", allocationCount, "String allocation count should probably never be zero?");
    }

}
