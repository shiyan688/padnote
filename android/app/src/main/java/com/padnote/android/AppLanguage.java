package com.padnote.android;

import android.app.LocaleManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;
import android.os.LocaleList;

import java.util.Locale;

/** Per-app language selection, with a stored fallback for Android 12 and earlier. */
final class AppLanguage {
    static final String SYSTEM = "";
    static final String[] TAGS = {SYSTEM, "zh-CN", "en", "ja", "ko", "es", "fr", "de"};
    static final String[] NAMES = {"Follow system", "简体中文", "English", "日本語", "한국어", "Español", "Français", "Deutsch"};
    private static final String PREFS = "padnote-language";
    private static final String KEY = "language-tag";

    private AppLanguage() {}

    static String selectedTag(Context context) {
        if (Build.VERSION.SDK_INT >= 33) {
            LocaleList locales = context.getSystemService(LocaleManager.class).getApplicationLocales();
            return locales.isEmpty() ? SYSTEM : canonical(locales.get(0).toLanguageTag());
        }
        return canonical(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, SYSTEM));
    }

    static int selectedIndex(Context context) {
        String tag = selectedTag(context);
        for (int i = 0; i < TAGS.length; i++) if (TAGS[i].equalsIgnoreCase(tag)) return i;
        return 0;
    }

    static void set(Context context, String tag) {
        String normalized = canonical(tag);
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager.class).setApplicationLocales(
                    normalized.isEmpty() ? LocaleList.getEmptyLocaleList() : LocaleList.forLanguageTags(normalized));
        } else {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (normalized.isEmpty()) prefs.edit().remove(KEY).apply();
            else prefs.edit().putString(KEY, normalized).apply();
        }
    }

    static Context wrap(Context base) {
        if (Build.VERSION.SDK_INT >= 33) return base;
        String tag = selectedTag(base);
        if (tag.isEmpty()) return base;
        Locale locale = Locale.forLanguageTag(tag);
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.setLocale(locale);
        config.setLayoutDirection(locale);
        return base.createConfigurationContext(config);
    }

    private static String canonical(String tag) {
        if (tag == null || tag.trim().isEmpty()) return SYSTEM;
        String language = Locale.forLanguageTag(tag).getLanguage();
        if ("zh".equals(language)) return "zh-CN";
        for (String supported : TAGS) if (!supported.isEmpty() && Locale.forLanguageTag(supported).getLanguage().equals(language)) return supported;
        return SYSTEM;
    }
}
