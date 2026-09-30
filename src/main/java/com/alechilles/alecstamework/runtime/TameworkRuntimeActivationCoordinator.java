package com.alechilles.alecstamework.runtime;

import com.alechilles.alecstamework.companion.store.CompanionStorage;
import com.alechilles.alecstamework.persistence.TameworkDataPathLayout;
import com.alechilles.alecstamework.persistence.TameworkDataPathService;
import com.alechilles.alecstamework.persistence.activation.TameworkPersistenceActivationEvidence;
import com.alechilles.alecstamework.persistence.activation.TameworkPersistenceActivationProbe;
import com.alechilles.alecstamework.persistence.bonded.BondedCompanionDataPath;
import com.alechilles.alecstamework.persistence.bonded.BondedCompanionPersistenceActivationProbe;
import com.alechilles.alecstamework.persistence.kernel.PersistenceFiles;
import com.alechilles.alecstamework.runtime.activation.TameworkActivationEvidence;
import com.alechilles.alecstamework.runtime.activation.TameworkAssetActivationEvidenceCollector;
import com.alechilles.alecstamework.runtime.activation.TameworkReloadTopologyReport;
import com.alechilles.alecstamework.runtime.activation.TameworkRuntimeActivationPlan;
import com.alechilles.alecstamework.runtime.activation.TameworkRuntimeActivationPlanner;
import com.alechilles.alecstamework.runtime.activation.TameworkRuntimeCapabilityRequests;
import com.alechilles.alecstamework.runtime.activation.TameworkRuntimeModule;
import com.alechilles.alecstamework.runtime.activation.TameworkRuntimeModuleCatalog;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.Universe;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/** Builds one fail-closed startup plan from content, requests, and durable state. */
public final class TameworkRuntimeActivationCoordinator {
    private static final String GENERIC_WRITABLE = "generic-persistence-writable";
    private static final String BONDED_WRITABLE = "bonded-persistence-writable";
    private static final String COMPANION_STORE = "companion-store";

    private final TameworkRuntimeActivationPlanner planner =
            new TameworkRuntimeActivationPlanner(TameworkRuntimeModuleCatalog.standard());

    /** Immutable startup inputs that precede construction of active services. */
    public record Preparation(
            TameworkRuntimeActivationPlan plan,
            TameworkDataPathLayout dataPathLayout,
            TameworkPersistenceActivationEvidence genericPersistence,
            TameworkPersistenceActivationEvidence bondedPersistence
    ) {
    }

    /** Probes storage read-only and builds one immutable startup plan. */
    public Preparation prepare(
            Path pluginDataDirectory,
            HytaleLogger logger,
            TameworkRuntimeCapabilityRequests requests
    ) {
        TameworkDataPathLayout layout = new TameworkDataPathService(logger)
                .resolveDataPathLayout(pluginDataDirectory);
        TameworkPersistenceActivationEvidence generic = new TameworkPersistenceActivationProbe(
                PersistenceFiles.replacementDatabase(layout.targetDirectory()),
                layout.persistenceSourceDirectories()
        ).probe();
        TameworkPersistenceActivationEvidence bonded =
                new BondedCompanionPersistenceActivationProbe(
                        BondedCompanionDataPath.resolve(layout)
                ).probe();
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
            TameworkPersistenceActivationEvidence genericPersistence,
            TameworkPersistenceActivationEvidence bondedPersistence
    ) {
        TameworkRuntimeActivationPlan candidate = planner.plan(evidence(
                requestedCapabilities, genericPersistence, bondedPersistence, companionStoreExists()
        ));
        return TameworkReloadTopologyReport.compare(startup, candidate);
    }

    private static TameworkActivationEvidence evidence(
            Map<TameworkRuntimeModule, Set<String>> requests,
            TameworkPersistenceActivationEvidence generic,
            TameworkPersistenceActivationEvidence bonded,
            boolean companionStore
    ) {
        TameworkActivationEvidence.Builder evidence = baseEvidence(requests)
                .requiredCapability(TameworkRuntimeModule.GENERIC_PERSISTENCE, GENERIC_WRITABLE)
                .requiredCapability(TameworkRuntimeModule.BONDED_PERSISTENCE, BONDED_WRITABLE);
        addPersistenceEvidence(evidence, TameworkRuntimeModule.GENERIC_PERSISTENCE,
                GENERIC_WRITABLE, generic);
        addPersistenceEvidence(evidence, TameworkRuntimeModule.BONDED_PERSISTENCE,
                BONDED_WRITABLE, bonded);
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
            TameworkPersistenceActivationEvidence persistence
    ) {
        if (!persistence.readOnly()) {
            evidence.availableCapability(writableCapability);
        }
        if (persistence.hasDurableWork()) {
            evidence.durableState(module, persistence.diagnosticCode());
        }
    }
}
