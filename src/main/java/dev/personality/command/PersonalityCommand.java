package dev.personality.command;

import dev.personality.PersonalityPlugin;
import dev.personality.gui.ProfileGUI;
import dev.personality.util.PlaceholderUtil;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Handles {@code /personality <player>} (aliases: /profile, /playerinfo).
 *
 * <p>Looks up the player online first; if not found, falls back to the
 * {@link OfflinePlayer} registry so profiles can be viewed for offline players.</p>
 */
public final class PersonalityCommand implements CommandExecutor, TabCompleter {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final PersonalityPlugin plugin;

    public PersonalityCommand(PersonalityPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by players.");
            return true;
        }

        if (!player.hasPermission("playerinfo.use")) {
            player.sendMessage(MM.deserialize(plugin.getConfig()
                    .getString("messages.no-permission", "<red>No permission.")));
            return true;
        }

        if (args.length == 0) {
            player.sendMessage(MM.deserialize("<red>Usage: /" + label + " <player>"));
            return true;
        }

        String targetName = args[0];

        // ── Try online player first (exact match, case-insensitive) ──
        Player online = Bukkit.getPlayerExact(targetName);
        if (online != null) {
            if (PlaceholderUtil.isMasked(plugin, online)) return true; // silent
            ProfileGUI.open(plugin, player, online);
            return true;
        }

        // ── Fall back to offline lookup ────────────────────────────
        // getOfflinePlayer(String) is deprecated but still the standard way to do this.
        // It may perform a blocking web request for unmapped usernames; acceptable here
        // since this is an admin-style command, not a hot-path operation.
        @SuppressWarnings("deprecation")
        OfflinePlayer offlineTarget = Bukkit.getOfflinePlayer(targetName);

        if (!offlineTarget.hasPlayedBefore()) {
            String msg = plugin.getConfig()
                    .getString("messages.player-not-found", "<red>Player not found: <white>{player}")
                    .replace("{player}", targetName);
            player.sendMessage(MM.deserialize(msg));
            return true;
        }

        ProfileGUI.open(plugin, player, offlineTarget);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                       String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(n -> n.toLowerCase().startsWith(prefix))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
