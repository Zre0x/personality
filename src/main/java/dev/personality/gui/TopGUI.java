package dev.personality.gui;

import dev.personality.PersonalityPlugin;
import dev.personality.database.DatabaseManager.TopEntry;
import dev.personality.util.ItemBuilder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.List;

/**
 * 54-slot Top-10 GUI displaying the most reputed players as player heads.
 *
 * <p>Default layout (players placed with visual spacing):</p>
 * <pre>
 *  Row 0 (0-8):   fillers
 *  Row 1 (9-17):  filler, #1, filler, #2, filler, #3, filler, #4, filler
 *  Row 2 (18-26): filler, #5, filler, #6, filler, #7, filler, #8, filler
 *  Row 3 (27-35): filler, #9, filler, #10, filler ...
 *  Row 4 (36-44): fillers
 *  Row 5 (45-53): filler × 4, [Close], filler × 4
 * </pre>
 */
public final class TopGUI {

    private static final MiniMessage MM         = MiniMessage.miniMessage();
    private static final int         GUI_SIZE   = 54;
    private static final int         CLOSE_SLOT = 49;

    /** Ordered slots where rank 1–10 heads are placed. */
    private static final int[] PLAYER_SLOTS = {10, 12, 14, 16, 19, 21, 23, 25, 28, 30};

    private TopGUI() {}

    // ── Public API ────────────────────────────────────────────────

    /**
     * Fetches the top-10 list asynchronously and opens the GUI on the main thread.
     */
    public static void open(PersonalityPlugin plugin, Player viewer) {
        plugin.getDatabaseManager().getTopPlayers(10).thenAccept(entries -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!viewer.isOnline()) return;
                Inventory inv = buildInventory(plugin, entries);
                viewer.openInventory(inv);
            });
        }).exceptionally(ex -> {
            plugin.getLogger().severe("Error building top GUI: " + ex.getMessage());
            return null;
        });
    }

    // ── Builder ───────────────────────────────────────────────────

    private static Inventory buildInventory(PersonalityPlugin plugin, List<TopEntry> entries) {
        String rawTitle = plugin.getConfig().getString("gui.top-title", "<gold><bold>Top Players");

        PersonalityHolder holder = new PersonalityHolder(PersonalityHolder.Type.TOP, null);
        Inventory inv = Bukkit.createInventory(holder, GUI_SIZE, MM.deserialize(rawTitle));
        holder.setInventory(inv);

        // Fill all slots with glass.
        ItemStack filler = new ItemBuilder(Material.GRAY_STAINED_GLASS_PANE)
                .name(Component.empty())
                .hideFlags()
                .build();
        for (int i = 0; i < GUI_SIZE; i++) inv.setItem(i, filler);

        // Close button
        inv.setItem(CLOSE_SLOT, new ItemBuilder(Material.BARRIER)
                .name(MM.deserialize("<red>Close"))
                .hideFlags()
                .build());

        // Player entries
        for (int rank = 0; rank < entries.size() && rank < PLAYER_SLOTS.length; rank++) {
            TopEntry     entry = entries.get(rank);
            OfflinePlayer op   = Bukkit.getOfflinePlayer(entry.uuid());
            String name        = op.getName() != null
                    ? op.getName()
                    : entry.uuid().toString().substring(0, 8) + "…";

            int    score      = entry.score();
            String scoreColor = score >= 0 ? "<green>" : "<red>";

            ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta sm    = (SkullMeta) skull.getItemMeta();
            sm.setOwningPlayer(op);
            sm.displayName(MM.deserialize("<yellow>#" + (rank + 1) + " <white>" + name));
            sm.lore(List.of(
                    MM.deserialize("<gray>Репутация: " + scoreColor + (score >= 0 ? "+" : "") + score)
            ));
            sm.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            skull.setItemMeta(sm);

            inv.setItem(PLAYER_SLOTS[rank], skull);
        }

        return inv;
    }
}
