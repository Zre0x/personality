package dev.personality.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Custom {@link InventoryHolder} that identifies all inventories opened by this plugin.
 *
 * <p>Checking {@code inventory.getHolder() instanceof PersonalityHolder} in event handlers
 * is the authoritative way to determine whether an inventory belongs to this plugin.</p>
 */
public final class PersonalityHolder implements InventoryHolder {

    /** Distinguishes which GUI screen this holder belongs to. */
    public enum Type {
        PROFILE,
        REPUTATION,
        TOP,
        FRIENDS
    }

    private final Type type;

    /**
     * The target player's UUID. May be {@code null} for the {@link Type#TOP} screen
     * which has no single target.
     */
    private final @Nullable UUID targetUuid;

    private Inventory inventory;

    public PersonalityHolder(Type type, @Nullable UUID targetUuid) {
        this.type       = type;
        this.targetUuid = targetUuid;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    /** Must be called immediately after {@link org.bukkit.Bukkit#createInventory} returns. */
    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    public Type getType() {
        return type;
    }

    public @Nullable UUID getTargetUuid() {
        return targetUuid;
    }
}
