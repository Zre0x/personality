package dev.personality;

import dev.personality.command.PersonalityCommand;
import dev.personality.command.ReputationCommand;
import dev.personality.database.DatabaseManager;
import dev.personality.listener.InventoryListener;
import dev.personality.listener.PlayerInteractEntityListener;
import dev.personality.listener.PlayerJoinListener;
import dev.personality.manager.ReputationManager;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Main plugin class for <b>Personality</b> — a player-profile and reputation plugin
 * for Paper / Purpur 1.21.x.
 *
 * <p>Wires together the database, managers, listeners, and commands on enable,
 * and gracefully shuts them down on disable.</p>
 */
public final class PersonalityPlugin extends JavaPlugin {

    private DatabaseManager   databaseManager;
    private ReputationManager reputationManager;

    // ── Lifecycle ─────────────────────────────────────────────────

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // Database — synchronous init, then all subsequent calls are async.
        databaseManager   = new DatabaseManager(this);
        databaseManager.initialize();

        reputationManager = new ReputationManager(this, databaseManager);

        // Listeners
        var pm = getServer().getPluginManager();
        pm.registerEvents(new PlayerJoinListener(this),             this);
        pm.registerEvents(new PlayerInteractEntityListener(this),   this);
        pm.registerEvents(new InventoryListener(this),              this);

        // Commands
        registerCommand("personality", new PersonalityCommand(this));
        registerCommand("reputation",  new ReputationCommand(this));

        getLogger().info("Personality enabled (Paper " + getServer().getBukkitVersion() + ").");
    }

    @Override
    public void onDisable() {
        if (databaseManager != null) {
            databaseManager.close();
        }
        getLogger().info("Personality disabled.");
    }

    // ── Helpers ───────────────────────────────────────────────────

    private <T extends org.bukkit.command.CommandExecutor & org.bukkit.command.TabCompleter>
    void registerCommand(String name, T handler) {
        PluginCommand cmd = getCommand(name);
        if (cmd == null) {
            getLogger().warning("Command '" + name + "' not found in plugin.yml — skipping.");
            return;
        }
        cmd.setExecutor(handler);
        cmd.setTabCompleter(handler);
    }

    // ── Accessors ─────────────────────────────────────────────────

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public ReputationManager getReputationManager() {
        return reputationManager;
    }
}
