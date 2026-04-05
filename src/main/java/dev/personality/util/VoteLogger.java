package dev.personality.util;

import dev.personality.PersonalityPlugin;
import dev.personality.model.VoteType;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Writes vote events to a plain-text log file asynchronously.
 * The log file is appended to, never truncated.
 */
public final class VoteLogger {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private VoteLogger() {}

    /**
     * Appends a vote entry to the configured log file on an async Bukkit thread.
     */
    public static void log(PersonalityPlugin plugin, String filename,
                           UUID voter, UUID target, VoteType type) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            File file = new File(plugin.getDataFolder(), filename);
            String line = String.format("[%s] VOTER=%s TARGET=%s TYPE=%s%n",
                    LocalDateTime.now().format(FMT),
                    voter,
                    target,
                    type.name());
            try (BufferedWriter bw = new BufferedWriter(new FileWriter(file, true))) {
                bw.write(line);
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to write vote log entry", e);
            }
        });
    }
}
