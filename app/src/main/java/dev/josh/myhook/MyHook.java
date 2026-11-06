package dev.josh.myhook;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.View;
import android.view.Window;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import io.github.libxposed.api.XposedModule;

/** API 102 module; deliberately does not use legacy XposedBridge APIs. */
public final class MyHook extends XposedModule {
    // Enable only for a local diagnostic build; normal builds avoid dex enumeration and stack dumps.
    private static final boolean SDK_DIAGNOSTICS = false;
    private String processName;
    private boolean installed;
    private final Set<String> reportedDrawables = new HashSet<>();

    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        processName = param.getProcessName();
    }

    @Override public void onPackageReady(PackageReadyParam param) {
        if (installed || !LaunchPolicy.PACKAGE.equals(param.getPackageName())
                || !LaunchPolicy.PACKAGE.equals(processName)) return;
        installed = true;
        info("Loaded API=" + getApiVersion() + "; target=" + LaunchPolicy.PACKAGE);
        AdSdkProbe probe = SDK_DIAGNOSTICS ? new AdSdkProbe(this) : null;
        AdSdkBlocker blocker = new AdSdkBlocker(this);
        try {
            install(Application.class.getDeclaredMethod("attach", Context.class), chain -> {
                Object result = chain.proceed();
                Context context = (Context) chain.getArg(0);
                if (LaunchPolicy.PACKAGE.equals(context.getPackageName())) {
                    blocker.install(context.getClassLoader());
                    if (probe != null) probe.scan(context.getClassLoader(), "Application.attach");
                }
                return result;
            });
        } catch (ReflectiveOperationException e) { error("SDK attach hook unavailable", e); }

        // Framework methods remain available even when the app's dex is unpacked later.
        for (Method method : Instrumentation.class.getDeclaredMethods()) {
            if (method.getName().equals("execStartActivity")) installStartHook(method);
            if (method.getName().equals("callActivityOnCreate")
                    || method.getName().equals("callActivityOnResume")) {
                Class<?>[] types = method.getParameterTypes();
                if (types.length > 0 && types[0] == Activity.class) {
                    install(method, chain -> {
                        Activity activity = (Activity) chain.getArg(0);
                        boolean target = LaunchPolicy.PACKAGE.equals(activity.getPackageName());
                        if (target) {
                            if (method.getName().equals("callActivityOnCreate")) {
                                blocker.install(activity.getClass().getClassLoader());
                                if (probe != null) probe.scan(activity.getClass().getClassLoader(), "Activity.create");
                            }
                            info(method.getName() + " " + activity.getClass().getName());
                            cleanStartupWindow(activity);
                        }
                        // Never swallow the target application's exceptions or call it twice.
                        Object result = chain.proceed();
                        if (target) cleanStartupWindow(activity);
                        return result;
                    });
                }
            }
        }

        try {
            install(Resources.class.getDeclaredMethod("getDrawableForDensity",
                    int.class, int.class, Resources.Theme.class), chain -> {
                Drawable replacement = startupDrawable((Resources) chain.getThisObject(),
                        (Integer) chain.getArg(0));
                return replacement != null ? replacement : chain.proceed();
            });
            // Window backgrounds are commonly loaded through TypedArray, not Resources.getDrawable.
            install(TypedArray.class.getDeclaredMethod("getDrawable", int.class), chain -> {
                TypedArray array = (TypedArray) chain.getThisObject();
                Drawable replacement = startupDrawable(array.getResources(),
                        array.getResourceId((Integer) chain.getArg(0), 0));
                return replacement != null ? replacement : chain.proceed();
            });
        } catch (ReflectiveOperationException e) {
            error("Drawable hook unavailable", e);
        }
    }

    private void installStartHook(Method method) {
        Class<?>[] types = method.getParameterTypes();
        int intentAt = -1;
        int activityAt = -1;
        for (int i = 0; i < types.length; i++) {
            if (types[i] == Intent.class) intentAt = i;
            if (types[i] == Activity.class) activityAt = i;
        }
        // Only intercept the Activity caller overload with an explicit requestCode.
        if (intentAt < 0 || activityAt < 0 || intentAt + 1 >= types.length
                || types[intentAt + 1] != int.class) return;
        final int intentIndex = intentAt;
        final int sourceIndex = activityAt;
        install(method, chain -> {
            Object[] replacement = null;
            try {
                Activity source = (Activity) chain.getArg(sourceIndex);
                Intent intent = (Intent) chain.getArg(intentIndex);
                ComponentName component = intent == null ? null : intent.getComponent();
                if (source != null && component != null
                        && LaunchPolicy.PACKAGE.equals(source.getPackageName())
                        && LaunchPolicy.PACKAGE.equals(component.getPackageName())) {
                    String from = source.getClass().getName();
                    String to = component.getClassName();
                    int requestCode = (Integer) chain.getArg(intentIndex + 1);
                    if (LaunchPolicy.isStartup(from) || LaunchPolicy.isSplash(to)) {
                        // Log class names only; no URIs, extras, login tokens or user data.
                        info("Route " + from + " -> " + to + "; requestCode=" + requestCode);
                    }
                    if (LaunchPolicy.canRedirect(from, component.getPackageName(), to,
                            requestCode, intent.getData() != null, intent.getSelector() != null)) {
                        // Verify the destination can be resolved before changing the request.
                        source.getPackageManager().getActivityInfo(
                                new ComponentName(LaunchPolicy.PACKAGE, LaunchPolicy.MAIN), 0);
                        // Fresh intent: ad payloads must not become homepage commands.
                        Intent home = new Intent().setClassName(LaunchPolicy.PACKAGE, LaunchPolicy.MAIN)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        replacement = chain.getArgs().toArray();
                        replacement[intentIndex] = home;
                        info("Redirect " + to + " -> MainActivity");
                    }
                }
            } catch (Exception e) {
                error("Route inspection failed; retaining original intent", e);
            }
            return replacement == null ? chain.proceed() : chain.proceed(replacement);
        });
    }

    private Drawable startupDrawable(Resources resources, int id) {
        if (id == 0) return null;
        try {
            if (!LaunchPolicy.PACKAGE.equals(resources.getResourcePackageName(id))) return null;
            String name = resources.getResourceEntryName(id);
            if (!LaunchPolicy.isStartupDrawable(name)) return null;
            synchronized (reportedDrawables) {
                if (reportedDrawables.add(name)) info("Replace drawable " + name);
            }
            return new ColorDrawable("bg_launcher".equals(name) ? Color.WHITE : Color.TRANSPARENT);
        } catch (Resources.NotFoundException ignored) {
            return null;
        }
    }

    private void cleanStartupWindow(Activity activity) {
        if (!LaunchPolicy.isStartup(activity.getClass().getName())) return;
        try {
            Window window = activity.getWindow();
            if (window != null) window.setBackgroundDrawable(new ColorDrawable(Color.WHITE));
            for (String name : new String[]{"iv_logo_splash", "iv_logo", "ivBg"}) {
                int id = activity.getResources().getIdentifier(name, "id", LaunchPolicy.PACKAGE);
                View view = id == 0 ? null : activity.findViewById(id);
                if (view != null) view.setVisibility(View.GONE);
            }
        } catch (Exception e) {
            error("Startup window cleanup failed", e);
        }
    }

    boolean install(Method method, Hooker hooker) {
        return install(method, ExceptionMode.PROTECTIVE, hooker);
    }

    boolean install(Method method, ExceptionMode mode, Hooker hooker) {
        try {
            hook(method).setExceptionMode(mode).intercept(hooker);
            info("Hooked " + method.getDeclaringClass().getSimpleName() + "." + method.getName());
            return true;
        } catch (Exception | LinkageError e) {
            error("Cannot hook " + method.getName(), e);
            return false;
        }
    }

    void info(String message) { log(Log.INFO, "MyHook", message); }
    private void error(String message, Throwable error) { log(Log.ERROR, "MyHook", message, error); }
}
