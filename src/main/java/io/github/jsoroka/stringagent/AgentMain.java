package io.github.jsoroka.stringagent;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;

import java.io.ByteArrayInputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.security.ProtectionDomain;
import java.util.ArrayList;

public class AgentMain implements ClassFileTransformer {

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
                m.insertAfter("((HttpServletResponse)$2).setHeader(\"X-StringAgent-ID\", \"\" + java.lang.Math.random());");
                classfileBuffer = cc.toBytecode();
                cc.detach();
            }
            return classfileBuffer;
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }
}
