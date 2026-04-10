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
 */
public final class DatabaseManager {

    private final PersonalityPlugin plugin;
    private Connection connection;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "personality-database");
        t.setDaemon(true);
        return t;
    });

    public DatabaseManager(PersonalityPlugin plugin) {
        this.plugin = plugin;
    }

    // ── Lifecycle ─────────────────────────────────────────────────

    public void initialize() {
        try {
            plugin.getDataFolder().mkdirs();
            File dbFile = new File(plugin.getDataFolder(), "database.db");

            Class.forName("dev.personality.libs.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());

            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL");
                stmt.execute("PRAGMA synchronous=NORMAL");
                stmt.execute("PRAGMA foreign_keys=ON");
            }

            createTables();
            plugin.getLogger().info("Database initialised: " + dbFile.getPath());
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to initialise the database!", e);
        }
    }

    private void createTables() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            // Player first-join tracking
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS players (
                    uuid       TEXT NOT NULL PRIMARY KEY,
                    first_join INTEGER NOT NULL
                )
            """);

            // Numeric reputation score per player
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS player_scores (
                    uuid  TEXT NOT NULL PRIMARY KEY,
                    score INTEGER NOT NULL DEFAULT 0
                )
            """);

            // Cooldown tracking: when did [giver] last give rep to [target]
            // last_direction: LIKE (+1) or DISLIKE (-1) — stored for display only
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS rep_cooldowns (
                    giver     TEXT NOT NULL,
                    target    TEXT NOT NULL,
                    last_time INTEGER NOT NULL,
                    last_dir  TEXT NOT NULL DEFAULT 'LIKE',
                    PRIMARY KEY (giver, target)
                )
            """);

            // Friends (bidirectional — stored as min:max pair)
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS friends (
                    uuid_a TEXT NOT NULL,
                    uuid_b TEXT NOT NULL,
                    since  INTEGER NOT NULL DEFAULT (strftime('%s','now')),
                    PRIMARY KEY (uuid_a, uuid_b)
                )
            """);
            stmt.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_friends_b ON friends (uuid_b)"
            );

            // Legacy table kept for schema compatibility — not used for logic anymore
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
            if (connection != null && !connection.isClosed()) connection.close();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Error closing database connection", e);
        }
    }

    // ── Players ───────────────────────────────────────────────────

    public CompletableFuture<Void> insertFirstJoin(UUID uuid, long timestampMillis) {
        return CompletableFuture.runAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT OR IGNORE INTO players (uuid, first_join) VALUES (?, ?)")) {
                ps.setString(1, uuid.toString());
                ps.setLong(2, timestampMillis);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "insertFirstJoin failed", e);
            }
        }, executor);
    }

    public CompletableFuture<Long> getFirstJoin(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT first_join FROM players WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getLong("first_join");
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "getFirstJoin failed", e);
            }
            return -1L;
        }, executor);
    }

    // ── Reputation score ──────────────────────────────────────────

    public CompletableFuture<Integer> getScore(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT score FROM player_scores WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getInt("score");
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "getScore failed", e);
            }
            return 0;
        }, executor);
    }

    public CompletableFuture<Void> addScore(UUID uuid, int delta) {
        return CompletableFuture.runAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO player_scores (uuid, score) VALUES (?, ?)
                ON CONFLICT(uuid) DO UPDATE SET score = score + excluded.score
                """)) {
                ps.setString(1, uuid.toString());
                ps.setInt(2, delta);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "addScore failed", e);
            }
        }, executor);
    }

    public CompletableFuture<Void> setScore(UUID uuid, int score) {
        return CompletableFuture.runAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO player_scores (uuid, score) VALUES (?, ?)
                ON CONFLICT(uuid) DO UPDATE SET score = excluded.score
                """)) {
                ps.setString(1, uuid.toString());
                ps.setInt(2, score);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "setScore failed", e);
            }
        }, executor);
    }

    // ── Cooldown ──────────────────────────────────────────────────

    /** Returns last rep-give timestamp in ms, or 0 if never. */
    public CompletableFuture<Long> getLastRepTime(UUID giver, UUID target) {
        return CompletableFuture.supplyAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT last_time FROM rep_cooldowns WHERE giver = ? AND target = ?")) {
                ps.setString(1, giver.toString());
                ps.setString(2, target.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getLong("last_time");
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "getLastRepTime failed", e);
            }
            return 0L;
        }, executor);
    }

    public CompletableFuture<Void> setLastRepTime(UUID giver, UUID target, long time, VoteType dir) {
        return CompletableFuture.runAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO rep_cooldowns (giver, target, last_time, last_dir) VALUES (?, ?, ?, ?)
                ON CONFLICT(giver, target) DO UPDATE SET last_time = excluded.last_time, last_dir = excluded.last_dir
                """)) {
                ps.setString(1, giver.toString());
                ps.setString(2, target.toString());
                ps.setLong(3, time);
                ps.setString(4, dir.name());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "setLastRepTime failed", e);
            }
        }, executor);
    }

    // ── Top players ───────────────────────────────────────────────

    public CompletableFuture<List<TopEntry>> getTopPlayers(int limit) {
        return CompletableFuture.supplyAsync(() -> {
            List<TopEntry> list = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement("""
                SELECT uuid, score FROM player_scores ORDER BY score DESC LIMIT ?
                """)) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        UUID uuid  = UUID.fromString(rs.getString("uuid"));
                        int  score = rs.getInt("score");
                        list.add(new TopEntry(uuid, score));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "getTopPlayers failed", e);
            }
            return list;
        }, executor);
    }

    // ── Friends ───────────────────────────────────────────────────

    private static String minUuid(UUID a, UUID b) { return a.compareTo(b) <= 0 ? a.toString() : b.toString(); }
    private static String maxUuid(UUID a, UUID b) { return a.compareTo(b) <= 0 ? b.toString() : a.toString(); }

    public CompletableFuture<List<UUID>> getFriends(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            List<UUID> list = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT uuid_a, uuid_b FROM friends WHERE uuid_a = ? OR uuid_b = ?")) {
                String s = uuid.toString();
                ps.setString(1, s); ps.setString(2, s);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        UUID a = UUID.fromString(rs.getString("uuid_a"));
                        UUID b = UUID.fromString(rs.getString("uuid_b"));
                        list.add(a.equals(uuid) ? b : a);
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "getFriends failed", e);
            }
            return list;
        }, executor);
    }

    public CompletableFuture<Void> addFriend(UUID a, UUID b) {
        return CompletableFuture.runAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT OR IGNORE INTO friends (uuid_a, uuid_b) VALUES (?, ?)")) {
                ps.setString(1, minUuid(a, b)); ps.setString(2, maxUuid(a, b));
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "addFriend failed", e);
            }
        }, executor);
    }

    public CompletableFuture<Void> removeFriend(UUID a, UUID b) {
        return CompletableFuture.runAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM friends WHERE uuid_a = ? AND uuid_b = ?")) {
                ps.setString(1, minUuid(a, b)); ps.setString(2, maxUuid(a, b));
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "removeFriend failed", e);
            }
        }, executor);
    }

    // ── Legacy vote methods (kept for VoteLogger compatibility) ───

    public CompletableFuture<VoteType> getVote(UUID targetUuid, UUID voterUuid) {
        return CompletableFuture.completedFuture(null); // not used in new system
    }

    // ── Data classes ──────────────────────────────────────────────

    public record TopEntry(UUID uuid, int score) {}
}
