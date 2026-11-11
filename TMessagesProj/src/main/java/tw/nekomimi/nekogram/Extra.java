package tw.nekomimi.nekogram;

import org.telegram.messenger.BuildConfig;

import tw.nekomimi.nekogram.helpers.UserHelper;

public class Extra {

    public static int APP_ID = BuildConfig.API_ID != 0 ? BuildConfig.API_ID : 6;
    public static String APP_HASH = (BuildConfig.API_HASH != null && !BuildConfig.API_HASH.isEmpty() && !BuildConfig.API_HASH.equals("null")) ? BuildConfig.API_HASH : "eb06d4abfb49dc3eeb1aeb98ae0f581e";
    public static String SENTRY_DSN = BuildConfig.SENTRY_DSN;

    public static boolean FORCE_ANALYTICS = "play".equals(BuildConfig.BUILD_TYPE);

    private static final UserHelper.BotInfo HELPER_BOT = new UserHelper.BotInfo() {
        @Override
        public long getId() {
            return BuildConfig.HELPER_BOT_ID;
        }

        @Override
        public String getUsername() {
            return BuildConfig.HELPER_BOT_USERNAME;
        }
    };

    public static UserHelper.BotInfo getHelperBot() {
        if (BuildConfig.HELPER_BOT_USERNAME == null) {
            return null;
        }
        return HELPER_BOT;
    }

    public static boolean isDirectApp() {
        return "release".equals(BuildConfig.BUILD_TYPE) || "debug".equals(BuildConfig.BUILD_TYPE);
    }

    public static boolean isTrustedBot(long id) {
        return id == BuildConfig.HELPER_BOT_ID;
    }
}
