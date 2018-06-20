package io.github.jsoroka.stringagent;

import io.javalin.Context;
import io.javalin.Handler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.javalin.Javalin;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.net.URL;

import static org.junit.jupiter.api.Assertions.*;

public class TestAgentMain {

    private Javalin server;
    private int port = 1024 + (int) (Math.random()*30000);

    @BeforeAll
    public static void loadAgent() throws Exception {
        Class<?> vmToolClass = Class.forName("com.sun.tools.attach.VirtualMachine");
        Method vmAttach = vmToolClass.getMethod("attach", String.class);
        String selfPid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
        Object selfVm = vmAttach.invoke(null, selfPid);
        Method loadAgent = vmToolClass.getMethod("loadAgent", String.class, String.class);
        loadAgent.invoke(selfVm, new File("src/test/resources/dummy-agent.jar").getAbsolutePath(), "");
    }

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

    @Test
    public void eachResponseShouldHaveAValidElapsedTimeHeader() throws IOException {
        final int howLongMs = 1000 + (int)(Math.random()*2000);
        server.get("/sleep/:howLongMs", new Handler() { @Override public void handle(Context ctx) throws Exception {
            Thread.sleep(Integer.parseInt(ctx.param("howLongMs")));
        }});
        URL url = new URL("http://localhost:" + port + "/sleep/" + howLongMs);
        long elapsedTimeNanos = Long.parseLong(url.openConnection().getHeaderField("X-StringAgent-Elapsed"));
        int elapsedTimeMs = (int)(elapsedTimeNanos/1000000);
        assertTrue(elapsedTimeMs > howLongMs, "Asked for " + howLongMs + " but only got " + elapsedTimeMs);
        int absoluteError = Math.abs(elapsedTimeMs - howLongMs);
        assertTrue(absoluteError < 500, "Asked for " + howLongMs + " but got " + elapsedTimeMs + ", error of " + absoluteError + " is more than 500ms.");
    }

}
