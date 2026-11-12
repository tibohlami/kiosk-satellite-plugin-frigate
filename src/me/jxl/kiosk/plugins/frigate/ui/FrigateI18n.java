// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.frigate.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import me.jxl.kiosk.plugins.PluginHost;
import me.jxl.kiosk.plugins.frigate.config.FrigateConfig;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Standard Android strings.xml i18n loader for Kiosk Satellite plugins.
 * Reads {@code assets/values/strings.xml} (default/English) and
 * {@code assets/values-fr/strings.xml} (French) via {@link PluginHost#openAsset(String)},
 * using the tablet's default Locale unless overridden in plugin settings.
 */
public final class FrigateI18n {

    private static volatile PluginHost activeHost;
    private static volatile boolean assetsLoaded = false;
    private static volatile Map<String, String> defaultStrings = Collections.emptyMap();
    private static volatile Map<String, String> frStrings = Collections.emptyMap();

    private FrigateI18n() {}

    public static void init(PluginHost host) {
        if (host != null) {
            activeHost = host;
            assetsLoaded = false;
            ensureLoaded(host);
        }
    }

    private static synchronized void ensureLoaded(PluginHost hostHint) {
        if (assetsLoaded) return;
        PluginHost h = (hostHint != null) ? hostHint : activeHost;
        Map<String, String> defMap = loadStringsAsset(h, "values/strings.xml");
        Map<String, String> frMap = loadStringsAsset(h, "values-fr/strings.xml");
        if (!defMap.isEmpty() || !frMap.isEmpty()) {
            defaultStrings = Collections.unmodifiableMap(defMap);
            frStrings = Collections.unmodifiableMap(frMap);
            assetsLoaded = (h != null);
        }
    }

    private static Map<String, String> loadStringsAsset(PluginHost host, String assetPath) {
        Map<String, String> map = new HashMap<>();
        try (InputStream is = openAssetStream(host, assetPath)) {
            if (is == null) return map;
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(is);
            NodeList nodes = doc.getElementsByTagName("string");
            for (int i = 0; i < nodes.getLength(); i++) {
                Element el = (Element) nodes.item(i);
                String name = el.getAttribute("name");
                if (name != null && !name.isEmpty()) {
                    map.put(name, unescapeAndroidXml(el.getTextContent()));
                }
            }
        } catch (Throwable ignored) {}
        return map;
    }

    private static InputStream openAssetStream(PluginHost host, String assetPath) {
        if (host != null) {
            try {
                return host.openAsset(assetPath);
            } catch (Exception ignored) {}
        }
        // Fallback for local JVM unit tests outside Kiosk Satellite host
        try {
            File local = new File("assets/" + assetPath);
            if (local.isFile()) {
                return new FileInputStream(local);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String unescapeAndroidXml(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length()) {
                char next = raw.charAt(i + 1);
                if (next == 'n') {
                    sb.append('\n');
                    i++;
                    continue;
                } else if (next == 't') {
                    sb.append('\t');
                    i++;
                    continue;
                } else if (next == '\'' || next == '"' || next == '\\') {
                    sb.append(next);
                    i++;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    public static Locale getSystemLocale(Context context) {
        if (context != null) {
            try {
                Configuration cfg = context.getResources().getConfiguration();
                if (cfg != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        if (!cfg.getLocales().isEmpty()) {
                            return cfg.getLocales().get(0);
                        }
                    } else {
                        @SuppressWarnings("deprecation")
                        Locale loc = cfg.locale;
                        if (loc != null) return loc;
                    }
                }
            } catch (Throwable ignored) {}
        }
        Locale def = Locale.getDefault();
        return def != null ? def : Locale.US;
    }

    /**
     * Resolves active two-letter language code ("fr" or "en") using plugin override or tablet locale.
     */
    public static String resolveLanguageCode(Context context, FrigateConfig config) {
        String override = (config != null) ? config.getLanguage() : FrigateConfig.DEFAULT_LANGUAGE;
        if ("fr".equalsIgnoreCase(override)) {
            return "fr";
        }
        if ("en".equalsIgnoreCase(override)) {
            return "en";
        }
        Locale sys = getSystemLocale(context);
        String lang = sys.getLanguage();
        if (lang != null && lang.toLowerCase(Locale.ROOT).startsWith("fr")) {
            return "fr";
        }
        return "en";
    }

    /**
     * Returns the effective Locale for date formatting and locale-sensitive casing.
     */
    public static Locale getEffectiveLocale(Context context, FrigateConfig config) {
        Locale sys = getSystemLocale(context);
        String override = (config != null) ? config.getLanguage() : FrigateConfig.DEFAULT_LANGUAGE;
        if ("fr".equalsIgnoreCase(override)) {
            if (sys.getLanguage() != null && sys.getLanguage().toLowerCase(Locale.ROOT).startsWith("fr")) {
                return sys;
            }
            return Locale.FRANCE;
        }
        if ("en".equalsIgnoreCase(override)) {
            if (sys.getLanguage() != null && sys.getLanguage().toLowerCase(Locale.ROOT).startsWith("en")) {
                return sys;
            }
            return Locale.US;
        }
        return sys;
    }

    public static String tr(Context context, FrigateConfig config, String key, Object... args) {
        PluginHost h = (config != null && config.getHost() != null) ? config.getHost() : activeHost;
        ensureLoaded(h);
        String lang = resolveLanguageCode(context, config);
        String template = null;
        if ("fr".equals(lang)) {
            template = frStrings.get(key);
        }
        if (template == null) {
            template = defaultStrings.get(key);
        }
        if (template == null) {
            template = key;
        }
        if (args != null && args.length > 0) {
            try {
                return String.format(getEffectiveLocale(context, config), template, args);
            } catch (Exception ignored) {}
        }
        return template;
    }

    public static String translateObjectLabel(Context context, FrigateConfig config, String raw) {
        if (raw == null || raw.isEmpty()) {
            return tr(context, config, "object_generic");
        }
        String key;
        switch (raw.toLowerCase(Locale.ROOT)) {
            case "person":
                key = "object_person";
                break;
            case "car":
                key = "object_car";
                break;
            case "vehicle":
                key = "object_vehicle";
                break;
            case "truck":
                key = "object_truck";
                break;
            case "motorcycle":
                key = "object_motorcycle";
                break;
            case "dog":
                key = "object_dog";
                break;
            case "cat":
                key = "object_cat";
                break;
            case "bird":
                key = "object_bird";
                break;
            case "package":
                key = "object_package";
                break;
            case "bicycle":
                key = "object_bicycle";
                break;
            default:
                Locale loc = getEffectiveLocale(context, config);
                return raw.substring(0, 1).toUpperCase(loc) + raw.substring(1);
        }
        return tr(context, config, key);
    }
}
