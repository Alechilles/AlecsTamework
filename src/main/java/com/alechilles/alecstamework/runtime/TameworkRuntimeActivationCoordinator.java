package com.alechilles.alecstamework.runtime;

import com.alechilles.alecstamework.companion.store.CompanionStorage;
import com.alechilles.alecstamework.runtime.activation.TameworkActivationEvidence;
import com.alechilles.alecstamework.runtime.activation.TameworkAssetActivationEvidenceCollector;
import com.alechilles.alecstamework.runtime.activation.TameworkReloadTopologyReport;
import com.alechilles.alecstamework.runtime.activation.TameworkRuntimeActivationPlan;
import com.alechilles.alecstamework.runtime.activation.TameworkRuntimeActivationPlanner;
import com.alechilles.alecstamework.runtime.activation.TameworkRuntimeCapabilityRequests;
import com.alechilles.alecstamework.runtime.activation.TameworkRuntimeModule;
import com.alechilles.alecstamework.runtime.activation.TameworkRuntimeModuleCatalog;
import com.alechilles.alecstamework.settings.TameworkDataPathLayout;
import com.alechilles.alecstamework.settings.TameworkDataPathService;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.Universe;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Builds one fail-closed startup plan from content, requests, and durable state. */
public final class TameworkRuntimeActivationCoordinator {
    private static final String GENERIC_WRITABLE = "generic-persistence-writable";
    private static final String BONDED_WRITABLE = "bonded-persistence-writable";
    private static final String COMPANION_STORE = "companion-store";
    private static final String BONDED_FILE = "bonded-companions.sqlite";
    /** The activation reason reported when old save files exist. */
    private static final String LEGACY_DATA_PRESENT = "durable-state-present";

    private final TameworkRuntimeActivationPlanner planner =
            new TameworkRuntimeActivationPlanner(TameworkRuntimeModuleCatalog.standard());

    /**
     * Immutable startup inputs that precede construction of active services.
     *
     * @param genericLegacyData whether any old Tamework save file exists
     * @param bondedLegacyData  whether the old bonded database exists
     */
    public record Preparation(
            TameworkRuntimeActivationPlan plan,
            TameworkDataPathLayout dataPathLayout,
            boolean genericLegacyData,
            boolean bondedLegacyData
    ) {
    }

    /**
     * Looks for saved data by file name only and builds one immutable startup plan. No old
     * database is opened here: only the companion importer loads the SQLite driver (plan 7 R24).
     */
    public Preparation prepare(
            Path pluginDataDirectory,
            HytaleLogger logger,
            TameworkRuntimeCapabilityRequests requests
    ) {
        TameworkDataPathLayout layout = new TameworkDataPathService(logger)
                .resolveDataPathLayout(pluginDataDirectory);
        List<Path> legacyDirs = layout.persistenceSourceDirectories();
        boolean generic = legacyDataPresent(legacyDirs, Files::exists);
        boolean bonded = anyExists(legacyDirs, BONDED_FILE, Files::exists);
        boolean companionStore = companionStoreExists();
        if (Universe.get() == null) {
            logger.at(java.util.logging.Level.FINE).log(
                    "Universe unavailable during activation planning; companion store evidence skipped.");
        }
        TameworkActivationEvidence evidence = evidence(
                requests.publish(), generic, bonded, companionStore
        );
        return new Preparation(planner.plan(evidence), layout, generic, bonded);
    }

    /** Compares current content with the frozen startup topology. */
    public TameworkReloadTopologyReport compare(
            TameworkRuntimeActivationPlan startup,
            Map<TameworkRuntimeModule, Set<String>> requestedCapabilities,
            boolean genericLegacyData,
            boolean bondedLegacyData
    ) {
        TameworkRuntimeActivationPlan candidate = planner.plan(evidence(
                requestedCapabilities, genericLegacyData, bondedLegacyData, companionStoreExists()
        ));
        return TameworkReloadTopologyReport.compare(startup, candidate);
    }

    /**
     * Whether any old Tamework save file exists: 3.x/4.x databases, a 2.x database, or 2.x
     * {@code .dat} bundles. Persistence must start for such a world even when no asset asks for
     * it, or neither the importer nor {@code /tw persistence start-fresh} could ever run.
     */
    static boolean legacyDataPresent(Collection<Path> legacyDirs, Predicate<Path> exists) {
        for (CompanionStorage.LegacyKind kind : CompanionStorage.LegacyKind.values()) {
            for (String name : kind.files()) {
                if (anyExists(legacyDirs, name, exists)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean anyExists(Collection<Path> dirs, String name, Predicate<Path> exists) {
        for (Path dir : dirs) {
            if (dir != null && exists.test(dir.resolve(name))) {
                return true;
            }
        }
        return false;
    }

    private static TameworkActivationEvidence evidence(
            Map<TameworkRuntimeModule, Set<String>> requests,
            boolean genericLegacyData,
            boolean bondedLegacyData,
            boolean companionStore
    ) {
        TameworkActivationEvidence.Builder evidence = baseEvidence(requests)
                .requiredCapability(TameworkRuntimeModule.GENERIC_PERSISTENCE, GENERIC_WRITABLE)
                .requiredCapability(TameworkRuntimeModule.BONDED_PERSISTENCE, BONDED_WRITABLE);
        addPersistenceEvidence(evidence, TameworkRuntimeModule.GENERIC_PERSISTENCE,
                GENERIC_WRITABLE, genericLegacyData);
        addPersistenceEvidence(evidence, TameworkRuntimeModule.BONDED_PERSISTENCE,
                BONDED_WRITABLE, bondedLegacyData);
        if (companionStore) {
            evidence.durableState(TameworkRuntimeModule.GENERIC_PERSISTENCE, COMPANION_STORE);
        }
        return evidence.build();
    }

    /**
     * True when the companion store exists ({@code meta.json} is written when it is created), so
     * the companion systems stay active whenever companions may exist. False when the universe is
     * not available (not a running server).
     */
    private static boolean companionStoreExists() {
        Universe universe = Universe.get();
        return universe != null
                && Files.exists(CompanionStorage.metaFile(CompanionStorage.root(universe.getPath())));
    }

    private static TameworkActivationEvidence.Builder baseEvidence(
            Map<TameworkRuntimeModule, Set<String>> requests
    ) {
        TameworkActivationEvidence.Builder evidence = new TameworkAssetActivationEvidenceCollector()
                .collectBuilder(TameworkRuntimeActivationEvidenceSource.collect());
        for (Map.Entry<TameworkRuntimeModule, Set<String>> entry : requests.entrySet()) {
            for (String capability : entry.getValue()) {
                evidence.requestedCapability(entry.getKey(), capability, "public-request");
            }
        }
        return evidence;
    }

    private static void addPersistenceEvidence(
            TameworkActivationEvidence.Builder evidence,
            TameworkRuntimeModule module,
            String writableCapability,
            boolean legacyDataPresent
    ) {
        evidence.availableCapability(writableCapability);
        if (legacyDataPresent) {
            evidence.durableState(module, LEGACY_DATA_PRESENT);
        }
    }
}
