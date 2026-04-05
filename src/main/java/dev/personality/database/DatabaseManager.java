package dev.personality.database;

import dev.personality.PersonalityPlugin;
import dev.personality.model.VoteType;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

/**
 * Manages the SQLite database using a dedicated single-threaded executor.
 * All operations are non-blocking from the caller's perspective.
 *
 * <p>SQLite is not thread-safe with a shared connection; the single-thread executor
 * serialises all access, making synchronisation unnecessary.</p>
 */
public final class DatabaseManager {

    private final PersonalityPlugin plugin;
    private Connection connection;

    /**
     * Single-threaded executor — guarantees serialised, non-blocking DB access.
     */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "personality-database");
        t.setDaemon(true);
        return t;
    });

    public DatabaseManager(PersonalityPlugin plugin) {
        this.plugin = plugin;
    }

    // ── Lifecycle ─────────────────────────────────────────────────

    /**
     * Initialises the connection and creates tables. Called from the main thread on enable.
     * The initial setup is intentionally synchronous so the plugin doesn't continue loading
     * with an uninitialised database.
     */
    public void initialize() {
        try {
            plugin.getDataFolder().mkdirs();
            File dbFile = new File(plugin.getDataFolder(), "database.db");

            // The shade plugin rewrites "org.sqlite" references in bytecode but NOT in
            // String literals. We use the shaded class name in forName() and a raw file-path
            // URL so the JDBC URL prefix remains "jdbc:sqlite" regardless of relocation.
            Class.forName("dev.personality.libs.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());

            // WAL mode improves concurrent read performance and is safe for single-writer use.
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL");
                stmt.execute("PRAGMA synchronous=NORMAL");
                stmt.execute("PRAGMA foreign_keys=ON");
            }

            createTables();
            plugin.getLogger().info("Database initialised: " + dbFile.getPath());
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to initialise the database! The plugin will not function correctly.", e);
        }
    }

    private void createTables() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS players (
                    uuid       TEXT NOT NULL PRIMARY KEY,
                    first_join INTEGER NOT NULL
                )
            """);

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS reputation (
                    target_uuid TEXT    NOT NULL,
                    voter_uuid  TEXT    NOT NULL,
                    vote_type   TEXT    NOT NULL,
                    voted_at    INTEGER NOT NULL DEFAULT (strftime('%s', 'now')),
                    PRIMARY KEY (target_uuid, voter_uuid)
                )
            """);

            stmt.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_rep_target ON reputation (target_uuid)"
            );
        }
    }

    public void close() {
        executor.shutdown();
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Error closing database connection", e);
        }
    }

    // ── Players ───────────────────────────────────────────────────

    /**
     * Stores the player's first-join timestamp. Uses INSERT OR IGNORE so subsequent calls
     * for the same UUID are no-ops.
     */
    public CompletableFuture<Void> insertFirstJoin(UUID uuid, long timestampMillis) {
        return CompletableFuture.runAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT OR IGNORE INTO players (uuid, first_join) VALUES (?, ?)")) {
                ps.setString(1, uuid.toString());
                ps.setLong(2, timestampMillis);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "insertFirstJoin failed for " + uuid, e);
            }
        }, executor);
    }

    /**
     * Returns the stored first-join timestamp in milliseconds, or {@code -1} if unknown.
     */
    public CompletableFuture<Long> getFirstJoin(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT first_join FROM players WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getLong("first_join");
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "getFirstJoin failed for " + uuid, e);
            }
            return -1L;
        }, executor);
    }

    // ── Reputation ────────────────────────────────────────────────

    /**
     * Returns the vote the given voter has cast on the target, or {@code null} if none.
     */
    public CompletableFuture<VoteType> getVote(UUID targetUuid, UUID voterUuid) {
        return CompletableFuture.supplyAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT vote_type FROM reputation WHERE target_uuid = ? AND voter_uuid = ?")) {
                ps.setString(1, targetUuid.toString());
                ps.setString(2, voterUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return VoteType.valueOf(rs.getString("vote_type"));
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "getVote failed", e);
            }
            return null;
        }, executor);
    }

    /**
     * Inserts or replaces a vote entry. The timestamp is updated on each change.
     */
    public CompletableFuture<Void> upsertVote(UUID targetUuid, UUID voterUuid, VoteType voteType) {
        return CompletableFuture.runAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO reputation (target_uuid, voter_uuid, vote_type, voted_at)
                VALUES (?, ?, ?, strftime('%s', 'now'))
                ON CONFLICT (target_uuid, voter_uuid) DO UPDATE
                SET vote_type = excluded.vote_type,
                    voted_at  = excluded.voted_at
            """)) {
                ps.setString(1, targetUuid.toString());
                ps.setString(2, voterUuid.toString());
                ps.setString(3, voteType.name());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "upsertVote failed", e);
            }
        }, executor);
    }

    /**
     * Returns {@code int[2]} where index 0 = like count and index 1 = dislike count.
     */
    public CompletableFuture<int[]> getReputationCounts(UUID targetUuid) {
        return CompletableFuture.supplyAsync(() -> {
            int[] counts = {0, 0};
            try (PreparedStatement ps = connection.prepareStatement("""
                SELECT vote_type, COUNT(*) AS cnt
                FROM reputation
                WHERE target_uuid = ?
                GROUP BY vote_type
            """)) {
                ps.setString(1, targetUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String type = rs.getString("vote_type");
                        int cnt = rs.getInt("cnt");
                        if ("LIKE".equals(type))    counts[0] = cnt;
                        else if ("DISLIKE".equals(type)) counts[1] = cnt;
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "getReputationCounts failed for " + targetUuid, e);
            }
            return counts;
        }, executor);
    }

    /**
     * Returns the top {@code limit} players sorted by (likes - dislikes) descending.
     */
    public CompletableFuture<List<TopEntry>> getTopPlayers(int limit) {
        return CompletableFuture.supplyAsync(() -> {
            List<TopEntry> list = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement("""
                SELECT target_uuid,
                       SUM(CASE WHEN vote_type = 'LIKE'    THEN 1 ELSE 0 END) AS likes,
                       SUM(CASE WHEN vote_type = 'DISLIKE' THEN 1 ELSE 0 END) AS dislikes
                FROM reputation
                GROUP BY target_uuid
                ORDER BY (likes - dislikes) DESC
                LIMIT ?
            """)) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        UUID uuid     = UUID.fromString(rs.getString("target_uuid"));
                        int  likes    = rs.getInt("likes");
                        int  dislikes = rs.getInt("dislikes");
                        list.add(new TopEntry(uuid, likes, dislikes));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "getTopPlayers failed", e);
            }
            return list;
        }, executor);
    }

    // ── Data classes ──────────────────────────────────────────────

    public record TopEntry(UUID uuid, int likes, int dislikes) {
        public int score() {
            return likes - dislikes;
        }
    }
}
