package dev.josh.myhook;

import android.content.Context;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface.ExceptionMode;

/** Narrow experimental rules verified against runtime signatures in 丰巢 6.48.0. */
final class AdSdkBlocker {
    private static final String BASE = "kt.com.fcbox.hiveconsumer.app.business.ad.";
    private final MyHook module;
    private final Set<Method> installed = new HashSet<>();
    private final Set<String> reportedErrors = new HashSet<>();
    private int attempts;

    AdSdkBlocker(MyHook module) { this.module = module; }

    synchronized void install(ClassLoader loader) {
        if (loader == null || attempts++ >= 4) return;
        for (String adapter : new String[]{"ToponImpl", "TobidImpl", "UbixImpl"}) {
            try { installInit(loader, adapter); }
            catch (Exception | LinkageError e) { unavailable(adapter, e); }
        }
        // These methods accept advertisement candidates and asynchronously deliver result lists.
        // Retain their original scheduling/completion by passing an empty candidate list.
        installBatch(loader, "m", "homebanner.HomeBannerAdClient", "BannerAdvertListener");
        installBatch(loader, "n", "interstital.InterstitalAdClient", "AdvertListener");
        installBatch(loader, "p", "splash.SplashAdClient", "AdvertListener");
    }

    private void installInit(ClassLoader loader, String adapter) throws ReflectiveOperationException {
        Class<?> owner = Class.forName(BASE + "impl." + adapter, false, loader);
        Class<?> callback = Class.forName(BASE + "listener.AdSdkInitInterface$InitCallback", false, loader);
        Class<?> continuation = Class.forName("kotlin.coroutines.Continuation", false, loader);
        Method method = owner.getDeclaredMethod("a", Context.class, callback, continuation);
        if (installed.contains(method)) return;
        // Do not interpret arbitrary suspend functions as Unit or Boolean.
        if (method.getReturnType() != Object.class ||
                !method.getGenericParameterTypes()[2].getTypeName().equals(
                        "kotlin.coroutines.Continuation<? super kotlin.Unit>")) {
            throw new NoSuchMethodException("Unexpected suspend return contract");
        }
        Method failed = callback.getMethod("onFailed", String.class);
        if (failed.getReturnType() != void.class) throw new NoSuchMethodException("onFailed return");
        Object unit = kotlinUnit(loader);
        AtomicInteger hits = new AtomicInteger();
        // Once a callback is delivered, never fall back to initialization and deliver it twice.
        if (module.install(method, ExceptionMode.PASSTHROUGH, chain -> {
            if (hits.incrementAndGet() <= 5) module.info("ADBLOCK hit init " + adapter);
            Object listener = chain.getArg(1);
            if (listener != null) {
                try { failed.invoke(listener, "MyHook: ad SDK initialization disabled"); }
                catch (InvocationTargetException e) { throw e.getCause(); }
            }
            // Complete the suspend function synchronously; do not resume its Continuation too.
            return unit;
        })) {
            installed.add(method);
            module.info("ADBLOCK installed init " + adapter);
        }
    }

    private static Object kotlinUnit(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> unit = Class.forName("kotlin.Unit", true, loader);
        Field singleton = null;
        // R8 renamed INSTANCE in this APK. Resolve the unique typed constant, not its field name.
        for (Field field : unit.getDeclaredFields()) {
            if (field.getType() == unit && Modifier.isStatic(field.getModifiers())
                    && Modifier.isFinal(field.getModifiers())) {
                if (singleton != null) throw new NoSuchFieldException("Ambiguous Unit singleton");
                singleton = field;
            }
        }
        if (singleton == null) throw new NoSuchFieldException("Unit singleton unavailable");
        singleton.setAccessible(true);
        Object value = singleton.get(null);
        if (value == null) throw new NoSuchFieldException("Unit singleton is null");
        return value;
    }

    private void installBatch(ClassLoader loader, String name, String client, String listenerName) {
        try {
            Class<?> owner = Class.forName(BASE + "AppAdSdkManager", false, loader);
            Method method = owner.getDeclaredMethod(name, List.class,
                    Class.forName(BASE + "listener." + listenerName, false, loader),
                    Class.forName("kotlin.jvm.functions.Function1", false, loader));
            if (installed.contains(method)) return;
            String expectedList = "java.util.List<" + BASE + "entity." + client + ">";
            if (method.getReturnType() != void.class ||
                    !method.getGenericParameterTypes()[0].getTypeName().equals(expectedList)) {
                throw new NoSuchMethodException("Unexpected advertisement list contract");
            }
            AtomicInteger hits = new AtomicInteger();
            if (module.install(method, chain -> {
                Object candidates = chain.getArg(0);
                if (!(candidates instanceof List<?>) || ((List<?>) candidates).isEmpty())
                    return chain.proceed();
                Object[] args = chain.getArgs().toArray();
                // A new mutable list avoids mutating the caller's collection.
                args[0] = new ArrayList<>();
                if (hits.incrementAndGet() <= 5) module.info("ADBLOCK hit candidates " + client
                        + "; removed=" + ((List<?>) candidates).size());
                return chain.proceed(args);
            })) {
                installed.add(method);
                module.info("ADBLOCK installed candidates " + client);
            }
        } catch (Exception | LinkageError e) { unavailable("AppAdSdkManager." + name, e); }
    }

    private void unavailable(String target, Throwable error) {
        if (reportedErrors.add(target)) module.info("ADBLOCK unavailable " + target
                + "; " + error.getClass().getSimpleName());
    }
}
