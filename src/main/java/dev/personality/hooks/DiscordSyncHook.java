package dev.personality.hooks;

import dev.personality.PersonalityPlugin;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Syncs reputation thresholds to Discord roles via DiscordSRV (reflection-based).
 * Also applies/removes LuckPerms groups if configured.
 *
 * Reads horizon-reputation-roles.yml from plugin data folder.
 */
public final class DiscordSyncHook {

    private final PersonalityPlugin plugin;
    private YamlConfiguration rolesConfig;

    private final List<Integer> positiveThresholds = new ArrayList<>();
    private final List<Integer> negativeThresholds = new ArrayList<>();

    public DiscordSyncHook(PersonalityPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        File file = new File(plugin.getDataFolder(), "horizon-reputation-roles.yml");
        if (!file.exists()) {
            plugin.saveResource("horizon-reputation-roles.yml", false);
        }
        rolesConfig = YamlConfiguration.loadConfiguration(file);

        positiveThresholds.clear();
        negativeThresholds.clear();

        ConfigurationSection pos = rolesConfig.getConfigurationSection("thresholds.positive");
        if (pos != null) {
            for (String key : pos.getKeys(false)) {
                try { positiveThresholds.add(Integer.parseInt(key)); }
                catch (NumberFormatException ignored) {}
            }
        }
        ConfigurationSection neg = rolesConfig.getConfigurationSection("thresholds.negative");
        if (neg != null) {
            for (String key : neg.getKeys(false)) {
                try { negativeThresholds.add(Integer.parseInt(key)); }
                catch (NumberFormatException ignored) {}
            }
        }

        Collections.sort(positiveThresholds);
        negativeThresholds.sort(Collections.reverseOrder());
    }

    /** Fire-and-forget: resolve player then sync. Works even if player is offline. */
    public void syncRolesAsync(UUID uuid, int score) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Player online = Bukkit.getPlayer(uuid);
            if (online != null) syncRoles(online, score);
            // Offline players: Discord role sync not possible without linked ID resolution
            // — roles will be re-applied on next login (handled in PlayerJoinListener)
        });
    }

    public void syncRoles(Player player, int score) {
        if (Bukkit.getPluginManager().getPlugin("DiscordSRV") == null) return;

        for (int threshold : positiveThresholds) {
            String path = "thresholds.positive." + threshold;
            String roleId = rolesConfig.getString(path + ".discord-role-id");
            if (roleId == null || roleId.equals("REPLACE_ROLE_ID")) continue;
            if (score >= threshold) {
                addDiscordRole(player, roleId);
                applyLpGroup(player, rolesConfig.getString(path + ".luckperms-group"));
            } else {
                removeDiscordRole(player, roleId);
                removeLpGroup(player, rolesConfig.getString(path + ".luckperms-group"));
            }
        }

        for (int threshold : negativeThresholds) {
            String path = "thresholds.negative." + threshold;
            String roleId = rolesConfig.getString(path + ".discord-role-id");
            if (roleId == null || roleId.equals("REPLACE_ROLE_ID")) continue;
            if (score <= threshold) {
                addDiscordRole(player, roleId);
                applyLpGroup(player, rolesConfig.getString(path + ".luckperms-group"));
            } else {
                removeDiscordRole(player, roleId);
                removeLpGroup(player, rolesConfig.getString(path + ".luckperms-group"));
            }
        }
    }

    // ── DiscordSRV reflection ─────────────────────────────────────

    private void addDiscordRole(Player player, String roleId) {
        modifyRole(player, roleId, true);
    }

    private void removeDiscordRole(Player player, String roleId) {
        modifyRole(player, roleId, false);
    }

    private void modifyRole(Player player, String roleId, boolean add) {
        try {
            Class<?> dsClass = Class.forName("github.scarsz.discordsrv.DiscordSRV");
            Object ds = dsClass.getMethod("getPlugin").invoke(null);

            Object alm = dsClass.getMethod("getAccountLinkManager").invoke(ds);
            String discordId = (String) alm.getClass()
                    .getMethod("getDiscordId", UUID.class).invoke(alm, player.getUniqueId());
            if (discordId == null) return;

            Object guild = dsClass.getMethod("getMainGuild").invoke(ds);
            Class<?> guildClass = guild.getClass();

            Object member = guildClass.getMethod("getMemberById", String.class).invoke(guild, discordId);
            if (member == null) return;

            Object role = guildClass.getMethod("getRoleById", String.class).invoke(guild, roleId);
            if (role == null) return;

            String method = add ? "addRoleToMember" : "removeRoleFromMember";
            Class<?> userSnowflake = Class.forName("net.dv8tion.jda.api.entities.UserSnowflake");
            Class<?> roleClass     = Class.forName("net.dv8tion.jda.api.entities.Role");
            Object audit = guildClass.getMethod(method, userSnowflake, roleClass).invoke(guild, member, role);
            audit.getClass().getMethod("queue").invoke(audit);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING,
                    "DiscordSRV role sync failed for " + player.getName() + ": " + e.getMessage());
        }
    }

    // ── LuckPerms ─────────────────────────────────────────────────

    private void applyLpGroup(Player player, String group) {
        if (group == null || group.isBlank()) return;
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                "lp user " + player.getName() + " parent add " + group);
    }

    private void removeLpGroup(Player player, String group) {
        if (group == null || group.isBlank()) return;
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                "lp user " + player.getName() + " parent remove " + group);
    }
}
