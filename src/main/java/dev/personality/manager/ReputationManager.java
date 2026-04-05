package dev.personality.manager;

import dev.personality.PersonalityPlugin;
import dev.personality.database.DatabaseManager;
import dev.personality.model.VoteType;
import dev.personality.util.VoteLogger;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Business logic for the reputation system.
 *
 * <p>Caches reputation counts per target in a thread-safe map.
 * The cache is invalidated after every successful vote to ensure consistency.</p>
 */
public final class ReputationManager {

    private final PersonalityPlugin plugin;
    private final DatabaseManager   db;

    /** targetUuid → int[]{likes, dislikes} */
    private final ConcurrentMap<UUID, int[]> cache = new ConcurrentHashMap<>();

    public ReputationManager(PersonalityPlugin plugin, DatabaseManager db) {
        this.plugin = plugin;
        this.db     = db;
    }

    // ── Reputation queries ────────────────────────────────────────

    /**
     * Returns cached reputation counts, or fetches and caches them from the database.
     *
     * @return CompletableFuture containing int[]{likes, dislikes}
     */
    public CompletableFuture<int[]> getReputation(UUID targetUuid) {
        int[] cached = cache.get(targetUuid);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        return db.getReputationCounts(targetUuid).thenApply(counts -> {
            cache.put(targetUuid, counts);
            return counts;
        });
    }

    /**
     * Removes the cached reputation for the given target, forcing a fresh DB read next time.
     */
    public void invalidate(UUID targetUuid) {
        cache.remove(targetUuid);
    }

    // ── Vote logic ────────────────────────────────────────────────

    /**
     * Casts or changes a vote from {@code voterUuid} towards {@code targetUuid}.
     *
     * <p>Rules enforced here:</p>
     * <ul>
     *   <li>Players cannot vote for themselves.</li>
     *   <li>Submitting the same vote twice is a no-op ({@link VoteResult#UNCHANGED}).</li>
     *   <li>Submitting a different vote replaces the previous one.</li>
     * </ul>
     */
    public CompletableFuture<VoteResult> castVote(UUID voterUuid, UUID targetUuid, VoteType newVote) {
        if (voterUuid.equals(targetUuid)) {
            return CompletableFuture.completedFuture(VoteResult.SELF_VOTE);
        }

        return db.getVote(targetUuid, voterUuid).thenCompose(existing -> {
            if (existing == newVote) {
                return CompletableFuture.completedFuture(VoteResult.UNCHANGED);
            }

            return db.upsertVote(targetUuid, voterUuid, newVote).thenApply(ignored -> {
                // Invalidate so the next getReputation() fetches fresh counts.
                invalidate(targetUuid);

                if (plugin.getConfig().getBoolean("vote-log.enabled", false)) {
                    String file = plugin.getConfig().getString("vote-log.file", "vote-log.txt");
                    VoteLogger.log(plugin, file, voterUuid, targetUuid, newVote);
                }

                return existing == null ? VoteResult.CAST : VoteResult.CHANGED;
            });
        });
    }

    // ── Result enum ───────────────────────────────────────────────

    public enum VoteResult {
        /** Voter tried to vote for themselves. */
        SELF_VOTE,
        /** Voter already has this exact vote — no change. */
        UNCHANGED,
        /** A brand-new vote was recorded. */
        CAST,
        /** An existing vote was changed to the new type. */
        CHANGED
    }
}
