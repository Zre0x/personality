package dev.personality.gui;

import dev.personality.PersonalityPlugin;
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
 * 27-slot reputation GUI where a viewer can give +1 or -1 rep to a target player.
 *
 * <p>Default slot layout:</p>
 * <pre>
 *  [ ][ ][ ][ ][ ][ ][ ][ ][ ]
 *  [ ][+][ ][ ][S][ ][-][ ][ ]
 *  [ ][ ][ ][ ][B][ ][ ][ ][ ]
 *
 *  +  = +1 rep button (green pane)  — slot 11
 *  S  = Current score indicator     — slot 13
 *  -  = -1 rep button (red pane)    — slot 15
 *  B  = Back button                  — slot 22
 * </pre>
 */
public final class ReputationGUI {

    private static final MiniMessage MM       = MiniMessage.miniMessage();
    private static final int         GUI_SIZE = 27;

    private ReputationGUI() {}

    public static void open(PersonalityPlugin plugin, Player viewer, OfflinePlayer target) {
        UUID   targetUuid = target.getUniqueId();
        String targetName = target.getName() != null ? target.getName() : "Unknown";

        CompletableFuture<Integer> scoreFuture    = plugin.getReputationManager().getScore(targetUuid);
        CompletableFuture<Long>    cdFuture        = plugin.getReputationManager().cooldownRemaining(
                viewer.getUniqueId(), targetUuid);

        CompletableFuture.allOf(scoreFuture, cdFuture).thenRun(() -> {
            int  score     = scoreFuture.join();
            long cdMs      = cdFuture.join();

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!viewer.isOnline()) return;
                Inventory inv = buildInventory(plugin, targetName, targetUuid, score, cdMs,
                        viewer.getUniqueId().equals(targetUuid));
                viewer.openInventory(inv);
            });
        }).exceptionally(ex -> {
            plugin.getLogger().severe("Error opening reputation GUI: " + ex.getMessage());
            return null;
        });
    }

    public static void refresh(PersonalityPlugin plugin, Player viewer, Inventory inv, UUID targetUuid) {
        CompletableFuture<Integer> scoreFuture = plugin.getReputationManager().getScore(targetUuid);
        CompletableFuture<Long>    cdFuture    = plugin.getReputationManager().cooldownRemaining(
                viewer.getUniqueId(), targetUuid);

        CompletableFuture.allOf(scoreFuture, cdFuture).thenRun(() -> {
            int  score = scoreFuture.join();
            long cdMs  = cdFuture.join();

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!viewer.isOnline()) return;
                if (!viewer.getOpenInventory().getTopInventory().equals(inv)) return;
                populateItems(plugin, inv, score, cdMs, viewer.getUniqueId().equals(targetUuid));
            });
        });
    }

    // ── Builder ───────────────────────────────────────────────────

    private static Inventory buildInventory(PersonalityPlugin plugin, String targetName,
                                             UUID targetUuid, int score, long cdMs, boolean isSelf) {
        String rawTitle = plugin.getConfig()
                .getString("gui.reputation-title", "<dark_gray>Reputation: <white>{player}")
                .replace("{player}", targetName);

        PersonalityHolder holder = new PersonalityHolder(PersonalityHolder.Type.REPUTATION, targetUuid);
        Inventory inv = Bukkit.createInventory(holder, GUI_SIZE, MM.deserialize(rawTitle));
        holder.setInventory(inv);

        ItemStack filler = new ItemBuilder(Material.GRAY_STAINED_GLASS_PANE)
                .name(Component.empty()).hideFlags().build();
        for (int i = 0; i < GUI_SIZE; i++) inv.setItem(i, filler);

        int backSlot = plugin.getConfig().getInt("gui.reputation-slots.back-button", 22);
        inv.setItem(backSlot, new ItemBuilder(Material.BARRIER)
                .name(MM.deserialize("<red>Назад"))
                .lore(List.of(MM.deserialize("<gray>Вернуться в профиль")))
                .hideFlags().build());

        populateItems(plugin, inv, score, cdMs, isSelf);
        return inv;
    }

    static void populateItems(PersonalityPlugin plugin, Inventory inv,
                               int score, long cdMs, boolean isSelf) {
        int likeSlot    = plugin.getConfig().getInt("gui.reputation-slots.like-button",    11);
        int dislikeSlot = plugin.getConfig().getInt("gui.reputation-slots.dislike-button", 15);
        int cvSlot      = plugin.getConfig().getInt("gui.reputation-slots.current-vote",   13);

        String scoreColor = score >= 0 ? "<green>" : "<red>";
        String scoreStr   = (score >= 0 ? "+" : "") + score;

        // Score display
        inv.setItem(cvSlot, new ItemBuilder(Material.NETHER_STAR)
                .name(MM.deserialize("<yellow>Репутация"))
                .lore(List.of(MM.deserialize(scoreColor + scoreStr)))
                .hideFlags().build());

        boolean onCooldown = cdMs > 0;
        String cdText = onCooldown ? formatCooldown(cdMs) : null;

        // +1 button
        if (isSelf) {
            inv.setItem(likeSlot, new ItemBuilder(Material.GRAY_STAINED_GLASS_PANE)
                    .name(MM.deserialize("<dark_gray>+1 Репутация"))
                    .lore(List.of(MM.deserialize("<gray>Нельзя оценивать себя")))
                    .hideFlags().build());
        } else if (onCooldown) {
            inv.setItem(likeSlot, new ItemBuilder(Material.GRAY_STAINED_GLASS_PANE)
                    .name(MM.deserialize("<dark_gray>+1 Репутация"))
                    .lore(List.of(MM.deserialize("<red>Кулдаун: <white>" + cdText)))
                    .hideFlags().build());
        } else {
            inv.setItem(likeSlot, new ItemBuilder(Material.LIME_STAINED_GLASS_PANE)
                    .name(MM.deserialize("<green><bold>+1 Репутация"))
                    .lore(List.of(MM.deserialize("<gray>Нажми, чтобы повысить репутацию")))
                    .hideFlags().build());
        }

        // -1 button
        if (isSelf) {
            inv.setItem(dislikeSlot, new ItemBuilder(Material.GRAY_STAINED_GLASS_PANE)
                    .name(MM.deserialize("<dark_gray>-1 Репутация"))
                    .lore(List.of(MM.deserialize("<gray>Нельзя оценивать себя")))
                    .hideFlags().build());
        } else if (onCooldown) {
            inv.setItem(dislikeSlot, new ItemBuilder(Material.GRAY_STAINED_GLASS_PANE)
                    .name(MM.deserialize("<dark_gray>-1 Репутация"))
                    .lore(List.of(MM.deserialize("<red>Кулдаун: <white>" + cdText)))
                    .hideFlags().build());
        } else {
            inv.setItem(dislikeSlot, new ItemBuilder(Material.RED_STAINED_GLASS_PANE)
                    .name(MM.deserialize("<red><bold>-1 Репутация"))
                    .lore(List.of(MM.deserialize("<gray>Нажми, чтобы понизить репутацию")))
                    .hideFlags().build());
        }
    }

    private static String formatCooldown(long ms) {
        long totalSec = ms / 1000;
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        if (h > 0) return h + "ч " + m + "м";
        if (m > 0) return m + "м " + s + "с";
        return s + "с";
    }
}
