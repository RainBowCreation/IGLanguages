package me.icegames.iglanguages.api;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.jetbrains.annotations.Nullable;

/**
 * Optional advanced hooks. All methods are synchronous and MUST be fast (no I/O).
 * Higher priority runs earlier.
 */
public interface TranslationExtension {
    /** Extenstion Name */
    String name();

    /** Plugin Instance */
    Plugin plugin();

    /** Larger runs earlier. Use 0 as default. */
    default int priority() { return 0; }

    /** Allow remapping a key before lookup (e.g., "balance" -> "economy_balance"). */
    default String mapKey(Player player, String lang, String category, String key) { return key; }

    /** Gate translation (ALWAYS_ALLOW wins over ALWAYS_DENY? see below). */
    default Gate gate(Player player, String lang, String category, String key) { return Gate.DEFER; }

    /** Override the raw value (pre-color, pre-placeholders). Return PASS to keep YAML result. */
    default OverrideResult override(Player player, String lang, String category, String key, @Nullable String currentRaw) {
        return OverrideResult.pass();
    }

    /** Transform raw text before color codes / PlaceholderAPI. */
    default String preFormat(Player player, String lang, String category, String key, String raw) { return raw; }

    /** Transform after color + PlaceholderAPI (final text). */
    default String postFormat(Player player, String lang, String category, String key, String formatted) { return formatted; }

    enum Gate { ALWAYS_ALLOW, ALWAYS_DENY, DEFER }

    final class OverrideResult {
        public final boolean handled;
        public final String value;       // raw (no colors, no placeholders)
        public final boolean cacheable;  // whether to cache this raw text

        private OverrideResult(boolean handled, String value, boolean cacheable) {
            this.handled = handled; this.value = value; this.cacheable = cacheable;
        }
        public static OverrideResult pass() { return new OverrideResult(false, null, true); }
        public static OverrideResult of(String raw, boolean cacheable) { return new OverrideResult(true, raw, cacheable); }
    }

    default void register() {
        Bukkit.getServicesManager().register(TranslationExtension.class, this, plugin(), ServicePriority.Normal);
        System.out.println("\\u001B[1;30m[\\u001B[0m\\u001B[36mI\\u001B[1;36mG\\u001B[0m\\u001B[1;37m\" + pluginName + \"\\u001B[1;30m]\\u001B[0m Registered TranslatorExtension: " + name());
        try {
            me.icegames.iglanguages.IGLanguages.getInstance().getLangManager().refreshExtensions();
        }
        catch (Exception ignored) {}
    }

    default void unregister() {
        try {
            Bukkit.getServicesManager().unregister(TranslationExtension.class, this);
            me.icegames.iglanguages.IGLanguages.getInstance().getLangManager().refreshExtensions();
        } catch (Exception ignored) {}
    }
}
