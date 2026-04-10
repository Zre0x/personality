package dev.personality;

import dev.personality.command.PersonalityCommand;
import dev.personality.command.RepAdminCommand;
import dev.personality.command.ReputationCommand;
import dev.personality.database.DatabaseManager;
import dev.personality.hooks.DiscordSyncHook;
import dev.personality.listener.InventoryListener;
import dev.personality.listener.PlayerInteractEntityListener;
import dev.personality.listener.PlayerJoinListener;
import dev.personality.manager.FriendManager;
import dev.personality.manager.ReputationManager;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class PersonalityPlugin extends JavaPlugin {

    private DatabaseManager   databaseManager;
    private ReputationManager reputationManager;
    private FriendManager     friendManager;
    private DiscordSyncHook   discordSync;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("horizon-reputation-roles.yml", false);

        databaseManager   = new DatabaseManager(this);
        databaseManager.initialize();

        discordSync       = new DiscordSyncHook(this);
        reputationManager = new ReputationManager(this, databaseManager);
        friendManager     = new FriendManager(this, databaseManager);

        var pm = getServer().getPluginManager();
        pm.registerEvents(new PlayerJoinListener(this),           this);
        pm.registerEvents(new PlayerInteractEntityListener(this), this);
        pm.registerEvents(new InventoryListener(this),            this);

        registerCommand("personality", new PersonalityCommand(this));
        registerCommand("reputation",  new ReputationCommand(this));
        registerCommand("repadmin",    new RepAdminCommand(this));

        getLogger().info("Personality enabled (Paper " + getServer().getBukkitVersion() + ").");
    }

    @Override
    public void onDisable() {
        if (databaseManager != null) databaseManager.close();
        getLogger().info("Personality disabled.");
    }

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

    public DatabaseManager getDatabaseManager()     { return databaseManager; }
    public ReputationManager getReputationManager() { return reputationManager; }
    public FriendManager getFriendManager()         { return friendManager; }
    public DiscordSyncHook getDiscordSync()         { return discordSync; }
}
