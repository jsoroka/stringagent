package io.github.jsoroka.stringagent;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtConstructor;
import javassist.CtMethod;

import java.io.ByteArrayInputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

public class AgentMain implements ClassFileTransformer {

    private static final String SYSPROP = "StringAgent-AllocationCounts";

    public static void agentmain(String args, Instrumentation instrumentation) throws UnmodifiableClassException {
        // register ourselves as a classfile transforming agent
        instrumentation.addTransformer(new AgentMain(), true);

        // apply ourselves to javalin servlet class, if already loaded
        ArrayList<Class<?>> classesToRetransform = new ArrayList<Class<?>>();
        for (Class<?> clazz : instrumentation.getAllLoadedClasses()) {
            if (clazz.getName().equals("io.javalin.core.JavalinServlet") || clazz.getName().equals("java.lang.String")) {
                classesToRetransform.add(clazz);
            }
        }
        if (!classesToRetransform.isEmpty()) {
            instrumentation.retransformClasses(classesToRetransform.toArray(new Class<?>[classesToRetransform.size()]));
        }
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        try {
            ClassPool cp = ClassPool.getDefault();
            if ("io/javalin/core/JavalinServlet".equals(className)) {
                CtClass cc = cp.makeClass(new ByteArrayInputStream(classfileBuffer));
                CtMethod m = cc.getDeclaredMethod("service");
                cp.importPackage("javax.servlet.http");
                cp.importPackage("io.github.jsoroka.stringagent");
                m.insertBefore("AgentMain.clearCount();");
                m.insertAfter("((HttpServletResponse)$2).setHeader(\"X-StringAgent-ID\", \"\" + java.lang.Math.random());");
                m.insertAfter("((HttpServletResponse)$2).setHeader(\"X-StringAgent-Count\", \"\" + AgentMain.getCount());");
                classfileBuffer = cc.toBytecode();
                cc.detach();
            } else if ("java/lang/String".equals(className)) {
                cp.importPackage("java.util.concurrent");
                CtClass cc = cp.makeClass(new ByteArrayInputStream(classfileBuffer));
                for (CtConstructor ctor : cc.getConstructors()) {
                    if (!ctor.callsSuper()) { // don't count ctors that just call sibling ctors
                        continue;
                    }
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
                classfileBuffer = cc.toBytecode();
                cc.detach();
            }
            return classfileBuffer;
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    public static void clearCount() {
        synchronized (System.class) {
            ConcurrentHashMap<Integer,int[]> threadMap = (ConcurrentHashMap)System.getProperties().get(SYSPROP);
            if (threadMap == null) {
                System.getProperties().put(SYSPROP, threadMap = new ConcurrentHashMap<Integer,int[]>());
            }
            int id = System.identityHashCode(Thread.currentThread());
            int[] count = threadMap.get(id);
            if (count == null) {
                threadMap.put(id, count = new int[1]);
            }
            count[0] = 0;
        }
    }

    public static int getCount() {
        synchronized (System.class) {
            ConcurrentHashMap<Integer,int[]> threadMap = (ConcurrentHashMap)System.getProperties().get(SYSPROP);
            if (threadMap == null) {
                System.getProperties().put(SYSPROP, threadMap = new ConcurrentHashMap<Integer,int[]>());
            }
            int id = System.identityHashCode(Thread.currentThread());
            int[] count = threadMap.get(id);
            if (count == null) {
                threadMap.put(id, count = new int[1]);
            }
            return count[0];
        }
    }
}
