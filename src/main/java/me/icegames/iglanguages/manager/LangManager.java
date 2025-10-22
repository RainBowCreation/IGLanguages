package me.icegames.iglanguages.manager;

import me.icegames.iglanguages.IGLanguages;
import me.icegames.iglanguages.api.TranslationPolicy;
import me.icegames.iglanguages.storage.PlayerLangStorage;
import me.icegames.iglanguages.util.GetLocale;
import me.icegames.iglanguages.util.LangEnum;
import me.icegames.iglanguages.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.*;

/**
 * Loads and serves translations. Categories are inferred from the YAML filename
 * (e.g., ui.yml => category "ui"). A TranslationPolicy (if registered) can
 * allow/deny translating a category per player.
 *
 * Public API behavior is preserved:
 *  - getTranslation(Player, key) → colorized + PlaceholderAPI
 *  - getLangTranslation(lang, key) → colorized, no PlaceholderAPI
 */
public class LangManager {

    private final IGLanguages plugin;
    private final PlayerLangStorage playerLangStorage;
    // Player → language code
    public final Map<UUID, String> playerLang = new HashMap<>();
    private final Map<String, Map<String, String>> translations = new HashMap<>();
    // LRU cache of RAW values (no colors, no PlaceholderAPI): (lang:key) → raw
    private final Map<String, String> translationCache;
    private final String defaultLang;
    // lang → (category → (key → raw))
    private final Map<String, Map<String, Map<String, String>>> byLangByCategory = new HashMap<>();
    // lang → (key → category)
    private final Map<String, Map<String, String>> keyToCategoryByLang = new HashMap<>();
    private TranslationPolicy policy;

    public LangManager(IGLanguages plugin, PlayerLangStorage storage) {
        this.plugin = plugin;
        this.playerLangStorage = storage;
        this.defaultLang = plugin.getConfig().getString("defaultLang");

        int cacheSize = plugin.getConfig().getInt("translationCacheSize", 500);
        this.translationCache = new LinkedHashMap<String, String>(cacheSize, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > cacheSize;
            }
        };
        loadPlayerLanguages();
    }

    public void loadAll() {
        translations.clear();
        byLangByCategory.clear();
        keyToCategoryByLang.clear();

        File langsFolder = new File(plugin.getDataFolder(), "langs");
        if (!langsFolder.exists()) langsFolder.mkdirs();

        File[] langDirs = langsFolder.listFiles(File::isDirectory);
        if (langDirs == null) {
            loadPlayerLanguages();
            return;
        }

        for (File langDir : langDirs) {
            String lang = langDir.getName().toLowerCase();
            if (!LangEnum.isValidCode(lang)) {
                plugin.getLogger().warning("Invalid language folder: " + langDir.getName());
                plugin.getLogger().warning("Please use a valid language code. Available: " + LangEnum.getAllCodes());
                continue;
            }

            Map<String, String> flatLang = new HashMap<>();
            Map<String, Map<String, String>> catMap = new HashMap<>();
            Map<String, String> keyToCat = new HashMap<>();

            File[] files = langDir.listFiles((dir, name) -> name.endsWith(".yml"));
            if (files != null) {
                for (File file : files) {
                    String fname = file.getName();
                    String category = fname.substring(0, fname.length() - 4).toLowerCase(); // strip ".yml"
                    Map<String, String> catFlat = catMap.computeIfAbsent(category, k -> new HashMap<>());

                    YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
                    for (String root : cfg.getKeys(false)) {
                        Object v = cfg.get(root);
                        if (v instanceof ConfigurationSection) {
                            flattenSectionUnderscore((ConfigurationSection) v, root.toLowerCase() + "_", catFlat);
                        } else if (v != null) {
                            catFlat.put(root.toLowerCase(), v.toString());
                        }
                    }

                    // merge into flat index and remember category per key
                    for (Map.Entry<String, String> e : catFlat.entrySet()) {
                        String k = e.getKey().toLowerCase();
                        flatLang.put(k, e.getValue());
                        keyToCat.put(k, category);
                    }
                }
            }

            translations.put(lang, flatLang);
            byLangByCategory.put(lang, catMap);
            keyToCategoryByLang.put(lang, keyToCat);
        }

        // clear raw cache because values may have changed
        clearCache();
        loadPlayerLanguages();
        refreshPolicy();
    }

    private void flattenSectionUnderscore(ConfigurationSection section, String prefix, Map<String, String> out) {
        for (String child : section.getKeys(false)) {
            Object v = section.get(child);
            String key = (prefix + child).toLowerCase();
            if (v instanceof ConfigurationSection) {
                flattenSectionUnderscore((ConfigurationSection) v, key + "_", out);
            } else if (v != null) {
                out.put(key, String.valueOf(v));
            }
        }
    }

    public String getDefaultLang() {
        return defaultLang;
    }

    public List<String> getAvailableLangs() {
        return new ArrayList<>(translations.keySet());
    }

    public int getTotalTranslationsCount() {
        int total = 0;
        for (Map<String, String> langMap : translations.values()) {
            total += langMap.size();
        }
        return total;
    }

    public List<String> getAvailableCategories(String lang) {
        Map<String, Map<String, String>> m = byLangByCategory.get(lang.toLowerCase());
        if (m == null) return Collections.emptyList();
        return new ArrayList<>(m.keySet());
    }

    public Map<String, Map<String, Map<String, String>>> getByLangByCategory() {
        return byLangByCategory;
    }

    public String getPlayerLang(UUID id) {
        String lang = playerLang.get(id);
        return (lang != null) ? lang : defaultLang;
    }

    public boolean hasPlayerLang(UUID uuid) {
        return playerLangStorage.hasPlayerLang(uuid);
    }

    public void setPlayerLang(UUID id, String lang) {
        if (lang == null) return;
        playerLang.put(id, lang.toLowerCase());
    }

    public void savePlayerLang(UUID uuid) {
        String lang = playerLang.get(uuid);
        if (lang != null) playerLangStorage.savePlayerLang(uuid, lang);
        plugin.LogDebug("Saved player language " + lang);
    }

    public void loadPlayerLanguages() {
        try {
            Map<UUID, String> loaded = playerLangStorage.loadAll();
            playerLang.clear();
            for (Map.Entry<UUID, String> e : loaded.entrySet()) {
                if (e.getValue() != null) playerLang.put(e.getKey(), e.getValue().toLowerCase());
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load player languages: " + e.getMessage());
        }
    }

    private void refreshPolicy() {
        try {
            org.bukkit.plugin.RegisteredServiceProvider<TranslationPolicy> rsp =
                    Bukkit.getServicesManager().getRegistration(TranslationPolicy.class);
            policy = (rsp != null) ? rsp.getProvider() : null;
        } catch (Throwable t) {
            policy = null;
        }
    }

    private String categoryOf(String lang, String keyLower) {
        Map<String, String> m = keyToCategoryByLang.get(lang);
        if (m != null) {
            String c = m.get(keyLower);
            if (c != null) return c;
        }
        m = keyToCategoryByLang.get(defaultLang);
        return (m != null) ? m.getOrDefault(keyLower, "uncategorized") : "uncategorized";
    }

    private String colorize(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    private boolean hasPlaceholderAPI() {
        return Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
    }

    // Get RAW (no colors, no placeholders), from maps only
    private String getRaw(String lang, String keyLower) {
        Map<String, String> langMap = translations.getOrDefault(lang, Collections.<String, String>emptyMap());
        Map<String, String> defMap  = translations.getOrDefault(defaultLang, Collections.<String, String>emptyMap());
        String v = langMap.get(keyLower);
        return (v != null) ? v : defMap.get(keyLower);
    }

    public String getTranslation(Player player, String key) {
        String lang = getPlayerLang(player.getUniqueId());
        String keyLower = key.toLowerCase();
        String cacheKey = lang + ":" + keyLower;

        // 1) fetch RAW from cache or maps
        String raw = translationCache.get(cacheKey);
        if (raw == null) {
            raw = getRaw(lang, keyLower);
            if (raw != null) translationCache.put(cacheKey, raw);
        }

        // 2) consult policy (per-player, per-category)
        if (policy == null) refreshPolicy();
        if (policy != null) {
            boolean allow = true;
            try {
                allow = policy.shouldTranslate(player, lang, categoryOf(lang, keyLower), keyLower);
            } catch (Throwable t) {
                plugin.getLogger().warning("TranslationPolicy threw: " + t.getMessage());
            }
            if (!allow) {
                String fallback = lang.equalsIgnoreCase(defaultLang) ? raw : getRaw(defaultLang, keyLower);
                raw = (fallback != null) ? fallback : null;
            }
        }

        // 3) not found path (kept behavior)
        if (raw == null) {
            String nf = MessageUtil.getMessage(plugin.getMessagesConfig(), "translation_not_found", "{key}", key);
            String colored = colorize(nf);
            return hasPlaceholderAPI()
                    ? me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, colored)
                    : colored;
        }

        // 4) format for the player
        String formatted = colorize(raw);
        if (hasPlaceholderAPI()) {
            formatted = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, formatted);
        }
        return formatted;
    }

    public String getLangTranslation(String lang, String key) {
        String keyLower = key.toLowerCase();
        String cacheKey = lang.toLowerCase() + ":" + keyLower;

        String raw = translationCache.get(cacheKey);
        if (raw == null) {
            raw = getRaw(lang.toLowerCase(), keyLower);
            if (raw == null) {
                String nf = MessageUtil.getMessage(plugin.getMessagesConfig(), "translation_not_found", "{key}", key);
                return colorize(nf);
            }
            translationCache.put(cacheKey, raw);
        }
        return colorize(raw);
    }

    public String detectClientLanguage(Player player) {
        java.util.Optional<String> maybeLocale = GetLocale.resolveLocaleStr(player);
        if (!maybeLocale.isPresent()) {
            return defaultLang;
        }
        String lang = maybeLocale.get();
        List<String> availableLangs = getAvailableLangs();
        if (availableLangs.contains(lang)) {
            return lang;
        }
        return defaultLang;
    }

    public void clearCache() {
        translationCache.clear();
    }
}