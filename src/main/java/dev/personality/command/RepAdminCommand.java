package dev.personality.command;

import dev.personality.PersonalityPlugin;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * /repadmin set <player> <value>
 * /repadmin add <player> <value>
 * /repadmin reset <player>
 * /repadmin info <player>
 * /repadmin syncall
 * /repadmin reload
 */
public final class RepAdminCommand implements CommandExecutor, TabCompleter {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final PersonalityPlugin plugin;

    public RepAdminCommand(PersonalityPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("playerinfo.admin")) {
            sender.sendMessage(MM.deserialize("<red>Нет доступа."));
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> {
                plugin.reloadConfig();
                plugin.getDiscordSync().load();
                sender.sendMessage(MM.deserialize("<green>Конфиг перезагружен."));
            }
            case "syncall" -> {
                Bukkit.getOnlinePlayers().forEach(p ->
                    plugin.getReputationManager().getScore(p.getUniqueId())
                        .thenAccept(score ->
                            plugin.getDiscordSync().syncRoles(p, score)));
                sender.sendMessage(MM.deserialize("<green>Синхронизация запущена."));
            }
            case "info" -> {
                if (args.length < 2) { sendHelp(sender); return true; }
                UUID uuid = resolveUuid(args[1]);
                if (uuid == null) { sender.sendMessage(MM.deserialize("<red>Игрок не найден.")); return true; }
                plugin.getReputationManager().getScore(uuid).thenAccept(score ->
                    Bukkit.getScheduler().runTask(plugin, () ->
                        sender.sendMessage(MM.deserialize(
                            "<gray>Репутация <white>" + args[1] + "<gray>: <white>" + score))));
            }
            case "set" -> {
                if (args.length < 3) { sendHelp(sender); return true; }
                UUID uuid = resolveUuid(args[1]);
                if (uuid == null) { sender.sendMessage(MM.deserialize("<red>Игрок не найден.")); return true; }
                try {
                    int val = Integer.parseInt(args[2]);
                    plugin.getReputationManager().adminSet(uuid, val).thenRun(() ->
                        Bukkit.getScheduler().runTask(plugin, () ->
                            sender.sendMessage(MM.deserialize(
                                "<green>Репутация <white>" + args[1] + "<green> = <white>" + val))));
                } catch (NumberFormatException e) {
                    sender.sendMessage(MM.deserialize("<red>Неверное число."));
                }
            }
            case "add" -> {
                if (args.length < 3) { sendHelp(sender); return true; }
                UUID uuid = resolveUuid(args[1]);
                if (uuid == null) { sender.sendMessage(MM.deserialize("<red>Игрок не найден.")); return true; }
                try {
                    int delta = Integer.parseInt(args[2]);
                    plugin.getReputationManager().adminAdd(uuid, delta).thenRun(() ->
                        plugin.getReputationManager().getScore(uuid).thenAccept(newScore ->
                            Bukkit.getScheduler().runTask(plugin, () ->
                                sender.sendMessage(MM.deserialize(
                                    "<green>Репутация <white>" + args[1]
                                    + "<green> изменена на <white>" + (delta >= 0 ? "+" : "") + delta
                                    + "<green>, теперь: <white>" + newScore)))));
                } catch (NumberFormatException e) {
                    sender.sendMessage(MM.deserialize("<red>Неверное число."));
                }
            }
            case "reset" -> {
                if (args.length < 2) { sendHelp(sender); return true; }
                UUID uuid = resolveUuid(args[1]);
                if (uuid == null) { sender.sendMessage(MM.deserialize("<red>Игрок не найден.")); return true; }
                plugin.getReputationManager().adminSet(uuid, 0).thenRun(() ->
                    Bukkit.getScheduler().runTask(plugin, () ->
                        sender.sendMessage(MM.deserialize(
                            "<green>Репутация <white>" + args[1] + "<green> сброшена."))));
            }
            default -> sendHelp(sender);
        }
        return true;
    }

    private UUID resolveUuid(String name) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(name);
        return op.hasPlayedBefore() || op.isOnline() ? op.getUniqueId() : null;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(MM.deserialize("<gold>/repadmin set <игрок> <число>"));
        sender.sendMessage(MM.deserialize("<gold>/repadmin add <игрок> <число>"));
        sender.sendMessage(MM.deserialize("<gold>/repadmin reset <игрок>"));
        sender.sendMessage(MM.deserialize("<gold>/repadmin info <игрок>"));
        sender.sendMessage(MM.deserialize("<gold>/repadmin syncall"));
        sender.sendMessage(MM.deserialize("<gold>/repadmin reload"));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd,
                                       @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) return List.of("set", "add", "reset", "info", "syncall", "reload");
        return List.of();
    }
}
