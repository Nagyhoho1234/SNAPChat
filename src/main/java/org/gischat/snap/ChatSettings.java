package org.gischat.snap;

import java.util.prefs.Preferences;

public class ChatSettings {
    private static final Preferences PREFS = Preferences.userRoot().node("gischat");

    public static LlmProvider getProvider() {
        String val = PREFS.get("provider", LlmProvider.Anthropic.name());
        try {
            return LlmProvider.valueOf(val);
        } catch (IllegalArgumentException e) {
            return LlmProvider.Anthropic;
        }
    }

    public static void setProvider(LlmProvider p) {
        PREFS.put("provider", p.name());
    }

    public static String getApiKey() {
        return PREFS.get("apiKey", "");
    }

    public static void setApiKey(String key) {
        PREFS.put("apiKey", key);
    }

    public static String getModel() {
        return PREFS.get("model", "claude-sonnet-4-6");
    }

    public static void setModel(String model) {
        PREFS.put("model", model);
    }

    public static String getEndpoint() {
        return PREFS.get("endpoint", "");
    }

    public static void setEndpoint(String url) {
        PREFS.put("endpoint", url);
    }

    public static String getEffectiveEndpoint() {
        String ep = getEndpoint();
        return (ep != null && !ep.isBlank()) ? ep : getProvider().defaultEndpoint;
    }

    public static int getMaxTokens() {
        return PREFS.getInt("maxTokens", 4096);
    }

    public static void setMaxTokens(int v) {
        PREFS.putInt("maxTokens", v);
    }

    public static boolean getConfirmBeforeExecute() {
        return PREFS.getBoolean("confirmBeforeExecute", true);
    }

    public static void setConfirmBeforeExecute(boolean v) {
        PREFS.putBoolean("confirmBeforeExecute", v);
    }

    public static boolean getShowGeneratedCode() {
        return PREFS.getBoolean("showGeneratedCode", true);
    }

    public static void setShowGeneratedCode(boolean v) {
        PREFS.putBoolean("showGeneratedCode", v);
    }
}
