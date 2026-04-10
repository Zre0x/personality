package dev.personality.gui;

import dev.personality.PersonalityPlugin;
import dev.personality.util.ItemBuilder;
import dev.personality.util.PlaceholderUtil;
import dev.personality.util.TimeUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 27-slot profile GUI showing a player's statistics and a button to open the reputation menu.
 *
 * <p>Layout (default slot assignments — all configurable in config.yml):</p>
 * <pre>
 *  [ ][ ][ ][ ][H][ ][ ][ ][ ]
 *  [ ][FJ][PT][ ][S][ ][DC][ ][ ]
 *  [ ][ ][ ][ ][R][ ][ ][ ][ ]
 *
 *  H  = Player head
 *  FJ = First join
 *  PT = Playtime
 *  S  = Reputation score
 *  DC = Discord username
 *  R  = Open reputation menu (likes/dislikes detail shown there)
 *  [ ] = Gray glass pane (filler)
 * </pre>
 */
public final class ProfileGUI {

    private static final MiniMessage MM       = MiniMessage.miniMessage();
    private static final int         GUI_SIZE = 27;

    private ProfileGUI() {}

    // ── Public API ────────────────────────────────────────────────

    /**
     * Fetches all required data asynchronously, then opens the GUI on the main thread.
     */
    public static void open(PersonalityPlugin plugin, Player viewer, OfflinePlayer target) {
        UUID   targetUuid = target.getUniqueId();
        String targetName = target.getName() != null ? target.getName() : "Unknown";

        CompletableFuture<int[]>    repFuture    = plugin.getReputationManager().getReputation(targetUuid);
        CompletableFuture<Long>     fjFuture     = plugin.getDatabaseManager().getFirstJoin(targetUuid);
        CompletableFuture<Boolean>  friendFuture = plugin.getFriendManager().areFriends(viewer.getUniqueId(), targetUuid);

        CompletableFuture.allOf(repFuture, fjFuture, friendFuture).thenRun(() -> {
            int[]   counts    = repFuture.join();
            long    firstJoin = fjFuture.join();
            boolean isFriend  = friendFuture.join();

            if (firstJoin <= 0) firstJoin = target.getFirstPlayed();

            final long    resolvedFirstJoin = firstJoin;
            final boolean resolvedFriend    = isFriend;

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!viewer.isOnline()) return;

                // Resolve Discord on main thread (PAPI may need online player context)
                Player onlineTarget = Bukkit.getPlayer(targetUuid);
                String discord = resolveDiscord(plugin, target, onlineTarget);

                Inventory inv = buildInventory(plugin, viewer, target, targetName, counts,
                        resolvedFirstJoin, discord, resolvedFriend);
                viewer.openInventory(inv);
                playSound(viewer, plugin.getConfig().getString("sounds.open-profile", "BLOCK_CHEST_OPEN"));
            });
        }).exceptionally(ex -> {
            plugin.getLogger().severe("Error building profile GUI for " + targetName + ": " + ex.getMessage());
            return null;
        });
    }

    private static String resolveDiscord(PersonalityPlugin plugin, OfflinePlayer target, Player onlineTarget) {
        String placeholder = plugin.getConfig().getString("placeholder.discord", "%discordsrv_user%");
        // Prefer online resolution — DiscordSRV PAPI works better with online players
        if (onlineTarget != null) {
            String result = PlaceholderUtil.resolve(plugin, onlineTarget, placeholder, "");
            if (!result.isBlank()) return result;
        }
        String result = PlaceholderUtil.resolveOffline(plugin, target, placeholder, "");
        return result.isBlank() ? "Не привязан" : result;
    }

    // ── Builder ───────────────────────────────────────────────────

    private static Inventory buildInventory(PersonalityPlugin plugin, Player viewer,
                                             OfflinePlayer target, String targetName,
                                             int[] counts, long firstJoin,
                                             String discord, boolean isFriend) {
        String rawTitle = plugin.getConfig()
                .getString("gui.profile-title", "<dark_gray>Profile: <white>{player}")
                .replace("{player}", targetName);

        PersonalityHolder holder = new PersonalityHolder(PersonalityHolder.Type.PROFILE, target.getUniqueId());
        Inventory inv = Bukkit.createInventory(holder, GUI_SIZE, MM.deserialize(rawTitle));
        holder.setInventory(inv);

        // ── Fill all slots with glass first ───────────────────────
        ItemStack filler = new ItemBuilder(Material.GRAY_STAINED_GLASS_PANE)
                .name(Component.empty())
                .hideFlags()
                .build();
        for (int i = 0; i < GUI_SIZE; i++) inv.setItem(i, filler);

        // ── Player head ───────────────────────────────────────────
        int headSlot = plugin.getConfig().getInt("gui.profile-slots.player-head", 4);
        inv.setItem(headSlot, buildSkull(target, targetName));

        // ── First join ────────────────────────────────────────────
        if (plugin.getConfig().getBoolean("stats.show-first-join", true)) {
            int slot   = plugin.getConfig().getInt("gui.profile-slots.first-join", 10);
            String date = firstJoin > 0 ? TimeUtil.formatTimestamp(firstJoin) : "Unknown";
            inv.setItem(slot, new ItemBuilder(Material.CLOCK)
                    .name(MM.deserialize("<gray>First Join"))
                    .lore(List.of(MM.deserialize("<white>" + date)))
                    .hideFlags()
                    .build());
        }

        // ── Playtime ──────────────────────────────────────────────
        if (plugin.getConfig().getBoolean("stats.show-playtime", true)) {
            int slot  = plugin.getConfig().getInt("gui.profile-slots.playtime", 11);
            long ticks = 0L;
            try {
                ticks = target.getStatistic(org.bukkit.Statistic.PLAY_ONE_MINUTE);
            } catch (Exception ignored) {}
            inv.setItem(slot, new ItemBuilder(Material.COMPASS)
                    .name(MM.deserialize("<gray>Playtime"))
                    .lore(List.of(MM.deserialize("<white>" + TimeUtil.formatTicks(ticks))))
                    .hideFlags()
                    .build());
        }

        // ── Reputation score ──────────────────────────────────────
        if (plugin.getConfig().getBoolean("stats.show-reputation", true)) {
            int score = counts[0]; // counts[0] is now the numeric score directly
            String scoreColor = score >= 0 ? "<green>" : "<red>";
            String scoreStr   = (score >= 0 ? "+" : "") + score;

            int rSlot = plugin.getConfig().getInt("gui.profile-slots.reputation", 13);
            inv.setItem(rSlot, new ItemBuilder(Material.NETHER_STAR)
                    .name(MM.deserialize("<yellow>Репутация"))
                    .lore(List.of(MM.deserialize(scoreColor + scoreStr)))
                    .hideFlags()
                    .build());
        }

        // ── Discord ───────────────────────────────────────────────
        if (plugin.getConfig().getBoolean("stats.show-discord", true)) {
            int slot = plugin.getConfig().getInt("gui.profile-slots.discord", 15);
            inv.setItem(slot, new ItemBuilder(Material.BOOK)
                    .name(MM.deserialize("<aqua>Discord"))
                    .lore(List.of(MM.deserialize("<white>" + discord)))
                    .hideFlags()
                    .build());
        }

        // ── Achievements ──────────────────────────────────────────
        if (plugin.getConfig().getBoolean("stats.show-achievements", true)) {
            int slot = plugin.getConfig().getInt("gui.profile-slots.achievements", 12);
            int done = countAdvancementsDone(target);
            inv.setItem(slot, new ItemBuilder(Material.GOLDEN_APPLE)
                    .name(MM.deserialize("<gold>Достижения"))
                    .lore(List.of(MM.deserialize("<yellow>" + done + " <gray>выполнено")))
                    .hideFlags()
                    .build());
        }

        // ── Friends button (only when viewing someone else) ───────
        boolean isSelf = viewer.getUniqueId().equals(target.getUniqueId());
        int friendSlot = plugin.getConfig().getInt("gui.profile-slots.friends-button", 16);
        if (isSelf) {
            inv.setItem(friendSlot, new ItemBuilder(Material.PLAYER_HEAD)
                    .name(MM.deserialize("<green>Друзья"))
                    .lore(List.of(MM.deserialize("<gray>Открыть список друзей")))
                    .hideFlags()
                    .build());
        } else {
            Material friendMat = isFriend ? Material.LIME_DYE : Material.GRAY_DYE;
            String friendLabel = isFriend ? "<red>Убрать из друзей" : "<green>Добавить в друзья";
            inv.setItem(friendSlot, new ItemBuilder(friendMat)
                    .name(MM.deserialize(friendLabel))
                    .hideFlags()
                    .build());
        }

        // ── Reputation menu button ────────────────────────────────
        int repSlot = plugin.getConfig().getInt("gui.profile-slots.reputation-button", 22);
        inv.setItem(repSlot, new ItemBuilder(Material.EXPERIENCE_BOTTLE)
                .name(MM.deserialize("<gold>Оценить игрока"))
                .lore(List.of(MM.deserialize("<gray>Открыть меню репутации")))
                .hideFlags()
                .build());

        return inv;
    }

    private static int countAdvancementsDone(OfflinePlayer target) {
        Player online = target.getPlayer();
        if (online == null) return 0;
        int count = 0;
        for (var it = Bukkit.advancementIterator(); it.hasNext(); ) {
            var adv = it.next();
            var progress = online.getAdvancementProgress(adv);
            if (progress.isDone()) count++;
        }
        return count;
    }

    private static ItemStack buildSkull(OfflinePlayer target, String displayName) {
        ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta sm    = (SkullMeta) skull.getItemMeta();
        sm.setOwningPlayer(target);
        sm.displayName(MiniMessage.miniMessage().deserialize("<yellow><bold>" + displayName));
        sm.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        skull.setItemMeta(sm);
        return skull;
    }

    static void playSound(Player player, String soundName) {
        try {
            Sound sound = Sound.valueOf(soundName.toUpperCase());
            player.playSound(player.getLocation(), sound, 1f, 1f);
        } catch (IllegalArgumentException ignored) {}
    }
}
