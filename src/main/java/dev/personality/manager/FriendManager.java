package dev.personality.manager;

import dev.personality.PersonalityPlugin;
import dev.personality.database.DatabaseManager;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class FriendManager {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final PersonalityPlugin plugin;
    private final DatabaseManager   db;

    // Cache: uuid → set of friend uuids
    private final Map<UUID, Set<UUID>> cache = new ConcurrentHashMap<>();

    public FriendManager(PersonalityPlugin plugin, DatabaseManager db) {
        this.plugin = plugin;
        this.db     = db;
    }

    // ── Cache helpers ─────────────────────────────────────────────

    private Set<UUID> cached(UUID uuid) {
        return cache.computeIfAbsent(uuid, k -> ConcurrentHashMap.newKeySet());
    }

    public void invalidate(UUID uuid) {
        cache.remove(uuid);
    }

    // ── API ───────────────────────────────────────────────────────

    public CompletableFuture<Boolean> areFriends(UUID a, UUID b) {
        if (a.equals(b)) return CompletableFuture.completedFuture(false);
        Set<UUID> set = cache.get(a);
        if (set != null) return CompletableFuture.completedFuture(set.contains(b));
        return db.getFriends(a).thenApply(friends -> {
            cached(a).addAll(friends);
            return friends.contains(b);
        });
    }

    public CompletableFuture<List<UUID>> getFriends(UUID uuid) {
        Set<UUID> set = cache.get(uuid);
        if (set != null) return CompletableFuture.completedFuture(new ArrayList<>(set));
        return db.getFriends(uuid).thenApply(friends -> {
            cached(uuid).addAll(friends);
            return friends;
        });
    }

    public enum FriendResult { ADDED, REMOVED, SELF, ALREADY_FRIENDS, NOT_FRIENDS }

    public CompletableFuture<FriendResult> toggle(UUID sender, UUID target) {
        if (sender.equals(target)) return CompletableFuture.completedFuture(FriendResult.SELF);
        return areFriends(sender, target).thenCompose(already -> {
            if (already) {
                return db.removeFriend(sender, target).thenApply(v -> {
                    cached(sender).remove(target);
                    cached(target).remove(sender);
                    return FriendResult.REMOVED;
                });
            } else {
                return db.addFriend(sender, target).thenApply(v -> {
                    cached(sender).add(target);
                    cached(target).add(sender);
                    return FriendResult.ADDED;
                });
            }
        });
    }

    // ── Join notification ─────────────────────────────────────────

    public void notifyFriendsOnJoin(Player joined) {
        UUID uuid = joined.getUniqueId();
        getFriends(uuid).thenAccept(friends -> {
            String msg = plugin.getConfig().getString("messages.friend-joined",
                    "<green>➤ <white>{player} <green>вошёл на сервер")
                    .replace("{player}", joined.getName());
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (UUID friendUuid : friends) {
                    Player friend = Bukkit.getPlayer(friendUuid);
                    if (friend != null) friend.sendMessage(MM.deserialize(msg));
                }
            });
        });
    }
}
