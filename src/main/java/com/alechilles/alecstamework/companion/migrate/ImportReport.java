package com.alechilles.alecstamework.companion.migrate;

import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.LocationKind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The operator's report of one 3.x/4.x import (spec 12.2): a plain text file
 * {@code import-report-<timestamp>.txt} in the Tamework data folder, and the one-line summary
 * for the console. The file is a log for server operators, so it is English like the console.
 * A failed import gets a short report under the same name pattern, so the operator notice can
 * name a file in both cases.
 */
public final class ImportReport {
    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    /** The one report of a failed import; each failed start overwrites it, so files do not pile up. */
    public static final String FAILURE_FILE_NAME = "import-report-failed.txt";

    /** One old file the import read. {@code schema} is the released schema it has, for example {@code V2}. */
    public record Source(@Nonnull LegacyRows.SourceFile file, @Nonnull String schema) {
    }

    /**
     * What the importer found besides the files it read.
     *
     * @param sources        the files that were read
     * @param ignoredCopies  files with an import source's name in a later data folder; the first
     *                       folder that holds a name wins
     * @param ignoredLegacy2x 2.x files on the same world; 3.x/4.x data wins over them (spec 12.1)
     */
    public record Sources(@Nonnull List<Source> sources, @Nonnull List<Path> ignoredCopies,
                          @Nonnull List<Path> ignoredLegacy2x) {
        public Sources {
            sources = List.copyOf(sources);
            ignoredCopies = List.copyOf(ignoredCopies);
            ignoredLegacy2x = List.copyOf(ignoredLegacy2x);
        }
    }

    private ImportReport() {
    }

    /** {@code import-report-<UTC yyyyMMdd-HHmmss>.txt} for the import that started at {@code atMs}. */
    @Nonnull
    public static String fileName(long atMs) {
        return "import-report-" + FILE_STAMP.format(Instant.ofEpochMilli(atMs)) + ".txt";
    }

    /** Writes {@code text} as {@code fileName} in {@code directory}, replacing an older file, and returns the file. */
    @Nonnull
    public static Path write(@Nonnull Path directory, @Nonnull String fileName, @Nonnull String text)
            throws IOException {
        Files.createDirectories(directory);
        Path file = directory.resolve(fileName);
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }

    /** The one console line for a finished import: the totals, then every report list or counter that is not empty. */
    @Nonnull
    public static String summary(@Nonnull ImportResult result, long durationMs) {
        StringBuilder out = new StringBuilder("Imported ").append(result.records().size())
                .append(" companions from Tamework 3.x/4.x data in ").append(durationMs).append(" ms: ")
                .append(byLocation(result)).append("; ").append(result.snapshots().size()).append(" snapshots, ")
                .append(result.aliases().size()).append(" old body ids");
        for (Line line : lines(result.report())) {
            if (line.count() != 0) {
                out.append(", ").append(line.title().toLowerCase(Locale.ROOT)).append(' ').append(line.count());
            }
        }
        return out.append('.').toString();
    }

    /** The report of a finished import. */
    @Nonnull
    public static String success(@Nonnull Sources sources, @Nonnull ImportResult result, @Nonnull Path storeRoot,
                                 long atMs, long durationMs, @Nonnull String tameworkVersion) {
        StringBuilder out = header("imported", atMs, tameworkVersion);
        out.append("New companion store: ").append(storeRoot).append('\n');
        out.append("Duration: ").append(durationMs).append(" ms\n\n");
        appendSources(out, sources);

        out.append("\nImported\n");
        out.append("  Companions: ").append(result.records().size()).append(" (")
                .append(result.records().stream().filter(CompanionRecord::bonded).count()).append(" bonded)\n");
        out.append("  By location: ").append(byLocation(result)).append('\n');
        out.append("  Snapshots: ").append(result.snapshots().size()).append('\n');
        out.append("  Old body ids in ").append(LegacyAliases.FILE_NAME).append(": ")
                .append(result.aliases().size()).append('\n');

        out.append("\nNotes\n");
        out.append("  Provider claims: none were imported. Limits that another mod's provider enforces do not count an "
                + "imported companion until its next change (for example a summon, a store or a capture).\n");
        for (Line line : lines(result.report())) {
            out.append("  ").append(line.title()).append(" (").append(line.count()).append("): ")
                    .append(line.meaning()).append('\n');
            for (Object item : line.items()) {
                out.append("    ").append(item instanceof ImportResult.Skipped skipped
                        ? skipped.table() + ", " + skipped.key() + ", " + skipped.reason() + ": "
                                + skipReason(skipped.reason())
                        : String.valueOf(item)).append('\n');
            }
        }
        out.append("\nIf this import has to be repeated\n");
        out.append("  Stop the server and delete the folder ").append(storeRoot).append(" while the old database "
                + "files are still in place. The next start then imports again. Everything that happened to "
                + "companions after this import is lost when you do that.\n");
        return out.toString();
    }

    /** The short report of an import that wrote nothing. */
    @Nonnull
    public static String failure(@Nullable Sources sources, @Nonnull String reason, @Nonnull Path storeRoot,
                                 long atMs, @Nonnull String tameworkVersion) {
        StringBuilder out = header("FAILED, nothing was imported", atMs, tameworkVersion);
        out.append("Reason: ").append(reason).append("\n\n");
        out.append("No companion store was created at ").append(storeRoot).append(" and the old files were not "
                + "changed. Companion saving, capture, recall and the companion panel stay off until this is "
                + "resolved.\n\n");
        out.append("What you can do\n");
        out.append("  1. Fix the cause above (for example free disk space, or restore the old database from a "
                + "backup) and restart the server. The import runs again at every start until it succeeds.\n");
        out.append("  2. Or give up the old companion data: run /tw persistence start-fresh and restart. The old "
                + "files stay where they are.\n");
        out.append("  The server log has the full error.\n");
        if (sources != null) {
            out.append('\n');
            appendSources(out, sources);
        }
        return out.toString();
    }

    private static StringBuilder header(String result, long atMs, String tameworkVersion) {
        return new StringBuilder("Tamework companion import report\n")
                .append("Result: ").append(result).append('\n')
                .append("Time: ").append(Instant.ofEpochMilli(atMs)).append(" (").append(atMs).append(")\n")
                .append("Tamework version: ").append(tameworkVersion).append('\n');
    }

    private static void appendSources(StringBuilder out, Sources sources) {
        out.append("Source files (read only; none was changed or deleted)\n");
        for (Source source : sources.sources()) {
            LegacyRows.SourceFile file = source.file();
            out.append("  ").append(file.path()).append(", ").append(file.sizeBytes()).append(" bytes, modified ")
                    .append(Instant.ofEpochMilli(file.lastModifiedMs())).append(", schema ").append(source.schema())
                    .append('\n');
        }
        for (Path ignored : sources.ignoredCopies()) {
            out.append("  Also found and ignored: ").append(ignored).append(". Another data folder holds a file of "
                    + "the same name and the first folder wins.\n");
        }
        for (Path ignored : sources.ignoredLegacy2x()) {
            out.append("  An older 2.x file was also found and ignored: ").append(ignored)
                    .append(". 3.x/4.x data replaces it.\n");
        }
    }

    private static String byLocation(ImportResult result) {
        Map<LocationKind, Integer> counts = new EnumMap<>(LocationKind.class);
        result.records().forEach(record -> counts.merge(record.location().kind(), 1, Integer::sum));
        StringJoiner joined = new StringJoiner(", ");
        counts.forEach((kind, count) -> joined.add(kind + " " + count));
        return joined.length() == 0 ? "none" : joined.toString();
    }

    /** One list or counter of the mapper's report, with what it means for the operator. */
    private record Line(String title, int count, List<?> items, String meaning) {
    }

    private static Line line(String title, List<?> items, String meaning) {
        return new Line(title, items.size(), items, meaning);
    }

    /** Every list and counter of {@link ImportResult.Report} except the totals printed under "Imported". */
    private static List<Line> lines(ImportResult.Report report) {
        return List.of(
                new Line("Unfinished operations", report.unfinishedOperations(), List.of(),
                        "The old version had started these operations and never finished them. They were not "
                                + "replayed; each companion was imported in the state its old lifecycle row "
                                + "recorded. Nothing to do."),
                line("Quarantined profiles", report.quarantinedProfiles(),
                        "The old version had set these aside after an error. They were imported from their last "
                                + "recorded state like any other companion; check them in game."),
                line("Skipped rows", report.skippedRows(),
                        "Old rows that could not become part of a companion. Each line is table, key, reason. A "
                                + "skipped companion_profile row is a companion that was not imported."),
                line("Live without checkpoint", report.liveWithoutCheckpoint(),
                        "These companions were out in a world but the old data held no saved copy of their body. "
                                + "They are imported at position 0,0,0 and fill in when their body loads. If the "
                                + "body is gone, the owner can recover the companion as lost; it then comes back "
                                + "from its older saved state when one existed, otherwise new from its role at "
                                + "level 1. A recover before the body has loaded replaces it: the original "
                                + "body is removed as a leftover copy when it loads later. Owners should "
                                + "visit such animals before they use Recover."),
                line("Live world guessed", report.liveWorldGuessed(),
                        "The old data named no world for these live companions. They carry the world most other "
                                + "rows name until their body loads and corrects it. On a server with several "
                                + "worlds the companion panel may show the wrong world until then."),
                line("Live used history", report.liveUsedHistory(),
                        "These live companions had no saved copy of their body, so an older saved state of theirs "
                                + "was kept as the fallback. It is used only if the body is gone and the owner "
                                + "recovers the companion; levels gained after that state was saved are then lost."),
                line("Live used old death state", report.liveUsedOldDeathState(),
                        "These live companions had no saved copy of their body and no other saved state, so the "
                                + "state of an earlier death is kept as the fallback. They are alive and have no "
                                + "death timers. If the body is gone and the owner recovers the companion, it "
                                + "comes back with the level and traits it had at that death."),
                line("Checkpoints of dying bodies", report.checkpointsOfDyingBodies(),
                        "The only saved copy of these live companions was taken as the body died. The death was "
                                + "removed from the copy so the companion can be recovered if its body is gone."),
                line("Imported lost", report.importedLost(),
                        "The old data named no usable body or coop slot for these, or their state was "
                                + "unresolved. Their owners can recover them like any lost companion."),
                line("Npc uuid collisions", report.npcUuidCollisions(),
                        "Two companions named the same body. Of each pair the one changed last kept the body and "
                                + "the other was imported as lost. Both are listed."),
                line("Without state", report.withoutState(),
                        "The old data held no readable state for these stored, dead, lost or coop companions. "
                                + "They come back from their role at level 1."),
                line("State in item", report.stateInItem(),
                        "These captured companions have no saved state in the old database because their capture "
                                + "item holds it. Releasing the item brings them back as before. If the item was "
                                + "destroyed, the companion cannot be restored."));
    }

    private static String skipReason(String reason) {
        return switch (reason) {
            case "INVALID_ID" -> "the profile id or owner id is not a valid id";
            case "NO_LIFECYCLE" -> "the profile has no lifecycle row, so its state is unknown";
            case "NO_ROLE" -> "the profile names no role";
            case "ALSO_BONDED" -> "the bonded database holds the same profile; the bonded row was imported instead";
            case "NAMESPACE_HAS_SLASH" ->
                    "extension data whose namespace contains \"/\", which the new store does not accept";
            case "RESERVED_NAMESPACE" -> "extension data in a namespace Tamework keeps for itself; it is not needed";
            case "UNREADABLE" -> "saved state that could not be read";
            case "MAPPING_FAILED" -> "the row could not be turned into a companion; the server log has the error";
            default -> "not imported";
        };
    }
}
