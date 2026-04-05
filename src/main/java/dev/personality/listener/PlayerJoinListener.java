package dev.personality.listener;

import dev.personality.PersonalityPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Records each player's first-join timestamp in the database on login.
 *
 * <p>{@code INSERT OR IGNORE} in the database layer means re-joins are no-ops,
 * so we can safely call this every time without an extra Bukkit API check.</p>
 */
public final class PlayerJoinListener implements Listener {

    private final PersonalityPlugin plugin;

    public PlayerJoinListener(PersonalityPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerJoin(PlayerJoinEvent event) {
        var player = event.getPlayer();

        // getFirstPlayed() is set by Bukkit before PlayerJoinEvent fires.
        long firstPlayed = player.getFirstPlayed();
        if (firstPlayed <= 0) firstPlayed = System.currentTimeMillis();

        plugin.getDatabaseManager().insertFirstJoin(player.getUniqueId(), firstPlayed);
    }
}
