package io.github.jsoroka.stringagent;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtConstructor;
import javassist.CtMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.energyit.statsd.FastStatsDClient;
import tech.energyit.statsd.StatsDClient;
import tech.energyit.statsd.SynchronousSender;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class AgentMain implements ClassFileTransformer {

    private static Logger logger = LoggerFactory.getLogger(AgentMain.class);

    private static StatsDClient statsd = null;
    private static final byte[] STATSD_ELAPSED = "StringAgent-Elapsed".getBytes();
    private static final byte[] STATSD_COUNT = "StringAgent-Count".getBytes();
    private static final byte[] STATSD_HELLO = "StringAgent-Hello".getBytes();

    private static final boolean ON_BOOTCLASSPATH = AgentMain.class.getClassLoader() == null;

    private static final String SYSPROP = "StringAgent-AllocationCounts";

    private static ConcurrentHashMap<String,Set<String>> CODESOURCES = new ConcurrentHashMap<String,Set<String>>();

    private static ThreadLocal<Long> threadLocalTimer = new ThreadLocal<Long>();
    private static ThreadLocal<Long> threadLocalRequestId = new ThreadLocal<Long>();
    private static ThreadLocal<int[]> threadLocalAllocationCount = new ThreadLocal<int[]>();

    public static void premain(java.lang.String args, Instrumentation instrumentation) throws UnmodifiableClassException {
        agentmain(args, instrumentation);
    }

    public static void agentmain(String args, Instrumentation instrumentation) throws UnmodifiableClassException {
        if (!ON_BOOTCLASSPATH) { /* TODO warn about degraded performance, give diagnosis and suggested fixes */ }

        Class[] allLoadedClasses = instrumentation.getAllLoadedClasses();

        for (String arg : (args+"").split(";")) {
            if (arg.startsWith("statsd=")) {
                String hostname = arg.substring("statsd=".length(), arg.indexOf(':'));
                int port = Integer.parseInt(arg.substring(args.indexOf(':') + 1));
                statsd = new FastStatsDClient(new SynchronousSender(hostname, port));
                // send one value to statsd immediately, to ensure that specified hostname:port is valid.
                statsd.gauge(STATSD_HELLO, allLoadedClasses.length);
            }
        }

        // register ourselves as a classfile transforming agent
        instrumentation.addTransformer(new AgentMain(), true);

        // apply ourselves to javalin servlet class, if already loaded
        ArrayList<Class<?>> classesToRetransform = new ArrayList<Class<?>>();
        for (Class<?> clazz : allLoadedClasses) {
            noteClassLoad(clazz.getProtectionDomain(), clazz.getName(), clazz.getMethods().length);

            if (clazz.getName().equals("io.javalin.core.JavalinServlet") || clazz.getName().equals("java.lang.String")) {
                classesToRetransform.add(clazz);
            }
        }
        if (!classesToRetransform.isEmpty()) {
            instrumentation.retransformClasses(classesToRetransform.toArray(new Class<?>[classesToRetransform.size()]));
        }
    }

    // keep track of number of loaded jars, classes and methods
    private static void noteClassLoad(ProtectionDomain protectionDomain, String className, int classMethodCount) {
        String codeSource = "";
        if (protectionDomain != null && protectionDomain.getCodeSource() != null) {
            codeSource = protectionDomain.getCodeSource().getLocation().toString();
        }
        Set<String> newValue = Collections.synchronizedSet(new HashSet<String>());
        Set<String> value = CODESOURCES.putIfAbsent(codeSource, newValue);
        if (value == null)
            value = newValue;
        value.add(className + '@' + classMethodCount);
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        try {
            ClassPool cp = ClassPool.getDefault();
            if (classBeingRedefined == null) {
                CtClass cc = cp.makeClass(new ByteArrayInputStream(classfileBuffer));
                noteClassLoad(protectionDomain, cc.getName(), cc.getMethods().length);
                cc.detach();
            }

            if ("io/javalin/core/JavalinServlet".equals(className)) {
                CtClass cc = cp.makeClass(new ByteArrayInputStream(classfileBuffer));
                CtMethod m = cc.getDeclaredMethod("service");
                cp.importPackage("javax.servlet.http");
                cp.importPackage("io.github.jsoroka.stringagent");
                m.insertBefore("AgentMain.beforeRequest($0, (HttpServletRequest)$1, (HttpServletResponse)$2);");
                m.insertAfter("AgentMain.afterRequest($0, (HttpServletRequest)$1, (HttpServletResponse)$2);", true);
                classfileBuffer = cc.toBytecode();
                cc.detach();
            } else if ("java/lang/String".equals(className)) {
                cp.importPackage("java.util.concurrent");
                CtClass cc = cp.makeClass(new ByteArrayInputStream(classfileBuffer));
                for (CtConstructor ctor : cc.getConstructors()) {
                    if (!ctor.callsSuper()) { // don't count ctors that just call sibling ctors
                        continue;
                    }
                    if (ON_BOOTCLASSPATH) {
                        ctor.insertAfter("io.github.jsoroka.stringagent.AgentMain.getStringAllocationCount()[0]++;");
                    } else {
                        ctor.insertAfter("synchronized (System.class) { " +
                            "ConcurrentHashMap threadMap = (ConcurrentHashMap)" +
                            "    System.getProperties().get(\"" + SYSPROP + "\");" +
                            "if (threadMap == null) {" +
                            "    threadMap = new ConcurrentHashMap();" +
                            "    System.getProperties().put(\"" + SYSPROP +"\", threadMap);" +
                            "}" +
                            "Integer id = Integer.valueOf(System.identityHashCode(Thread.currentThread()));" +
                            "int[] count = (int[])threadMap.get(id);" +
                            "if (count == null) { threadMap.put(id, count = new int[1]); }" +
                            "count[0]++;" +
                        "}", true);
                    }
                }
                classfileBuffer = cc.toBytecode();
                cc.detach();
            }
            return classfileBuffer;
        } catch (Throwable t) {
            t.printStackTrace();
            throw new AssertionError(t);
        }
    }

    private static int[] getStringAllocationCount() {
        int[] count;
        if (ON_BOOTCLASSPATH) {
            count = threadLocalAllocationCount.get();
            if (count == null) {
                threadLocalAllocationCount.set(count = new int[1]);
            }
        } else synchronized (System.class) {
            ConcurrentHashMap<Integer,int[]> threadMap = (ConcurrentHashMap)System.getProperties().get(SYSPROP);
            if (threadMap == null) {
                System.getProperties().put(SYSPROP, threadMap = new ConcurrentHashMap<Integer,int[]>());
            }
            int id = System.identityHashCode(Thread.currentThread());
            count = threadMap.get(id);
            if (count == null) {
                threadMap.put(id, count = new int[1]);
            }
        }
        return count;
    }

    public static int getJarsLoaded() {
        return CODESOURCES.size();
    }

    public static int getClassesLoaded() {
        int result = 0;
        for (Set<String> classes : CODESOURCES.values())
            result += classes.size();
        return result;
    }

    public static int getMethodsLoaded() {
        int result = 0;
        for (Set<String> classes : CODESOURCES.values()) {
            for (String classNameAndMethodCount : classes) {
                String methodCount = classNameAndMethodCount.substring(classNameAndMethodCount.indexOf('@') + 1);
                result += Integer.parseInt(methodCount);
            }
        }
        return result;
    }

    public static void beforeRequest(Object thiz, HttpServletRequest request, HttpServletResponse response) {
        threadLocalTimer.set(System.nanoTime());
        long requestId = System.currentTimeMillis()<<32 + (int)(Integer.MAX_VALUE * Math.random());
        threadLocalRequestId.set(requestId);
        request.setAttribute("X-StringAgent-ID", requestId);
        getStringAllocationCount()[0] = 0;
    }

    public static void afterRequest(Object thiz, HttpServletRequest request, HttpServletResponse response) {
        int numberOfStringAllocations = getStringAllocationCount()[0];
        long elapsedTime = System.nanoTime() - threadLocalTimer.get();
        long requestId = threadLocalRequestId.get();
        logger.debug(requestId + ": " + numberOfStringAllocations + ", " + elapsedTime);
        if (statsd != null) {
            statsd.time(STATSD_ELAPSED, elapsedTime/1000000);
            statsd.gauge(STATSD_COUNT, numberOfStringAllocations);
        }
        response.setHeader("X-StringAgent-ID", "" + requestId);
        response.setHeader("X-StringAgent-Count", "" + numberOfStringAllocations);
        response.setHeader("X-StringAgent-Elapsed", "" + elapsedTime);
        response.setHeader("X-StringAgent-JarsLoaded", "" + getJarsLoaded());
        response.setHeader("X-StringAgent-ClassesLoaded", "" + getClassesLoaded());
        response.setHeader("X-StringAgent-MethodsLoaded", "" + getMethodsLoaded());
    }
}
