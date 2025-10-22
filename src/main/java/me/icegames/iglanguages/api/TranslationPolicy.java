package me.icegames.iglanguages.api;

import org.bukkit.entity.Player;

/** Return true to translate this key for this player, false to suppress (use defaultLang/raw). */
public interface TranslationPolicy {
    boolean shouldTranslate(Player player, String lang, String category, String key);
}