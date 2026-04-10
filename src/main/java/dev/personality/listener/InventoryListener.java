package dev.personality.listener;

import dev.personality.PersonalityPlugin;
import dev.personality.gui.FriendsGUI;
import dev.personality.gui.PersonalityHolder;
import dev.personality.gui.ProfileGUI;
import dev.personality.gui.ReputationGUI;
import dev.personality.manager.FriendManager.FriendResult;
import dev.personality.manager.ReputationManager.RepResult;
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
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

public final class InventoryListener implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final PersonalityPlugin plugin;

    public InventoryListener(PersonalityPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!(event.getInventory().getHolder() instanceof PersonalityHolder holder)) return;

        event.setCancelled(true);

        Inventory clicked = event.getClickedInventory();
        if (clicked == null || !clicked.equals(event.getInventory())) return;

        int slot = event.getSlot();

        switch (holder.getType()) {
            case PROFILE    -> handleProfileClick(player, holder, slot);
            case REPUTATION -> handleReputationClick(player, holder, slot, event.getInventory());
            case TOP        -> handleTopClick(player, slot);
            case FRIENDS    -> handleFriendsClick(player, holder, slot, event.getInventory());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof PersonalityHolder) {
            event.setCancelled(true);
        }
    }

    // ── Profile ───────────────────────────────────────────────────

    private void handleProfileClick(Player player, PersonalityHolder holder, int slot) {
        UUID targetUuid = holder.getTargetUuid();
        if (targetUuid == null) return;

        int repBtnSlot    = plugin.getConfig().getInt("gui.profile-slots.reputation-button", 22);
        int friendBtnSlot = plugin.getConfig().getInt("gui.profile-slots.friends-button", 16);

        if (slot == repBtnSlot) {
            OfflinePlayer target = Bukkit.getOfflinePlayer(targetUuid);
            ReputationGUI.open(plugin, player, target);
        } else if (slot == friendBtnSlot) {
            boolean isSelf = player.getUniqueId().equals(targetUuid);
            if (isSelf) {
                // Open own friends list
                Bukkit.getScheduler().runTask(plugin, () -> FriendsGUI.open(plugin, player));
            } else {
                // Toggle friend
                plugin.getFriendManager().toggle(player.getUniqueId(), targetUuid)
                        .thenAccept(result -> Bukkit.getScheduler().runTask(plugin, () -> {
                            if (result == FriendResult.ADDED) {
                                player.sendMessage(MM.deserialize("<green>Игрок добавлен в друзья."));
                            } else if (result == FriendResult.REMOVED) {
                                player.sendMessage(MM.deserialize("<gray>Игрок удалён из друзей."));
                            }
                            // Reopen profile to reflect new state
                            ProfileGUI.open(plugin, player, Bukkit.getOfflinePlayer(targetUuid));
                        }));
            }
        }
    }

    // ── Friends ───────────────────────────────────────────────────

    private void handleFriendsClick(Player player, PersonalityHolder holder, int slot, Inventory inv) {
        int size = inv.getSize();
        int backSlot = size - 5;

        if (slot == backSlot) {
            // Back to own profile
            Bukkit.getScheduler().runTask(plugin, () -> ProfileGUI.open(plugin, player, player));
            return;
        }

        // Click on a head — open that friend's profile
        ItemStack item = inv.getItem(slot);
        if (item == null || item.getType() != org.bukkit.Material.PLAYER_HEAD) return;
        if (!(item.getItemMeta() instanceof org.bukkit.inventory.meta.SkullMeta sm)) return;
        OfflinePlayer fp = sm.getOwningPlayer();
        if (fp == null) return;
        Bukkit.getScheduler().runTask(plugin, () -> ProfileGUI.open(plugin, player, fp));
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
            ProfileGUI.open(plugin, player, Bukkit.getOfflinePlayer(targetUuid));
            return;
        }

        VoteType direction = null;
        if (slot == likeSlot)    direction = VoteType.LIKE;
        if (slot == dislikeSlot) direction = VoteType.DISLIKE;
        if (direction == null) return;

        // Gray panes (self / cooldown) are also in these slots — ignore clicks on them
        // by checking the item material before proceeding
        var item = inv.getItem(slot);
        if (item != null && item.getType() == org.bukkit.Material.GRAY_STAINED_GLASS_PANE) return;

        final VoteType finalDir = direction;
        plugin.getReputationManager()
                .giveRep(player.getUniqueId(), targetUuid, finalDir)
                .thenAccept(result -> Bukkit.getScheduler().runTask(plugin, () ->
                        handleRepResult(player, inv, targetUuid, finalDir, result)));
    }

    private void handleRepResult(Player player, Inventory inv, UUID targetUuid,
                                  VoteType dir, RepResult result) {
        switch (result) {
            case SELF -> player.sendMessage(MM.deserialize(
                    plugin.getConfig().getString("messages.cannot-vote-self",
                            "<red>Нельзя оценивать самого себя!")));

            case ON_COOLDOWN -> plugin.getReputationManager()
                    .cooldownRemaining(player.getUniqueId(), targetUuid)
                    .thenAccept(ms -> Bukkit.getScheduler().runTask(plugin, () -> {
                        long h = ms / 3600000, m = (ms % 3600000) / 60000;
                        player.sendMessage(MM.deserialize(
                                "<red>Кулдаун. Осталось: <white>" + h + "ч " + m + "м"));
                    }));

            case OK -> {
                String soundKey  = dir == VoteType.LIKE ? "sounds.like" : "sounds.dislike";
                String soundName = plugin.getConfig().getString(soundKey,
                        dir == VoteType.LIKE ? "ENTITY_PLAYER_LEVELUP" : "ENTITY_VILLAGER_NO");
                try {
                    player.playSound(player.getLocation(), Sound.valueOf(soundName.toUpperCase()), 1f, 1f);
                } catch (IllegalArgumentException ignored) {}

                String msgKey = dir == VoteType.LIKE ? "messages.vote-like" : "messages.vote-dislike";
                String msg    = plugin.getConfig().getString(msgKey, "");
                if (msg != null && !msg.isBlank()) {
                    String name = Bukkit.getOfflinePlayer(targetUuid).getName();
                    player.sendMessage(MM.deserialize(msg.replace("{player}", name != null ? name : "?")));
                }

                plugin.getReputationManager().invalidate(targetUuid);
                ReputationGUI.refresh(plugin, player, inv, targetUuid);
            }
        }
    }

    // ── Top ───────────────────────────────────────────────────────

    private void handleTopClick(Player player, int slot) {
        if (slot == 49) player.closeInventory();
    }
}
