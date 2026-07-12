package com.alechilles.alecstamework.ownership.reconciliation;

import com.hypixel.hytale.server.core.universe.playerdata.PlayerStorage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;

/** Resumable scan of every UUID exposed by Hytale 0.5.6 {@link PlayerStorage}. */
public final class HytaleStoredPlayerInventoryEvidenceSource implements CompanionPopulationEvidenceSource {
    private static final String COVERAGE_KEY = "player-saves:stored";
    private final StoredPlayerCatalog catalog;
    private final StoredPlayerReader reader;
    private final List<UUID> players;
    private final Descriptor descriptor;

    public HytaleStoredPlayerInventoryEvidenceSource(
            @Nonnull PlayerStorage storage,
            @Nonnull HytalePlayerInventoryEvidenceScanner inventories
    ) throws IOException {
        this(storage, inventories, "direct-source");
    }

    public HytaleStoredPlayerInventoryEvidenceSource(
            @Nonnull PlayerStorage storage,
            @Nonnull HytalePlayerInventoryEvidenceScanner inventories,
            @Nonnull String mutableSourceEpoch
    ) throws IOException {
        this(
                Objects.requireNonNull(storage, "storage")::getPlayers,
                runtimeReader(storage, inventories),
                mutableSourceEpoch
        );
    }

    HytaleStoredPlayerInventoryEvidenceSource(
            @Nonnull StoredPlayerCatalog catalog,
            @Nonnull StoredPlayerReader reader,
            @Nonnull String mutableSourceEpoch
    ) throws IOException {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.reader = Objects.requireNonNull(reader, "reader");
        List<UUID> ordered = new ArrayList<>(catalog.getPlayers());
        ordered.sort(Comparator.comparing(UUID::toString));
        this.players = List.copyOf(ordered);
        this.descriptor = new Descriptor(
                COVERAGE_KEY,
                com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationCoverageRecord.Dimension.PLAYER_SAVES,
                "universe",
                ReconciliationGeneration.forStrings(
                        COVERAGE_KEY,
                        List.of(
                                ReconciliationGeneration.forUuids(COVERAGE_KEY, players),
                                requireText(mutableSourceEpoch, "mutableSourceEpoch")
                        )
                ),
                players.size()
        );
    }

    @Nonnull
    @Override
    public Descriptor descriptor() {
        return descriptor;
    }

    @Nonnull
    @Override
    public CompletableFuture<Batch> scan(long offset, int maxUnits) {
        int start = checkedStart(offset);
        int end = Math.min(players.size(), start + requirePositive(maxUnits));
        CompletableFuture<List<CompanionPopulationEvidence>> future =
                CompletableFuture.completedFuture(new ArrayList<>());
        for (int index = start; index < end; index++) {
            UUID playerUuid = players.get(index);
            future = future.thenCompose(evidence -> reader.read(playerUuid).thenApply(found -> {
                evidence.addAll(Objects.requireNonNull(found, "stored player evidence"));
                return evidence;
            }));
        }
        return future.thenApply(evidence -> {
            boolean complete = end == players.size();
            if (complete && !samePlayers()) {
                throw new IllegalStateException("Stored-player catalog changed during reconciliation.");
            }
            return new Batch(evidence, end, end - start, complete);
        });
    }

    private int checkedStart(long offset) {
        if (offset < 0L || offset > players.size()) {
            throw new IllegalArgumentException("Stored-player cursor is outside the source snapshot.");
        }
        return Math.toIntExact(offset);
    }

    private static int requirePositive(int value) {
        if (value <= 0) {
            throw new IllegalArgumentException("maxUnits must be positive.");
        }
        return value;
    }

    private boolean samePlayers() {
        try {
            List<UUID> current = new ArrayList<>(catalog.getPlayers());
            current.sort(Comparator.comparing(UUID::toString));
            return players.equals(current);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to verify stored-player catalog.", exception);
        }
    }

    @Nonnull
    private static StoredPlayerReader runtimeReader(
            @Nonnull PlayerStorage storage,
            @Nonnull HytalePlayerInventoryEvidenceScanner inventories
    ) {
        PlayerStorage requiredStorage = Objects.requireNonNull(storage, "storage");
        HytalePlayerInventoryEvidenceScanner requiredInventories =
                Objects.requireNonNull(inventories, "inventories");
        return playerUuid -> requiredStorage.load(playerUuid).thenApply(holder -> {
            if (holder == null) {
                throw new IllegalStateException("Stored player holder was null: " + playerUuid);
            }
            return requiredInventories.scan(
                    holder,
                    "player-save/" + playerUuid,
                    COVERAGE_KEY
            );
        });
    }

    @Nonnull
    private static String requireText(@Nonnull String value, @Nonnull String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank.");
        }
        return normalized;
    }

    @FunctionalInterface
    interface StoredPlayerCatalog {
        Set<UUID> getPlayers() throws IOException;
    }

    @FunctionalInterface
    interface StoredPlayerReader {
        CompletableFuture<List<CompanionPopulationEvidence>> read(UUID playerUuid);
    }
}
