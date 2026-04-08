package dev.personality.command;

import dev.personality.PersonalityPlugin;
import dev.personality.gui.TopGUI;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Handles {@code /reputation top} (alias: /rep).
 *
 * <p>When executed by a {@link Player} the Top-10 GUI is opened.
 * When executed from the console a formatted chat list is printed instead.</p>
 */
public final class ReputationCommand implements CommandExecutor, TabCompleter {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final PersonalityPlugin plugin;

    public ReputationCommand(PersonalityPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("top")) {
            sender.sendMessage(MM.deserialize("<red>Usage: /" + label + " top"));
            return true;
        }

        // ── Console fallback: formatted chat output ────────────────
        if (!(sender instanceof Player player)) {
            printChatTop(sender);
            return true;
        }

        if (!player.hasPermission("playerinfo.use")) {
            player.sendMessage(MM.deserialize(plugin.getConfig()
                    .getString("messages.no-permission", "<red>No permission.")));
            return true;
        }

        TopGUI.open(plugin, player);
        return true;
    }

    private void printChatTop(CommandSender sender) {
        plugin.getDatabaseManager().getTopPlayers(10).thenAccept(entries -> {
            if (entries.isEmpty()) {
                sender.sendMessage(MM.deserialize("<gray>No reputation data yet."));
                return;
            }

            sender.sendMessage(MM.deserialize("<gold><bold>═══ Top Players (Reputation) ═══"));
            for (int i = 0; i < entries.size(); i++) {
                var entry  = entries.get(i);
                var op     = Bukkit.getOfflinePlayer(entry.uuid());
                String name  = op.getName() != null ? op.getName() : entry.uuid().toString().substring(0, 8);
                int    score = entry.score();
                String color = score >= 0 ? "<green>" : "<red>";

                sender.sendMessage(MM.deserialize(
                        "<yellow>#" + (i + 1) + " <white>" + name
                        + "  <gray>репутация: " + color + (score >= 0 ? "+" : "") + score
                ));
            }
            sender.sendMessage(MM.deserialize("<gold><bold>════════════════════════════════"));
        });
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                       String alias, String[] args) {
        if (args.length == 1) return List.of("top");
        return List.of();
    }
}
