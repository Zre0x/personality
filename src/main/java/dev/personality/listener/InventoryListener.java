package dev.personality.listener;

import dev.personality.PersonalityPlugin;
import dev.personality.gui.PersonalityHolder;
import dev.personality.gui.ProfileGUI;
import dev.personality.gui.ReputationGUI;
import dev.personality.manager.ReputationManager.VoteResult;
import dev.personality.model.VoteType;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

import java.util.UUID;

/**
 * Central handler for all clicks and drags inside plugin-owned inventories.
 *
 * <p>The holder type ({@link PersonalityHolder.Type}) drives which action to take.
 * Every click inside a plugin inventory is cancelled regardless of slot to prevent
 * item theft. Only clicks in the top inventory (the plugin GUI) are dispatched.</p>
 */
public final class InventoryListener implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final PersonalityPlugin plugin;

    public InventoryListener(PersonalityPlugin plugin) {
        this.plugin = plugin;
    }

    // ── Click ─────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!(event.getInventory().getHolder() instanceof PersonalityHolder holder)) return;

        // Cancel ALL clicks — including those in the player's own bottom inventory —
        // to prevent any item movement.
        event.setCancelled(true);

        // Do not dispatch clicks that occurred outside the plugin GUI (e.g. player inventory row).
        Inventory clicked = event.getClickedInventory();
        if (clicked == null || !clicked.equals(event.getInventory())) return;

        int slot = event.getSlot();

        switch (holder.getType()) {
            case PROFILE    -> handleProfileClick(player, holder, slot);
            case REPUTATION -> handleReputationClick(player, holder, slot, event.getInventory());
            case TOP        -> handleTopClick(player, slot);
        }
    }

    // ── Drag ──────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof PersonalityHolder) {
            event.setCancelled(true);
        }
    }

    // ── Profile ───────────────────────────────────────────────────

    private void handleProfileClick(Player player, PersonalityHolder holder, int slot) {
        int repBtnSlot = plugin.getConfig().getInt("gui.profile-slots.reputation-button", 22);
        if (slot != repBtnSlot) return;

        UUID targetUuid = holder.getTargetUuid();
        if (targetUuid == null) return;

        OfflinePlayer target = Bukkit.getOfflinePlayer(targetUuid);
        ReputationGUI.open(plugin, player, target);
    }

    // ── Reputation ────────────────────────────────────────────────

    private void handleReputationClick(Player player, PersonalityHolder holder,
                                        int slot, Inventory inv) {
        UUID targetUuid = holder.getTargetUuid();
        if (targetUuid == null) return;

        int likeSlot    = plugin.getConfig().getInt("gui.reputation-slots.like-button",    11);
        int dislikeSlot = plugin.getConfig().getInt("gui.reputation-slots.dislike-button", 15);
        int backSlot    = plugin.getConfig().getInt("gui.reputation-slots.back-button",    22);

        if (slot == backSlot) {
            OfflinePlayer target = Bukkit.getOfflinePlayer(targetUuid);
            ProfileGUI.open(plugin, player, target);
            return;
        }

        VoteType vote = null;
        if (slot == likeSlot)    vote = VoteType.LIKE;
        if (slot == dislikeSlot) vote = VoteType.DISLIKE;
        if (vote == null) return;

        final VoteType finalVote = vote;
        plugin.getReputationManager()
                .castVote(player.getUniqueId(), targetUuid, finalVote)
                .thenAccept(result -> Bukkit.getScheduler().runTask(plugin, () ->
                        handleVoteResult(player, inv, targetUuid, finalVote, result)));
    }

    private void handleVoteResult(Player player, Inventory inv, UUID targetUuid,
                                   VoteType vote, VoteResult result) {
        switch (result) {
            case SELF_VOTE -> {
                String msg = plugin.getConfig().getString(
                        "messages.cannot-vote-self", "<red>You cannot vote for yourself!");
                player.sendMessage(MM.deserialize(msg));
            }
            case UNCHANGED -> {
                String msg = plugin.getConfig().getString(
                        "messages.vote-unchanged", "<yellow>Your vote is already recorded.");
                player.sendMessage(MM.deserialize(msg));
            }
            case CAST, CHANGED -> {
                // Play sound
                String soundKey = vote == VoteType.LIKE ? "sounds.like" : "sounds.dislike";
                String soundName = plugin.getConfig().getString(soundKey,
                        vote == VoteType.LIKE ? "ENTITY_PLAYER_LEVELUP" : "ENTITY_VILLAGER_NO");
                try {
                    player.playSound(player.getLocation(), Sound.valueOf(soundName.toUpperCase()), 1f, 1f);
                } catch (IllegalArgumentException ignored) {}

                // Send confirmation message
                OfflinePlayer target     = Bukkit.getOfflinePlayer(targetUuid);
                String        targetName = target.getName() != null ? target.getName() : "Unknown";
                String        msgKey     = vote == VoteType.LIKE ? "messages.vote-like" : "messages.vote-dislike";
                String        msg        = plugin.getConfig().getString(msgKey, "")
                        .replace("{player}", targetName);
                if (!msg.isBlank()) player.sendMessage(MM.deserialize(msg));

                // Refresh the open GUI in-place — no reopen needed.
                ReputationGUI.refresh(plugin, player, inv, targetUuid);
            }
        }
    }

    // ── Top ───────────────────────────────────────────────────────

    private void handleTopClick(Player player, int slot) {
        if (slot == 49) { // CLOSE_SLOT
            player.closeInventory();
        }
    }
}
