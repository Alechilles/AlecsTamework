package com.alechilles.alecstamework.companion.migrate;

import com.alechilles.alecstamework.companion.store.CompanionFileIo;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.bson.BsonValue;

/**
 * Every NPC UUID a 3.x/4.x world knew for a companion, mapped to its profile id (plan 7 R10):
 * every alias in any state, bonded live bodies, bonded cleanup targets, bonded capture sources
 * and the {@code npcUuid} of every snapshot. Bodies saved in the world are matched to their
 * imported record through it as their chunks load.
 *
 * <p>Immutable. The file {@link #FILE_NAME} is written once by the importer inside the store
 * folder, loaded once at start and never changed afterwards.</p>
 *
 * @param entries every entry by NPC UUID, in insertion order; unmodifiable
 */
public record LegacyAliases(@Nonnull Map<UUID, Entry> entries) {
    public static final String FILE_NAME = "legacy-aliases.json";
    public static final LegacyAliases EMPTY = new LegacyAliases(Map.of());
    private static final int FORMAT = 1;

    /** What a body with a known NPC UUID is to its profile. */
    public enum Kind {
        /** The body of a record imported LIVE: stamp it and register it. */
        CURRENT,
        /**
         * The last body of a record imported LOST because the old rows could not place it (cause
         * {@code IMPORTED_UNRESOLVED} or {@code IMPORTED_NO_BODY}). If this body shows up it is
         * the companion, and its record goes back to LIVE.
         */
        REJOIN,
        /** A leftover body: an old alias, a cleanup target, a capture source, a snapshot's source. */
        STALE
    }

    /** One known NPC UUID: the profile it belonged to, and what the body is to that profile. */
    public record Entry(@Nonnull UUID profileId, @Nonnull Kind kind) {
        public Entry {
            Objects.requireNonNull(profileId, "profileId");
            Objects.requireNonNull(kind, "kind");
        }
    }

    public LegacyAliases {
        entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }

    @Nonnull
    public Optional<Entry> byNpcUuid(@Nonnull UUID npcUuid) {
        return Optional.ofNullable(entries.get(npcUuid));
    }

    public int size() {
        return entries.size();
    }

    @Nonnull
    public BsonDocument toBson() {
        BsonDocument aliases = new BsonDocument();
        entries.forEach((npcUuid, entry) -> aliases.put(npcUuid.toString(),
                new BsonDocument("ProfileId", new BsonString(entry.profileId().toString()))
                        .append("Kind", new BsonString(entry.kind().name()))));
        return new BsonDocument("Format", new BsonInt32(FORMAT)).append("Aliases", aliases);
    }

    /** @throws IllegalArgumentException when the document is not an alias file this build can read */
    @Nonnull
    public static LegacyAliases fromBson(@Nonnull BsonDocument document) {
        BsonValue format = document.get("Format");
        if (format == null || !format.isNumber() || format.asNumber().intValue() != FORMAT
                || !document.isDocument("Aliases")) {
            throw new IllegalArgumentException("not a legacy alias file of format " + FORMAT);
        }
        Map<UUID, Entry> entries = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, BsonValue> alias : document.getDocument("Aliases").entrySet()) {
                BsonDocument entry = alias.getValue().asDocument();
                entries.put(UUID.fromString(alias.getKey()), new Entry(
                        UUID.fromString(entry.getString("ProfileId").getValue()),
                        Kind.valueOf(entry.getString("Kind").getValue())));
            }
        } catch (RuntimeException malformed) {
            throw new IllegalArgumentException("malformed legacy alias file", malformed);
        }
        return new LegacyAliases(entries);
    }

    /** Writes {@link #FILE_NAME} into {@code storeRoot} with the companion store's file access. */
    @Nonnull
    public CompletableFuture<Void> save(@Nonnull CompanionFileIo io, @Nonnull Path storeRoot) {
        return io.write(storeRoot.resolve(FILE_NAME), toBson());
    }

    /**
     * Reads {@link #FILE_NAME} from {@code storeRoot}; {@link #EMPTY} when there is none (a world
     * that was never imported). Blocking; call it at start, off the world threads.
     *
     * @throws IOException when the file exists but cannot be read or is not an alias file
     */
    @Nonnull
    public static LegacyAliases load(@Nonnull CompanionFileIo io, @Nonnull Path storeRoot) throws IOException {
        Path file = storeRoot.resolve(FILE_NAME);
        BsonDocument document = io.readNow(file);
        if (document == null) {
            return EMPTY;
        }
        try {
            return fromBson(document);
        } catch (IllegalArgumentException malformed) {
            throw new IOException("unreadable legacy alias file " + file, malformed);
        }
    }
}
