package dev.personality.listener;

import dev.personality.PersonalityPlugin;
import dev.personality.gui.ProfileGUI;
import dev.personality.util.PlaceholderUtil;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Opens the profile GUI when a player SHIFTS + RIGHT-CLICKS another player.
 *
 * <p>Guards:</p>
 * <ul>
 *   <li>Main hand only</li>
 *   <li>Viewer must be sneaking</li>
 *   <li>Target must be a real {@link Player} (NPC metadata check)</li>
 *   <li>Target must not be masked</li>
 *   <li>Viewer must have {@code playerinfo.use}</li>
 * </ul>
 */
public final class PlayerInteractEntityListener implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final PersonalityPlugin plugin;

    public PlayerInteractEntityListener(PersonalityPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        // ── Guard: main hand only ─────────────────────────────────
        if (event.getHand() != EquipmentSlot.HAND) return;

        // ── Guard: must be sneaking ───────────────────────────────
        Player viewer = event.getPlayer();
        if (!viewer.isSneaking()) return;

        // ── Guard: target must be a real player ───────────────────
        if (!(event.getRightClicked() instanceof Player target)) return;

        // ── Guard: reject Citizens/NPC entities ───────────────────
        if (target.hasMetadata("NPC")) return;

        // ── Guard: permission ─────────────────────────────────────
        if (!viewer.hasPermission("playerinfo.use")) {
            String msg = plugin.getConfig().getString("messages.no-permission", "<red>You don't have permission.");
            viewer.sendMessage(MM.deserialize(msg));
            return;
        }

        // ── Guard: mask check ─────────────────────────────────────
        if (PlaceholderUtil.isMasked(plugin, target)) {
            // Silently cancel — do not send any message.
            event.setCancelled(true);
            return;
        }

        event.setCancelled(true);
        ProfileGUI.open(plugin, viewer, target);
    }
}
