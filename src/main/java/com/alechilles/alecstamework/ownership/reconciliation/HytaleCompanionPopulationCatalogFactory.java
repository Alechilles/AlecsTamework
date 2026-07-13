package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationLegacyEvidenceRepository;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.playerdata.PlayerStorage;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Builds a fail-safe snapshot catalog from every current 0.5.6 Universe persistence family. */
public final class HytaleCompanionPopulationCatalogFactory {
    private HytaleCompanionPopulationCatalogFactory() {
    }

    @Nonnull
    public static BuildResult create(
            @Nonnull Universe universe,
            @Nonnull ComponentType<EntityStore, TameworkOwnerComponent> ownerType,
            @Nonnull ItemFeatureRegistry itemFeatures,
            @Nonnull CompanionPopulationLegacyEvidenceRepository legacyEvidenceRepository,
            @Nonnull CustomContainerReconciliationRegistry customContainers,
            @Nonnull String scanSessionEpoch
    ) {
        return create(
                universe, ownerType, itemFeatures, legacyEvidenceRepository,
                customContainers, scanSessionEpoch, scanSessionEpoch);
    }

    @Nonnull
    public static BuildResult create(
            @Nonnull Universe universe,
            @Nonnull ComponentType<EntityStore, TameworkOwnerComponent> ownerType,
            @Nonnull ItemFeatureRegistry itemFeatures,
            @Nonnull CompanionPopulationLegacyEvidenceRepository legacyEvidenceRepository,
            @Nonnull CustomContainerReconciliationRegistry customContainers,
            @Nonnull String scanSessionEpoch,
            @Nonnull String playerEvidenceEpoch
    ) {
        return create(
                universe,
                ownerType,
                itemFeatures,
                legacyEvidenceRepository,
                customContainers,
                scanSessionEpoch,
                playerEvidenceEpoch,
                PersistentWorldDirectoryCatalog.filesystem()
        );
    }

    @Nonnull
    static BuildResult create(
            @Nonnull Universe universe,
            @Nonnull ComponentType<EntityStore, TameworkOwnerComponent> ownerType,
            @Nonnull ItemFeatureRegistry itemFeatures,
            @Nonnull CompanionPopulationLegacyEvidenceRepository legacyEvidenceRepository,
            @Nonnull CustomContainerReconciliationRegistry customContainers,
            @Nonnull String scanSessionEpoch,
            @Nonnull String playerEvidenceEpoch,
            @Nonnull PersistentWorldDirectoryCatalog persistentWorldDirectories
    ) {
        Objects.requireNonNull(universe, "universe");
        Objects.requireNonNull(scanSessionEpoch, "scanSessionEpoch");
        Objects.requireNonNull(playerEvidenceEpoch, "playerEvidenceEpoch");
        Objects.requireNonNull(persistentWorldDirectories, "persistentWorldDirectories");
        LegacyCapturedItemEvidenceReader itemReader = new LegacyCapturedItemEvidenceReader(itemFeatures);
        RecursiveItemContainerEvidenceScanner itemContainers =
                new RecursiveItemContainerEvidenceScanner(itemReader);
        HytalePlayerInventoryEvidenceScanner inventories =
                new HytalePlayerInventoryEvidenceScanner(itemContainers);
        List<CompanionPopulationEvidenceSource> sources = new ArrayList<>();
        List<String> incompleteReasons = new ArrayList<>();

        boolean profileSealed = addProfileSource(sources, legacyEvidenceRepository, incompleteReasons);
        KnownIdentities knownIdentities = knownIdentities(
                legacyEvidenceRepository, incompleteReasons
        );
        WorldResult worlds = addWorldSources(
                universe,
                ownerType,
                itemContainers,
                knownIdentities.npcUuids(),
                scanSessionEpoch,
                sources,
                incompleteReasons,
                persistentWorldDirectories
        );
        worlds = new WorldResult(worlds.sealed() && knownIdentities.complete());
        boolean playersSealed = addPlayerSources(
                universe, inventories, playerEvidenceEpoch, sources, incompleteReasons
        );
        CustomContainerReconciliationRegistry.Snapshot customSnapshot = customSnapshot(
                customContainers, incompleteReasons
        );
        CompanionPopulationReconciliationCatalog catalog = new CompanionPopulationReconciliationCatalog(
                sources,
                profileSealed,
                worlds.sealed(),
                playersSealed,
                worlds.sealed(),
                customSnapshot
        );
        String reason = incompleteReasons.isEmpty()
                ? "reconciliation-catalog-sealed"
                : String.join(",", incompleteReasons);
        return new BuildResult(catalog, reason, incompleteReasons.isEmpty());
    }

    private static boolean addProfileSource(
            @Nonnull List<CompanionPopulationEvidenceSource> sources,
            @Nonnull CompanionPopulationLegacyEvidenceRepository repository,
            @Nonnull List<String> reasons
    ) {
        try {
            sources.add(new SqliteProfileStateEvidenceSource(repository));
            return true;
        } catch (Exception exception) {
            reasons.add("profile-state-catalog-unavailable:" + exception.getClass().getSimpleName());
            return false;
        }
    }

    @Nonnull
    private static KnownIdentities knownIdentities(
            @Nonnull CompanionPopulationLegacyEvidenceRepository repository,
            @Nonnull List<String> reasons
    ) {
        try {
            return new KnownIdentities(repository.loadKnownNpcUuids(), true);
        } catch (Exception exception) {
            reasons.add("known-companion-catalog-unavailable:" + exception.getClass().getSimpleName());
            return new KnownIdentities(Set.of(), false);
        }
    }

    @Nonnull
    private static WorldResult addWorldSources(
            @Nonnull Universe universe,
            @Nonnull ComponentType<EntityStore, TameworkOwnerComponent> ownerType,
            @Nonnull RecursiveItemContainerEvidenceScanner itemContainers,
            @Nonnull Set<UUID> knownNpcUuids,
            @Nonnull String mutableSourceEpoch,
            @Nonnull List<CompanionPopulationEvidenceSource> sources,
            @Nonnull List<String> reasons,
            @Nonnull PersistentWorldDirectoryCatalog persistentWorldDirectories
    ) {
        Optional<PersistentWorldDirectoryCatalog.Snapshot> savedBefore = savedWorldSnapshot(
                universe, persistentWorldDirectories, reasons, "before"
        );
        Map<String, World> before = new TreeMap<>(universe.getWorlds());
        boolean complete = true;
        for (Map.Entry<String, World> entry : before.entrySet()) {
            World world = entry.getValue();
            if (world == null || !world.isAlive()) {
                complete = false;
                reasons.add("world-catalog-not-alive:" + entry.getKey());
                continue;
            }
            try {
                sources.add(new HytaleSavedWorldEvidenceSource(
                        world,
                        HytaleSavedWorldEvidenceSource.Mode.WORLD_ENTITIES,
                        ownerType,
                        itemContainers,
                        knownNpcUuids,
                        mutableSourceEpoch
                ));
                sources.add(new HytaleSavedWorldEvidenceSource(
                        world,
                        HytaleSavedWorldEvidenceSource.Mode.BASE_CONTAINER_BLOCKS,
                        ownerType,
                        itemContainers,
                        knownNpcUuids,
                        mutableSourceEpoch
                ));
            } catch (Exception exception) {
                complete = false;
                reasons.add("world-catalog-unavailable:" + entry.getKey()
                        + ":" + exception.getClass().getSimpleName());
            }
        }
        Set<String> afterKeys = new TreeSet<>(universe.getWorlds().keySet());
        if (!afterKeys.equals(before.keySet())) {
            complete = false;
            reasons.add("world-catalog-changed-during-snapshot");
        }
        Optional<PersistentWorldDirectoryCatalog.Snapshot> savedAfter = savedWorldSnapshot(
                universe, persistentWorldDirectories, reasons, "after"
        );
        if (savedBefore.isEmpty() || savedAfter.isEmpty()) {
            complete = false;
        } else if (!savedBefore.equals(savedAfter)) {
            complete = false;
            reasons.add("persisted-world-catalog-changed-during-snapshot");
        } else {
            PersistentWorldDirectoryCatalog.Coverage coverage = savedBefore.orElseThrow().compareToLiveWorlds(
                    liveWorldSavePaths(before)
            );
            if (!coverage.complete()) {
                complete = false;
                for (Path missing : coverage.missingWorldDirectories()) {
                    reasons.add("persisted-world-not-live:" + missing.getFileName());
                }
            }
        }
        return new WorldResult(complete);
    }

    private static Optional<PersistentWorldDirectoryCatalog.Snapshot> savedWorldSnapshot(
            @Nonnull Universe universe,
            @Nonnull PersistentWorldDirectoryCatalog persistentWorldDirectories,
            @Nonnull List<String> reasons,
            @Nonnull String phase
    ) {
        try {
            return Optional.of(persistentWorldDirectories.snapshot(universe.getWorldsPath()));
        } catch (Exception exception) {
            reasons.add("persisted-world-catalog-unavailable:" + phase
                    + ":" + exception.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    @Nonnull
    private static List<Path> liveWorldSavePaths(@Nonnull Map<String, World> worlds) {
        List<Path> paths = new ArrayList<>();
        for (World world : worlds.values()) {
            if (world != null) {
                paths.add(world.getSavePath());
            }
        }
        return List.copyOf(paths);
    }

    private static boolean addPlayerSources(
            @Nonnull Universe universe,
            @Nonnull HytalePlayerInventoryEvidenceScanner inventories,
            @Nonnull String mutableSourceEpoch,
            @Nonnull List<CompanionPopulationEvidenceSource> sources,
            @Nonnull List<String> reasons
    ) {
        PlayerStorage storage = universe.getPlayerStorage();
        if (storage == null) {
            reasons.add("player-storage-unavailable");
            return false;
        }
        try {
            Set<UUID> storedBefore = new TreeSet<>(Comparator.comparing(UUID::toString));
            storedBefore.addAll(storage.getPlayers());
            List<PlayerTarget> onlineBefore = onlineTargets(universe);
            sources.add(new HytaleStoredPlayerInventoryEvidenceSource(
                    storage, inventories, mutableSourceEpoch
            ));
            sources.add(new HytaleOnlinePlayerInventoryEvidenceSource(
                    universe, inventories, mutableSourceEpoch
            ));
            Set<UUID> storedAfter = new TreeSet<>(Comparator.comparing(UUID::toString));
            storedAfter.addAll(storage.getPlayers());
            boolean stable = storedBefore.equals(storedAfter)
                    && onlineBefore.equals(onlineTargets(universe));
            if (!stable) {
                reasons.add("player-catalog-changed-during-snapshot");
            }
            return stable;
        } catch (Exception exception) {
            reasons.add("player-catalog-unavailable:" + exception.getClass().getSimpleName());
            return false;
        }
    }

    @Nonnull
    private static List<PlayerTarget> onlineTargets(@Nonnull Universe universe) {
        List<PlayerTarget> result = new ArrayList<>();
        for (PlayerRef player : universe.getPlayers()) {
            result.add(new PlayerTarget(player.getUuid(), player.getWorldUuid()));
        }
        result.sort(Comparator.comparing(target -> target.playerUuid().toString()));
        return List.copyOf(result);
    }

    @Nonnull
    private static CustomContainerReconciliationRegistry.Snapshot customSnapshot(
            @Nonnull CustomContainerReconciliationRegistry registry,
            @Nonnull List<String> reasons
    ) {
        try {
            CustomContainerReconciliationRegistry.Snapshot snapshot = registry.snapshot();
            if (!snapshot.sealed()) {
                reasons.add("custom-container-catalog-not-sealed");
            }
            return snapshot;
        } catch (Exception exception) {
            reasons.add("custom-container-catalog-unavailable:" + exception.getClass().getSimpleName());
            try {
                return new CustomContainerReconciliationRegistry().snapshot();
            } catch (Exception impossible) {
                throw new IllegalStateException(impossible);
            }
        }
    }

    public record BuildResult(@Nonnull CompanionPopulationReconciliationCatalog catalog,
                              @Nonnull String reason,
                              boolean completelySealed) {
    }

    private record WorldResult(boolean sealed) {
    }

    private record KnownIdentities(@Nonnull Set<UUID> npcUuids, boolean complete) {
        private KnownIdentities {
            npcUuids = Set.copyOf(npcUuids);
        }
    }

    private record PlayerTarget(@Nonnull UUID playerUuid, @Nonnull UUID worldUuid) {
    }
}
