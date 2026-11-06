package dev.josh.myhook;

public final class LaunchPolicyTest {
    public static void main(String[] args) {
        String splash = LaunchPolicy.PREFIX + "splashad.SplashActivity";
        check(LaunchPolicy.canRedirect(LaunchPolicy.ENTRANCE, LaunchPolicy.PACKAGE, splash, -1, false, false), "cold launch");
        check(LaunchPolicy.canRedirect(LaunchPolicy.MAIN, LaunchPolicy.PACKAGE, splash, -1, false, false), "warm launch");
        check(!LaunchPolicy.canRedirect(LaunchPolicy.ENTRANCE, "another.package", splash, -1, false, false), "other package");
        check(!LaunchPolicy.canRedirect(LaunchPolicy.ENTRANCE, LaunchPolicy.PACKAGE, splash, 42, false, false), "result contract");
        check(!LaunchPolicy.canRedirect(LaunchPolicy.ENTRANCE, LaunchPolicy.PACKAGE, splash, -1, true, false), "deep link");
        check(!LaunchPolicy.canRedirect(LaunchPolicy.ENTRANCE, LaunchPolicy.PACKAGE, splash, -1, false, true), "selector");
        check(!LaunchPolicy.canRedirect(LaunchPolicy.PREFIX + "login.LoginActivity", LaunchPolicy.PACKAGE, splash, -1, false, false), "login");
        check(!LaunchPolicy.canRedirect(LaunchPolicy.ENTRANCE, LaunchPolicy.PACKAGE, LaunchPolicy.PREFIX + "entrance.EntranceGuideActivity", -1, false, false), "onboarding");
        check(!LaunchPolicy.isSplash(LaunchPolicy.PREFIX + "advert.AdvertActivity"), "unconfirmed advert purpose");
        check(!LaunchPolicy.isStartupDrawable("home_banner"), "homepage image");
        check(LaunchPolicy.isStartupDrawable("bg_launcher"), "startup background");
        check(!LaunchPolicy.isStartup(LaunchPolicy.MAIN), "main window retained");
        System.out.println("PASS: 12 launch-policy checks");
    }
    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
    }
}
