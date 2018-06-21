package io.github.jsoroka.stringagent;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtConstructor;
import javassist.CtMethod;

import java.io.ByteArrayInputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class AgentMain implements ClassFileTransformer {

    private static final boolean ON_BOOTCLASSPATH = AgentMain.class.getClassLoader() == null;

    private static final String SYSPROP = "StringAgent-AllocationCounts";

    private static ConcurrentHashMap<String,Set<String>> CODESOURCES = new ConcurrentHashMap<String,Set<String>>();

    public static void premain(java.lang.String args, Instrumentation instrumentation) throws UnmodifiableClassException {
        agentmain(args, instrumentation);
    }

    public static void agentmain(String args, Instrumentation instrumentation) throws UnmodifiableClassException {
        if (!ON_BOOTCLASSPATH) { /* TODO warn about degraded performance, give diagnosis and suggested fixes */ }

        // register ourselves as a classfile transforming agent
        instrumentation.addTransformer(new AgentMain(), true);

        // apply ourselves to javalin servlet class, if already loaded
        ArrayList<Class<?>> classesToRetransform = new ArrayList<Class<?>>();
        for (Class<?> clazz : instrumentation.getAllLoadedClasses()) {
            noteClassLoad(clazz.getProtectionDomain().getCodeSource(), clazz.getName(), clazz.getMethods().length);

            if (clazz.getName().equals("io.javalin.core.JavalinServlet") || clazz.getName().equals("java.lang.String")) {
                classesToRetransform.add(clazz);
            }
        }
        if (!classesToRetransform.isEmpty()) {
            instrumentation.retransformClasses(classesToRetransform.toArray(new Class<?>[classesToRetransform.size()]));
        }
    }

    // keep track of number of loaded jars, classes and methods
    private static void noteClassLoad(CodeSource codeSource, String className, int classMethodCount) {
        if (codeSource != null) {
            Set<String> newValue = Collections.synchronizedSet(new HashSet<String>());
            Set<String> value = CODESOURCES.putIfAbsent(codeSource.getLocation().toString(), newValue);
            if (value == null)
                value = newValue;
            value.add(className + '@' + classMethodCount);
        }
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        try {
            ClassPool cp = ClassPool.getDefault();
            if (classBeingRedefined == null) {
                CtClass cc = cp.makeClass(new ByteArrayInputStream(classfileBuffer));
                noteClassLoad(protectionDomain.getCodeSource(), cc.getName(), cc.getMethods().length);
                cc.detach();
            }

            if ("io/javalin/core/JavalinServlet".equals(className)) {
                CtClass cc = cp.makeClass(new ByteArrayInputStream(classfileBuffer));
                CtMethod m = cc.getDeclaredMethod("service");
                cp.importPackage("javax.servlet.http");
                cp.importPackage("io.github.jsoroka.stringagent");
                m.insertBefore("AgentMain.clearCount();");
                m.insertBefore("AgentMain.startTimer();");
                m.insertAfter("((HttpServletResponse)$2).setHeader(\"X-StringAgent-ID\", \"\" + java.lang.Math.random());");
                m.insertAfter("((HttpServletResponse)$2).setHeader(\"X-StringAgent-Count\", \"\" + AgentMain.getCount());");
                m.insertAfter("((HttpServletResponse)$2).setHeader(\"X-StringAgent-Elapsed\", \"\" + AgentMain.stopTimer());");
                m.insertAfter("((HttpServletResponse)$2).setHeader(\"X-StringAgent-JarsLoaded\", \"\" + AgentMain.getJarsLoaded());");
                m.insertAfter("((HttpServletResponse)$2).setHeader(\"X-StringAgent-ClassesLoaded\", \"\" + AgentMain.getClassesLoaded());");
                m.insertAfter("((HttpServletResponse)$2).setHeader(\"X-StringAgent-MethodsLoaded\", \"\" + AgentMain.getMethodsLoaded());");
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
                        ctor.insertAfter("io.github.jsoroka.stringagent.AgentMain.incrementCount();");
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
            throw new AssertionError(t);
        }
    }

    private static ThreadLocal<int[]> threadLocalAllocationCount = new ThreadLocal<int[]>();

    public static void clearCount() {
        setCount(0);
    }

    public static void incrementCount() {
        setCount(getCount() + 1);
    }

    private static void setCount(int newCount) {
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
        count[0] = newCount;
    }

    public static int getCount() {
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
        return count[0];
    }

    private static ThreadLocal<Long> threadLocalTimer = new ThreadLocal<Long>();

    public static void startTimer() {
        threadLocalTimer.set(System.nanoTime());
    }

    public static long stopTimer() {
        return System.nanoTime() - threadLocalTimer.get();
    }

    public static int getJarsLoaded() {
        return CODESOURCES.size();
    }

    public static int getClassesLoaded() {
        int result = 0;
        ConcurrentHashMap<String,Set<String>> snapshot = new ConcurrentHashMap<String, Set<String>>(CODESOURCES);
        for (Set<String> classes : snapshot.values())
            result += classes.size();
        return result;
    }

    public static int getMethodsLoaded() {
        int result = 0;
        ConcurrentHashMap<String,Set<String>> snapshot = new ConcurrentHashMap<String, Set<String>>(CODESOURCES);
        for (Set<String> classes : snapshot.values()) {
            for (String classNameAndMethodCount : classes) {
                String methodCount = classNameAndMethodCount.substring(classNameAndMethodCount.indexOf('@') + 1);
                result += Integer.parseInt(methodCount);
            }
        }
        return result;
    }
}
