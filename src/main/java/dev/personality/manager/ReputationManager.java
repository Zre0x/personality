package dev.personality.manager;

import dev.personality.PersonalityPlugin;
import dev.personality.database.DatabaseManager;
import dev.personality.model.VoteType;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Business logic for the reputation system.
 *
 * Score is a cumulative integer. Players can give +1 or -1 to another player
 * once per cooldown period (default 24 h). No persistent "one-time vote".
 */
public final class ReputationManager {

    private final PersonalityPlugin plugin;
    private final DatabaseManager   db;

    /** Cache: targetUuid → score */
    private final ConcurrentMap<UUID, Integer> scoreCache = new ConcurrentHashMap<>();

    public ReputationManager(PersonalityPlugin plugin, DatabaseManager db) {
        this.plugin = plugin;
        this.db     = db;
    }

    // ── Score queries ─────────────────────────────────────────────

    public CompletableFuture<Integer> getScore(UUID targetUuid) {
        Integer cached = scoreCache.get(targetUuid);
        if (cached != null) return CompletableFuture.completedFuture(cached);
        return db.getScore(targetUuid).thenApply(score -> {
            scoreCache.put(targetUuid, score);
            return score;
        });
    }

    public void invalidate(UUID targetUuid) {
        scoreCache.remove(targetUuid);
    }

    // ── Cooldown helpers ──────────────────────────────────────────

    private long cooldownMs() {
        return plugin.getConfig().getLong("reputation.cooldown-seconds", 86400) * 1000L;
    }

    /** Returns ms remaining on cooldown, or 0 if available. */
    public CompletableFuture<Long> cooldownRemaining(UUID giver, UUID target) {
        return db.getLastRepTime(giver, target).thenApply(last -> {
            long elapsed = System.currentTimeMillis() - last;
            long remaining = cooldownMs() - elapsed;
            return Math.max(0, remaining);
        });
    }

    // ── Give rep ──────────────────────────────────────────────────

    public enum RepResult { OK, SELF, ON_COOLDOWN }

    /**
     * Give +1 (LIKE) or -1 (DISLIKE) reputation from giver to target.
     * Checks cooldown; applies score; triggers Discord sync.
     */
    public CompletableFuture<RepResult> giveRep(UUID giverUuid, UUID targetUuid, VoteType direction) {
        if (giverUuid.equals(targetUuid)) {
            return CompletableFuture.completedFuture(RepResult.SELF);
        }

        return db.getLastRepTime(giverUuid, targetUuid).thenCompose(last -> {
            long elapsed = System.currentTimeMillis() - last;
            if (elapsed < cooldownMs()) {
                return CompletableFuture.completedFuture(RepResult.ON_COOLDOWN);
            }

            int delta = direction == VoteType.LIKE ? 1 : -1;

            return db.addScore(targetUuid, delta)
                    .thenCompose(v -> db.setLastRepTime(giverUuid, targetUuid,
                            System.currentTimeMillis(), direction))
                    .thenCompose(v -> {
                        invalidate(targetUuid);
                        return db.getScore(targetUuid);
                    })
                    .thenApply(newScore -> {
                        scoreCache.put(targetUuid, newScore);
                        // Discord sync — runs on calling thread (async is fine here)
                        plugin.getDiscordSync().syncRolesAsync(targetUuid, newScore);
                        return RepResult.OK;
                    });
        });
    }

    // ── Admin operations ──────────────────────────────────────────

    public CompletableFuture<Void> adminSet(UUID uuid, int score) {
        invalidate(uuid);
        return db.setScore(uuid, score).thenRun(() -> {
            scoreCache.put(uuid, score);
            plugin.getDiscordSync().syncRolesAsync(uuid, score);
        });
    }

    public CompletableFuture<Void> adminAdd(UUID uuid, int delta) {
        return getScore(uuid).thenCompose(cur -> adminSet(uuid, cur + delta));
    }

    // ── Legacy compat (used by ProfileGUI — returns int[]{score,0}) ──

    /** @deprecated Use {@link #getScore(UUID)} directly. */
    @Deprecated
    public CompletableFuture<int[]> getReputation(UUID targetUuid) {
        return getScore(targetUuid).thenApply(s -> new int[]{s, 0});
    }
}
