-- ─────────────────────────────────────────────────────────────────
--  Personality Plugin — SQLite Schema
--  File: plugins/PlayerInfo/database.db
-- ─────────────────────────────────────────────────────────────────

PRAGMA journal_mode = WAL;
PRAGMA synchronous  = NORMAL;
PRAGMA foreign_keys = ON;

-- Stores the first-join timestamp for every player who has connected.
CREATE TABLE IF NOT EXISTS players (
    uuid       TEXT    NOT NULL PRIMARY KEY,   -- UUID string, e.g. "550e8400-e29b-41d4-..."
    first_join INTEGER NOT NULL                -- Unix epoch milliseconds
);

-- Stores one vote row per (target, voter) pair.
-- Primary key guarantees each voter can have at most one active vote per target.
-- Updating an existing vote uses ON CONFLICT ... DO UPDATE in application code.
CREATE TABLE IF NOT EXISTS reputation (
    target_uuid TEXT    NOT NULL,              -- UUID of the player being rated
    voter_uuid  TEXT    NOT NULL,              -- UUID of the player casting the vote
    vote_type   TEXT    NOT NULL               -- 'LIKE' or 'DISLIKE'
        CHECK (vote_type IN ('LIKE', 'DISLIKE')),
    voted_at    INTEGER NOT NULL               -- Unix epoch seconds (strftime('%s','now'))
        DEFAULT (strftime('%s', 'now')),
    PRIMARY KEY (target_uuid, voter_uuid)
);

-- Speeds up queries that aggregate votes for a single target (profile view, top list).
CREATE INDEX IF NOT EXISTS idx_rep_target ON reputation (target_uuid);
