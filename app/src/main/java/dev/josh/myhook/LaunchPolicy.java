package dev.josh.myhook;

/** Targets are taken from the supplied 6.45.0 manifest, not guessed method names. */
public final class LaunchPolicy {
    public static final String PACKAGE = "com.fcbox.hiveconsumer";
    public static final String PREFIX = "kt.com.fcbox.hiveconsumer.app.business.";
    public static final String ENTRANCE = PREFIX + "entrance.EntranceDelegateActivity";
    public static final String MAIN = PREFIX + "main.MainActivity";

    private LaunchPolicy() {}

    public static boolean isSplash(String name) {
        return (PREFIX + "splashad.SplashActivity").equals(name)
                || (PREFIX + "advert.GDTSplashActivity").equals(name)
                || (PREFIX + "advert.XWSplashActivity").equals(name);
    }

    public static boolean canRedirect(String source, String targetPackage,
                                      String destination, int requestCode,
                                      boolean hasData, boolean hasSelector) {
        // Keep URI routing, onboarding, and startActivityForResult contracts intact.
        return PACKAGE.equals(targetPackage) && isSplash(destination)
                && (ENTRANCE.equals(source) || MAIN.equals(source) || isSplash(source))
                && requestCode < 0 && !hasData && !hasSelector;
    }

    public static boolean isStartup(String name) {
        return ENTRANCE.equals(name) || isSplash(name);
    }

    public static boolean isStartupDrawable(String name) {
        return "bg_launcher".equals(name) || "bg_entrance".equals(name)
                || "bg_entrance_logo".equals(name);
    }
}
