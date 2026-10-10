package com.embers.packmod;

import net.minecraft.client.resources.language.I18n;
import net.minecraft.locale.Language;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves client labels using Minecraft's currently selected language. */
final class ClientText {
    private static final Pattern STATE = Pattern.compile("^(.*?)\\s+\\[(ON|OFF|unavailable)\\]$");
    private static final Pattern NON_SLUG = Pattern.compile("[^a-z0-9]+");
    private static final Pattern SLUG_EDGES = Pattern.compile("^_+|_+$");
    private static final String[] PREFIXES = {"Shape: ", "Default color: ", "Player color: ",
            "Hostile color: ", "Friendly color: ", "Other color: "};
    private static final BoundedCache<String, String> LABELS = new BoundedCache<>(1024);
    private static Language language;

    private ClientText() {}

    static String get(String english) {
        if (english == null || english.isEmpty()) return english;
        Language current = Language.getInstance();
        if (language != current) {
            LABELS.clear();
            language = current;
        }
        String cached = LABELS.get(english);
        if (cached != null) return cached;
        // The resolver recurses, so compute outside computeIfAbsent.
        String resolved = resolve(english);
        LABELS.put(english, resolved);
        return resolved;
    }

    private static String resolve(String english) {
        if (english.startsWith("• ")) return "• " + get(english.substring(2));
        if (english.endsWith("     ›")) return get(english.substring(0, english.length() - 6)) + "  ›";
        Matcher state = STATE.matcher(english);
        if (state.matches()) return get(state.group(1)) + "  [" + get(state.group(2)) + "]";
        if (english.startsWith("<  ")) return "<  " + get(english.substring(3));
        for (String prefix : PREFIXES) {
            if (english.startsWith(prefix)) return get(prefix.trim()) + " " + get(english.substring(prefix.length()));
        }
        if (english.startsWith("BORDER  ")) return get("BORDER") + "  " + get(english.substring(8));
        if (english.startsWith("BRACKETS  ")) return get("BRACKETS") + "  " + get(english.substring(10));
        String slug = SLUG_EDGES.matcher(NON_SLUG.matcher(english.toLowerCase(Locale.ROOT))
                .replaceAll("_")).replaceAll("");
        if (slug.isEmpty()) return english;
        String key = "packmod.ui." + slug;
        String translated = I18n.get(key);
        return key.equals(translated) ? english : translated;
    }
}
