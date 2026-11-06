package dev.josh.myhook;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import dalvik.system.DexFile;

/** Temporary, bounded diagnostics. Calls original methods and never logs argument values. */
final class AdSdkProbe {
    private final MyHook module;
    private final Set<Method> hooked = new HashSet<>();
    private final Set<String> reported = new HashSet<>();
    private int scans;
    private static final String[] ENTRIES = {
        "com.anythink.core.api.ATSDK",
        "com.anythink.interstitial.api.ATInterstitial",
        "com.anythink.nativead.api.ATNative",
        "com.anythink.splashad.api.ATSplashAd",
        "com.windmill.sdk.WindMillAd",
        "com.windmill.sdk.nativead.WMNativeAd",
        "com.windmill.sdk.interstitial.WMInterstitialAd",
        "com.windmill.sdk.splash.WMSplashAd",
        "com.qq.e.comm.managers.GDTAdSdk",
        "com.qq.e.ads.nativ.NativeUnifiedAD",
        "com.qq.e.ads.interstitial2.UnifiedInterstitialAD",
        "com.qq.e.ads.splash.SplashAD",
        "com.bytedance.sdk.openadsdk.TTAdSdk",
        "com.kwad.sdk.api.KsAdSDK",
        "com.baidu.mobads.sdk.api.MobadsPermissionSettings",
        "com.baidu.mobads.sdk.api.SplashAd",
        "com.baidu.mobads.sdk.api.ExpressInterstitialAd",
        "kt.com.fcbox.hiveconsumer.app.business.ad.listener.AdSdkInitInterface$InitCallback",
        "kt.com.fcbox.hiveconsumer.app.business.payment.paysuccess.UnityPaymentAdvertLayout"
    };

    AdSdkProbe(MyHook module) { this.module = module; }

    synchronized void scan(ClassLoader loader, String phase) {
        if (loader == null || scans++ >= 4) return;
        module.info("ADPROBE scan " + phase);
        for (String name : ENTRIES) inspectName(loader, name);
        // Packed apps can append dex to their loader during Application.attach.
        try {
            Object list = field(loader, "pathList");
            Object[] elements = (Object[]) field(list, "dexElements");
            int count = 0;
            for (Object element : elements) {
                DexFile dex = (DexFile) field(element, "dexFile");
                if (dex == null) continue;
                Enumeration<String> names = dex.entries();
                while (names.hasMoreElements()) {
                    String name = names.nextElement();
                    if (name.contains("$") || name.endsWith(".R") || name.contains(".bean.")
                            || name.contains(".entity.") || name.contains(".databinding.") || name.contains(".views.")) continue;
                    if (name.startsWith("com.fcbox.advert.") ||
                            (name.startsWith("kt.com.fcbox.hiveconsumer.") &&
                            (name.contains(".advert.") || name.contains(".splashad.") || name.contains(".business.ad.")))) {
                        if (++count > 160) break;
                        if (reported.add(name)) module.info("ADPROBE class " + name);
                        inspectName(loader, name);
                    }
                }
                if (count > 160) break;
            }
        } catch (Exception | LinkageError e) {
            module.info("ADPROBE enumerate unavailable: " + e.getClass().getSimpleName());
        }
    }

    private static Object field(Object object, String name) throws ReflectiveOperationException {
        for (Class<?> c = object.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(object);
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    private void inspectName(ClassLoader loader, String name) {
        try { inspect(Class.forName(name, false, loader)); }
        catch (ClassNotFoundException ignored) { }
        catch (Exception | LinkageError e) {
            module.info("ADPROBE inspect unavailable " + name + ": " + e.getClass().getSimpleName());
        }
    }

    private synchronized void inspect(Class<?> type) {
        for (Method method : type.getDeclaredMethods()) {
            if ((type.getName().startsWith("kt.com.fcbox.") || type.getName().startsWith("com.fcbox.advert."))
                    && reported.add(method.toString())) module.info("ADPROBE signature " + method.toGenericString());
            String name = method.getName().toLowerCase(java.util.Locale.ROOT);
            boolean business = type.getName().startsWith("kt.com.fcbox.hiveconsumer.app.business.ad.")
                    || type.getName().endsWith(".AppAdvertModel")
                    || type.getName().endsWith(".AdSplashManagerV2")
                    || type.getName().endsWith(".AdRecommendSwitchManager");
            if (!(business || name.contains("init") || name.contains("load") || name.contains("show")
                    || name.contains("request") || name.contains("ready") || name.contains("start")
                    || name.contains("admanager") || name.contains("adnative"))) continue;
            if (Modifier.isAbstract(method.getModifiers()) || method.isSynthetic()) continue;
            if (hooked.size() >= 500 || !hooked.add(method)) continue;
            AtomicInteger calls = new AtomicInteger();
            module.install(method, chain -> {
                int call = calls.incrementAndGet();
                if (call <= 2) {
                    StringBuilder trace = new StringBuilder("ADPROBE call ").append(method.toString());
                    int frames = 0;
                    for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
                        String owner = frame.getClassName();
                        if (owner.startsWith("dev.josh.myhook.") || owner.startsWith("java.lang.Thread")) continue;
                        trace.append("\n  at ").append(frame);
                        if (++frames >= 14) break;
                    }
                    module.info(trace.toString());
                }
                Object result = chain.proceed();
                if (call <= 2 && business) module.info("ADPROBE result " + method
                        + " => " + (result == null ? "null" : result instanceof Boolean ? result : result.getClass().getName()));
                // Factories return concrete SDK implementations, whose load methods can be hooked.
                if (result != null && (name.contains("admanager") || name.contains("adnative"))) {
                    try { inspect(result.getClass()); }
                    catch (Exception | LinkageError e) { module.info("ADPROBE factory inspection failed"); }
                }
                return result;
            });
        }
    }
}
