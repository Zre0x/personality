package dev.personality.util;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Arrays;
import java.util.List;

/**
 * Fluent builder for {@link ItemStack} instances using the Adventure API.
 *
 * <p>Always call {@link #build()} at the end; the builder is single-use.</p>
 */
public final class ItemBuilder {

    private final ItemStack item;
    private final ItemMeta  meta;

    public ItemBuilder(Material material) {
        this.item = new ItemStack(material);
        this.meta = item.getItemMeta();
    }

    /** Sets the display name. Pass {@link Component#empty()} for a blank name. */
    public ItemBuilder name(Component name) {
        meta.displayName(name);
        return this;
    }

    /** Sets the lore lines. */
    public ItemBuilder lore(List<Component> lore) {
        meta.lore(lore);
        return this;
    }

    /** Varargs convenience overload for lore. */
    public ItemBuilder lore(Component... lines) {
        meta.lore(Arrays.asList(lines));
        return this;
    }

    /** Sets the stack size. */
    public ItemBuilder amount(int amount) {
        item.setAmount(amount);
        return this;
    }

    /**
     * Hides all attribute and additional tooltip lines so filler items are truly clean.
     */
    public ItemBuilder hideFlags() {
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        return this;
    }

    /** Adds specific {@link ItemFlag}s. */
    public ItemBuilder flags(ItemFlag... flags) {
        meta.addItemFlags(flags);
        return this;
    }

    /** Applies the meta and returns the finished item. */
    public ItemStack build() {
        item.setItemMeta(meta);
        return item;
    }
}
