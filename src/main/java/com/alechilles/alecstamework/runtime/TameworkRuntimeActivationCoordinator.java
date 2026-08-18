package com.alechilles.alecstamework.runtime;

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
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/** Builds one fail-closed startup plan from content, requests, and durable state. */
public final class TameworkRuntimeActivationCoordinator {
    private static final String GENERIC_WRITABLE = "generic-persistence-writable";
    private static final String BONDED_WRITABLE = "bonded-persistence-writable";

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
        TameworkActivationEvidence evidence = evidence(
                requests.publish(), generic, bonded
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
                requestedCapabilities, genericPersistence, bondedPersistence
        ));
        return TameworkReloadTopologyReport.compare(startup, candidate);
    }

    private static TameworkActivationEvidence evidence(
            Map<TameworkRuntimeModule, Set<String>> requests,
            TameworkPersistenceActivationEvidence generic,
            TameworkPersistenceActivationEvidence bonded
    ) {
        TameworkActivationEvidence.Builder evidence = baseEvidence(requests)
                .requiredCapability(TameworkRuntimeModule.GENERIC_PERSISTENCE, GENERIC_WRITABLE)
                .requiredCapability(TameworkRuntimeModule.BONDED_PERSISTENCE, BONDED_WRITABLE);
        addPersistenceEvidence(evidence, TameworkRuntimeModule.GENERIC_PERSISTENCE,
                GENERIC_WRITABLE, generic);
        addPersistenceEvidence(evidence, TameworkRuntimeModule.BONDED_PERSISTENCE,
                BONDED_WRITABLE, bonded);
        return evidence.build();
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
