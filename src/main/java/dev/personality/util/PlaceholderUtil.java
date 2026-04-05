package dev.personality.util;

import dev.personality.PersonalityPlugin;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * Wrapper around PlaceholderAPI that gracefully handles the case where
 * PlaceholderAPI is not installed (soft-depend).
 */
public final class PlaceholderUtil {

    private PlaceholderUtil() {}

    // ── PAPI presence check ───────────────────────────────────────

    private static boolean hasPapi(PersonalityPlugin plugin) {
        return plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI");
    }

    // ── Placeholder resolution ────────────────────────────────────

    /**
     * Resolves a placeholder for an online {@link Player}.
     *
     * @param fallback value returned when PAPI is absent or the placeholder did not resolve
     */
    public static String resolve(PersonalityPlugin plugin, Player player, String placeholder, String fallback) {
        if (player == null || !hasPapi(plugin)) return fallback;
        String result = PlaceholderAPI.setPlaceholders(player, placeholder);
        if (result == null || result.equals(placeholder) || result.isBlank()) return fallback;
        return result;
    }

    /**
     * Resolves a placeholder for an {@link OfflinePlayer}.
     * Works with expansions that implement offline support (e.g. DiscordSRV).
     *
     * @param fallback value returned when PAPI is absent or the placeholder did not resolve
     */
    public static String resolveOffline(PersonalityPlugin plugin, OfflinePlayer player,
                                         String placeholder, String fallback) {
        if (player == null || !hasPapi(plugin)) return fallback;
        String result = PlaceholderAPI.setPlaceholders(player, placeholder);
        if (result == null || result.equals(placeholder) || result.isBlank()) return fallback;
        return result;
    }

    // ── Mask detection ────────────────────────────────────────────

    /**
     * Returns {@code true} if the given online player is considered "masked" and their
     * profile should not be opened.
     *
     * <p>Detection order (both checks are cumulative — either match = masked):</p>
     * <ol>
     *   <li>Bukkit metadata key (configurable)</li>
     *   <li>PlaceholderAPI placeholder returning the configured masked value</li>
     * </ol>
     */
    public static boolean isMasked(PersonalityPlugin plugin, Player target) {
        if (plugin.getConfig().getBoolean("mask.check-metadata", true)) {
            String key = plugin.getConfig().getString("mask.metadata-key", "masked");
            if (target.hasMetadata(key)) return true;
        }

        if (plugin.getConfig().getBoolean("mask.use-placeholder", false)) {
            String ph       = plugin.getConfig().getString("mask.placeholder", "");
            String expected = plugin.getConfig().getString("mask.placeholder-masked-value", "true");
            String result   = resolve(plugin, target, ph, "");
            if (result.equalsIgnoreCase(expected)) return true;
        }

        return false;
    }
}
