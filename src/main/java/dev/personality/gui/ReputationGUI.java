package dev.personality.gui;

import dev.personality.PersonalityPlugin;
import dev.personality.model.VoteType;
import dev.personality.util.ItemBuilder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 27-slot reputation GUI where a viewer can like or dislike a target player.
 *
 * <p>Default slot layout:</p>
 * <pre>
 *  [ ][ ][ ][ ][ ][ ][ ][ ][ ]
 *  [ ][+][ ][ ][V][ ][-][ ][ ]
 *  [ ][ ][ ][ ][B][ ][ ][ ][ ]
 *
 *  +  = Like button (green pane)
 *  -  = Dislike button (red pane)
 *  V  = Current vote indicator
 *  B  = Back button (barrier)
 * </pre>
 *
 * <p>After a vote is cast the GUI is refreshed in-place without reopening it,
 * providing immediate visual feedback.</p>
 */
public final class ReputationGUI {

    private static final MiniMessage MM       = MiniMessage.miniMessage();
    private static final int         GUI_SIZE = 27;

    private ReputationGUI() {}

    // ── Public API ────────────────────────────────────────────────

    /**
     * Opens the reputation GUI for {@code viewer} targeting {@code target}.
     */
    public static void open(PersonalityPlugin plugin, Player viewer, OfflinePlayer target) {
        UUID   targetUuid = target.getUniqueId();
        String targetName = target.getName() != null ? target.getName() : "Unknown";

        CompletableFuture<int[]>    repFuture  = plugin.getReputationManager().getReputation(targetUuid);
        CompletableFuture<VoteType> voteFuture = plugin.getDatabaseManager().getVote(targetUuid, viewer.getUniqueId());

        CompletableFuture.allOf(repFuture, voteFuture).thenRun(() -> {
            int[]    counts      = repFuture.join();
            VoteType currentVote = voteFuture.join();

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!viewer.isOnline()) return;
                Inventory inv = buildInventory(plugin, targetName, target.getUniqueId(), counts, currentVote);
                viewer.openInventory(inv);
            });
        }).exceptionally(ex -> {
            plugin.getLogger().severe("Error opening reputation GUI: " + ex.getMessage());
            return null;
        });
    }

    /**
     * Refreshes the interactive items inside an already-open reputation inventory.
     * This is called after a successful vote to update counts and the current-vote indicator
     * without reopening the inventory, giving the player instant feedback.
     */
    public static void refresh(PersonalityPlugin plugin, Player viewer, Inventory inv, UUID targetUuid) {
        // Cache was invalidated by ReputationManager before this method is called,
        // so getReputation() will fetch fresh data from the database.
        CompletableFuture<int[]>    repFuture  = plugin.getReputationManager().getReputation(targetUuid);
        CompletableFuture<VoteType> voteFuture = plugin.getDatabaseManager().getVote(targetUuid, viewer.getUniqueId());

        CompletableFuture.allOf(repFuture, voteFuture).thenRun(() -> {
            int[]    counts      = repFuture.join();
            VoteType currentVote = voteFuture.join();

            Bukkit.getScheduler().runTask(plugin, () -> {
                // Guard: only update if the player still has this inventory open.
                if (!viewer.isOnline()) return;
                if (!viewer.getOpenInventory().getTopInventory().equals(inv)) return;
                populateVoteItems(plugin, inv, counts, currentVote);
            });
        });
    }

    // ── Builder ───────────────────────────────────────────────────

    private static Inventory buildInventory(PersonalityPlugin plugin, String targetName,
                                             UUID targetUuid, int[] counts, VoteType currentVote) {
        String rawTitle = plugin.getConfig()
                .getString("gui.reputation-title", "<dark_gray>Reputation: <white>{player}")
                .replace("{player}", targetName);

        PersonalityHolder holder = new PersonalityHolder(PersonalityHolder.Type.REPUTATION, targetUuid);
        Inventory inv = Bukkit.createInventory(holder, GUI_SIZE, MM.deserialize(rawTitle));
        holder.setInventory(inv);

        // Fill all slots with glass first.
        ItemStack filler = new ItemBuilder(Material.GRAY_STAINED_GLASS_PANE)
                .name(Component.empty())
                .hideFlags()
                .build();
        for (int i = 0; i < GUI_SIZE; i++) inv.setItem(i, filler);

        // Back button
        int backSlot = plugin.getConfig().getInt("gui.reputation-slots.back-button", 22);
        inv.setItem(backSlot, new ItemBuilder(Material.BARRIER)
                .name(MM.deserialize("<red>Back"))
                .lore(List.of(MM.deserialize("<gray>Return to profile")))
                .hideFlags()
                .build());

        // Interactive vote items
        populateVoteItems(plugin, inv, counts, currentVote);

        return inv;
    }

    /**
     * Sets (or refreshes) the three interactive items: like button, dislike button,
     * and the current-vote indicator. Safe to call multiple times on the same inventory.
     */
    static void populateVoteItems(PersonalityPlugin plugin, Inventory inv,
                                   int[] counts, VoteType currentVote) {
        int likes    = counts[0];
        int dislikes = counts[1];

        int likeSlot    = plugin.getConfig().getInt("gui.reputation-slots.like-button",    11);
        int dislikeSlot = plugin.getConfig().getInt("gui.reputation-slots.dislike-button", 15);
        int cvSlot      = plugin.getConfig().getInt("gui.reputation-slots.current-vote",   13);

        // ── Like button ───────────────────────────────────────────
        boolean isLiked = currentVote == VoteType.LIKE;
        List<Component> likeLore = List.of(
                MM.deserialize("<gray>Total: <white>" + likes),
                Component.empty(),
                isLiked
                        ? MM.deserialize("<green><bold>✔ Your current vote")
                        : MM.deserialize("<gray>Click to like this player")
        );
        inv.setItem(likeSlot, new ItemBuilder(Material.LIME_STAINED_GLASS_PANE)
                .name(MM.deserialize(isLiked ? "<green><bold>Like" : "<green>Like"))
                .lore(likeLore)
                .hideFlags()
                .build());

        // ── Dislike button ────────────────────────────────────────
        boolean isDisliked = currentVote == VoteType.DISLIKE;
        List<Component> dislikeLore = List.of(
                MM.deserialize("<gray>Total: <white>" + dislikes),
                Component.empty(),
                isDisliked
                        ? MM.deserialize("<red><bold>✔ Your current vote")
                        : MM.deserialize("<gray>Click to dislike this player")
        );
        inv.setItem(dislikeSlot, new ItemBuilder(Material.RED_STAINED_GLASS_PANE)
                .name(MM.deserialize(isDisliked ? "<red><bold>Dislike" : "<red>Dislike"))
                .lore(dislikeLore)
                .hideFlags()
                .build());

        // ── Current vote indicator ────────────────────────────────
        String cvText;
        if (currentVote == null) {
            cvText = "<gray>No vote yet";
        } else {
            cvText = currentVote == VoteType.LIKE ? "<green>Liked" : "<red>Disliked";
        }

        inv.setItem(cvSlot, new ItemBuilder(Material.PAPER)
                .name(MM.deserialize("<yellow>Your Vote"))
                .lore(List.of(MM.deserialize(cvText)))
                .hideFlags()
                .build());
    }
}
