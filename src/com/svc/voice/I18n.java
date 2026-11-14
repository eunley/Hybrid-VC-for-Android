package com.svc.voice;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import java.util.Locale;

/** App-only locale override. First launch follows the system; explicit user selections are preserved. */
public final class I18n {
    private I18n() {}

    /** Stable preference values, in the same order as LANGUAGE_LABEL_KEYS. */
    public static final String[] LANGUAGE_CODES = {"zh-CN", "zh-TW", "en", "fr", "de", "it", "ja", "ko", "pt", "ru", "es", "system"};
    public static final String[] LANGUAGE_LABEL_KEYS = {"lang_zh", "lang_zh_tw", "lang_en", "lang_fr", "lang_de", "lang_it", "lang_ja", "lang_ko", "lang_pt", "lang_ru", "lang_es", "lang_auto"};

    public static String text(Context c,int id,Object... args) {
        return wrap(c).getResources().getString(id,args);
    }

    public static String[] languageCodes() { return LANGUAGE_CODES.clone(); }

    public static String[] languageLabels(Context c) {
        return new String[] {"简体中文 · Chinese (Simplified)", "繁體中文 · Chinese (Traditional)", "English · English", "Français · French", "Deutsch · German", "Italiano · Italian", "日本語 · Japanese", "한국어 · Korean", "Português · Portuguese", "Русский · Russian", "Español · Spanish", c.getString(R.string.lang_auto)};
    }

    public static int languageIndex(Context c) {
        String code = c.getSharedPreferences("svc", Context.MODE_PRIVATE).getString("lang", "system");
        if ("auto".equals(code)) code = "system";
        if (code != null && !"system".equals(code)) {
            String tag = code.replace('_', '-');
            if ("zhHans".equalsIgnoreCase(tag)) tag = "zh-Hans";
            if ("zhHant".equalsIgnoreCase(tag)) tag = "zh-Hant";
            code = supportedLocale(Locale.forLanguageTag(tag)).toLanguageTag();
        }
        for (int i = 0; i < LANGUAGE_CODES.length; i++) {
            if (LANGUAGE_CODES[i].equalsIgnoreCase(code)) return i;
        }
        return 2;
    }

    public static String languageLabel(Context c) {
        return languageLabels(c)[languageIndex(c)];
    }

    public static Locale pick(Context c) {
        String mode = "system";
        try {
            mode = c.getSharedPreferences("svc", Context.MODE_PRIVATE).getString("lang", "system");
        } catch (Throwable ignore) {}
        if ("system".equalsIgnoreCase(mode) || "auto".equalsIgnoreCase(mode)) {
            Configuration cf = Resources.getSystem().getConfiguration();
            Locale device;
            if (Build.VERSION.SDK_INT >= 24) {
                device = cf.getLocales().isEmpty() ? Locale.ENGLISH : cf.getLocales().get(0);
            } else {
                device = cf.locale == null ? Locale.ENGLISH : cf.locale;
            }
            return supportedLocale(device);
        }
        if (mode == null || mode.isEmpty()) return Locale.ENGLISH;
        String tag = mode.replace('_', '-');
        if ("zhHans".equalsIgnoreCase(tag)) tag = "zh-Hans";
        if ("zhHant".equalsIgnoreCase(tag)) tag = "zh-Hant";
        return supportedLocale(Locale.forLanguageTag(tag));
    }

    /** Script takes precedence over region, including zh-Hans-TW and zh-Hant-CN. */
    public static Locale supportedLocale(Locale locale) {
        if (locale == null) return Locale.ENGLISH;
        String language = locale.getLanguage();
        if ("zh".equalsIgnoreCase(language)) {
            String script = locale.getScript();
            if ("Hant".equalsIgnoreCase(script)) return Locale.TRADITIONAL_CHINESE;
            if ("Hans".equalsIgnoreCase(script)) return Locale.SIMPLIFIED_CHINESE;
            String region = locale.getCountry();
            return "TW".equalsIgnoreCase(region) || "HK".equalsIgnoreCase(region)
                    || "MO".equalsIgnoreCase(region)
                    ? Locale.TRADITIONAL_CHINESE : Locale.SIMPLIFIED_CHINESE;
        }
        for (String code : LANGUAGE_CODES) {
            if (code.equals(language) && !"system".equals(code)) return Locale.forLanguageTag(code);
        }
        return Locale.ENGLISH;
    }

    public static Context wrap(Context base) {
        try {
            Locale loc = pick(base);
            Configuration cf = new Configuration(base.getResources().getConfiguration());
            cf.setLocale(loc);
            cf.setLayoutDirection(loc);
            return base.createConfigurationContext(cf);
        } catch (Throwable t) {
            return base;
        }
    }
}
