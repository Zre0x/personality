package dev.personality.gui;

import dev.personality.PersonalityPlugin;
import dev.personality.util.ItemBuilder;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class FriendsGUI {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private FriendsGUI() {}

    public static void open(PersonalityPlugin plugin, Player viewer) {
        plugin.getFriendManager().getFriends(viewer.getUniqueId()).thenAccept(friends ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!viewer.isOnline()) return;
                    build(plugin, viewer, friends);
                }));
    }

    private static void build(PersonalityPlugin plugin, Player viewer, List<UUID> friends) {
        int size = Math.max(27, ((friends.size() / 9) + 1) * 9);
        if (size > 54) size = 54;

        PersonalityHolder holder = new PersonalityHolder(PersonalityHolder.Type.FRIENDS, viewer.getUniqueId());
        Inventory inv = Bukkit.createInventory(holder, size, MM.deserialize("<dark_gray>Друзья"));
        holder.setInventory(inv);

        ItemStack filler = new ItemBuilder(Material.GRAY_STAINED_GLASS_PANE)
                .name(net.kyori.adventure.text.Component.empty()).hideFlags().build();
        for (int i = 0; i < size; i++) inv.setItem(i, filler);

        int slot = 0;
        for (UUID friendUuid : friends) {
            if (slot >= size - 9) break; // leave bottom row for nav
            OfflinePlayer fp = Bukkit.getOfflinePlayer(friendUuid);
            boolean online = fp.isOnline();
            String status = online ? "<green>● Онлайн" : "<gray>● Оффлайн";
            inv.setItem(slot, buildHead(fp, status));
            slot++;
        }

        if (friends.isEmpty()) {
            inv.setItem(13, new ItemBuilder(Material.BARRIER)
                    .name(MM.deserialize("<gray>Список друзей пуст"))
                    .hideFlags().build());
        }

        // Back button — bottom middle
        inv.setItem(size - 5, new ItemBuilder(Material.ARROW)
                .name(MM.deserialize("<gray>Назад"))
                .hideFlags().build());

        viewer.openInventory(inv);
    }

    private static ItemStack buildHead(OfflinePlayer fp, String statusLine) {
        String name = fp.getName() != null ? fp.getName() : "Unknown";
        ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta sm = (SkullMeta) skull.getItemMeta();
        sm.setOwningPlayer(fp);
        sm.displayName(MM.deserialize("<white>" + name));
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        lore.add(MM.deserialize(statusLine));
        lore.add(MM.deserialize("<dark_gray>ЛКМ — открыть профиль"));
        sm.lore(lore);
        sm.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        skull.setItemMeta(sm);
        return skull;
    }
}
