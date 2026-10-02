package com.alechilles.alecstamework.companion.migrate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One old SQLite file, opened for the importer without touching the original (plan 7 Global
 * Constraints, R2). The file and its {@code -wal} are copied into a scratch folder and only the
 * copy is opened, so a write-ahead log left by a crashed server is replayed there and no file
 * appears beside the original. Closing deletes the copy.
 */
public final class LegacySource implements AutoCloseable {
    public static final String STATE_FILE = "tamework-state.sqlite";
    public static final String BONDED_FILE = "bonded-companions.sqlite";

    /** Sidecars SQLite may create next to the copy; removed with it. */
    private static final List<String> SIDECARS = List.of("-wal", "-shm", "-journal");
    /** The sidecars worth copying: committed pages not yet in the main file, and their index. */
    private static final List<String> COPIED_SIDECARS = List.of("-wal", "-shm");
    private static final String ROUTED_V2_HASH =
            "b72b00e5e77277f936866aa2f20555c3d35473379e40b6608890b9f0a382d5d7";

    /** Why a source was refused. The import writes nothing in every case (R2). */
    public enum Reason {
        /** The schema history is missing or names a version this importer does not know. */
        UNKNOWN_VERSION,
        /** {@code PRAGMA integrity_check} reported damage. */
        INTEGRITY_FAILED,
        /** The file could not be copied, opened or queried. */
        UNREADABLE
    }

    /** A source the importer must not use. {@link #file()} is the original, never the copy. */
    public static final class Refused extends Exception {
        private final Reason reason;
        private final Path file;

        Refused(@Nonnull Reason reason, @Nonnull Path file, @Nonnull String detail, @Nullable Throwable cause) {
            super(reason + " " + file + ": " + detail, cause);
            this.reason = reason;
            this.file = file;
        }

        @Nonnull public Reason reason() { return reason; }
        @Nonnull public Path file() { return file; }
    }

    /** The old files found in a world; either may be {@code null}. */
    public record Located(@Nullable Path stateFile, @Nullable Path bondedFile) {
        public boolean isEmpty() {
            return stateFile == null && bondedFile == null;
        }
    }

    private final LegacyRows.SourceFile original;
    private final Path copy;
    private final Connection connection;

    private LegacySource(LegacyRows.SourceFile original, Path copy, Connection connection) {
        this.original = original;
        this.copy = copy;
        this.connection = connection;
    }

    /**
     * Finds the old files in the directories {@code CompanionStorage.detectLegacy} checks, in the
     * same order; the first directory holding a file wins. Only the exact file names match, so a
     * backup such as {@code tamework-state.sqlite.v1-backup.<uuid>.sqlite} is never chosen.
     */
    @Nonnull
    public static Located locate(@Nonnull Collection<Path> legacyDirs) {
        return new Located(find(legacyDirs, STATE_FILE), find(legacyDirs, BONDED_FILE));
    }

    @Nullable
    private static Path find(Collection<Path> legacyDirs, String name) {
        for (Path dir : legacyDirs) {
            if (dir != null && Files.isRegularFile(dir.resolve(name))) {
                return dir.resolve(name);
            }
        }
        return null;
    }

    /**
     * Copies {@code file} into {@code scratchDir}, opens the copy read-only and checks its
     * integrity. {@code file} must be named {@link #STATE_FILE} or {@link #BONDED_FILE}.
     */
    @Nonnull
    public static LegacySource open(@Nonnull Path file, @Nonnull Path scratchDir) throws Refused {
        String name = file.getFileName().toString();
        if (!name.equals(STATE_FILE) && !name.equals(BONDED_FILE)) {
            throw new Refused(Reason.UNREADABLE, file, "not an import source name", null);
        }
        Path copy = scratchDir.resolve(name);
        Connection connection = null;
        try {
            LegacyRows.SourceFile original = new LegacyRows.SourceFile(
                    file, Files.size(file), Files.getLastModifiedTime(file).toMillis());
            Files.createDirectories(scratchDir);
            deleteCopy(copy);
            Files.copy(file, copy, StandardCopyOption.REPLACE_EXISTING);
            for (String suffix : COPIED_SIDECARS) {
                Path sidecar = file.resolveSibling(name + suffix);
                if (Files.isRegularFile(sidecar)) {
                    Files.copy(sidecar, copy.resolveSibling(name + suffix), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + copy.toUri() + "?mode=ro");
            List<String> problems = integrityProblems(connection);
            if (!problems.isEmpty()) {
                throw new Refused(Reason.INTEGRITY_FAILED, file, String.join("; ", problems), null);
            }
            return new LegacySource(original, copy, connection);
        } catch (IOException | SQLException | ClassNotFoundException | LinkageError failure) {
            release(connection, copy);
            throw new Refused(Reason.UNREADABLE, file, String.valueOf(failure.getMessage()), failure);
        } catch (Refused | RuntimeException failure) {
            release(connection, copy);
            throw failure;
        }
    }

    private static List<String> integrityProblems(Connection connection) throws SQLException {
        List<String> problems = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA integrity_check")) {
            while (rows.next()) {
                String line = rows.getString(1);
                if (!"ok".equalsIgnoreCase(line)) {
                    problems.add(line);
                }
            }
        }
        return problems;
    }

    /** The original file's path, size and modified time, taken before the copy. */
    @Nonnull
    public LegacyRows.SourceFile original() {
        return original;
    }

    /** A read-only connection to the copy. Owned by this source; do not close it. */
    @Nonnull
    Connection connection() {
        return connection;
    }

    /** Which released shape a state file has, from {@code schema_history}. */
    @Nonnull
    LegacyRows.StateSchema stateSchema() throws Refused {
        List<Integer> versions = new ArrayList<>();
        String latestHash = null;
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT version, lineage, schema_hash FROM schema_history ORDER BY version")) {
            while (rows.next()) {
                if (!"tamework-state".equals(rows.getString("lineage"))) {
                    throw unknownVersion("lineage " + rows.getString("lineage"));
                }
                versions.add(rows.getInt("version"));
                latestHash = rows.getString("schema_hash");
            }
        } catch (SQLException failure) {
            throw new Refused(Reason.UNKNOWN_VERSION, original.path(), "no readable schema_history", failure);
        }
        if (versions.equals(List.of(1))) {
            return LegacyRows.StateSchema.V1;
        }
        if (versions.equals(List.of(2)) || versions.equals(List.of(1, 2))) {
            return ROUTED_V2_HASH.equals(latestHash) ? LegacyRows.StateSchema.ROUTED_V2 : LegacyRows.StateSchema.V2;
        }
        throw unknownVersion("schema versions " + versions);
    }

    /** Refuses a bonded file whose history is not exactly version 1. */
    void requireBondedSchema() throws Refused {
        List<Integer> versions = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT version, lineage FROM bonded_schema_history ORDER BY version")) {
            while (rows.next()) {
                if (!"bonded-companions".equals(rows.getString("lineage"))) {
                    throw unknownVersion("lineage " + rows.getString("lineage"));
                }
                versions.add(rows.getInt("version"));
            }
        } catch (SQLException failure) {
            throw new Refused(Reason.UNKNOWN_VERSION, original.path(), "no readable bonded_schema_history", failure);
        }
        if (!versions.equals(List.of(1))) {
            throw unknownVersion("schema versions " + versions);
        }
    }

    private Refused unknownVersion(String detail) {
        return new Refused(Reason.UNKNOWN_VERSION, original.path(), detail, null);
    }

    /** Closes the connection and deletes the scratch copy with its sidecars. */
    @Override
    public void close() {
        release(connection, copy);
    }

    private static void release(@Nullable Connection connection, Path copy) {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // Nothing was written through this connection; the copy is deleted next.
            }
        }
        try {
            deleteCopy(copy);
        } catch (IOException ignored) {
            // A leftover copy is harmless: the caller owns and removes the scratch folder.
        }
    }

    private static void deleteCopy(Path copy) throws IOException {
        Files.deleteIfExists(copy);
        for (String suffix : SIDECARS) {
            Files.deleteIfExists(copy.resolveSibling(copy.getFileName() + suffix));
        }
    }
}
