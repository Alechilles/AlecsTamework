package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationCoverageRecord;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Explicit custom-container catalog. Coverage remains non-ready until the catalog is sealed.
 */
public final class CustomContainerReconciliationRegistry {
    private final Map<String, CustomContainerPopulationEvidenceProvider> providers = new LinkedHashMap<>();
    private boolean sealed;
    private long revision;
    @Nullable
    private String declaration;

    public void register(@Nonnull CustomContainerPopulationEvidenceProvider provider) {
        Objects.requireNonNull(provider, "provider");
        String providerId = CustomContainerPopulationEvidenceProvider.normalizeProviderId(provider.providerId());
        synchronized (this) {
            if (sealed) {
                throw new IllegalStateException("Custom container reconciliation catalog is already sealed.");
            }
            if (providers.putIfAbsent(providerId, provider) != null) {
                throw new IllegalArgumentException("Duplicate custom container provider: " + providerId);
            }
            revision++;
        }
    }

    /**
     * Explicitly declares that every custom persisted-container family is represented.
     */
    public synchronized void seal(@Nonnull String coverageDeclaration) {
        String normalized = Objects.requireNonNull(coverageDeclaration, "coverageDeclaration").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("coverageDeclaration must not be blank.");
        }
        sealed = true;
        declaration = normalized;
        revision++;
    }

    @Nonnull
    public Snapshot snapshot() throws Exception {
        Catalog catalog;
        synchronized (this) {
            catalog = new Catalog(
                    providerSnapshot(), sealed, declaration, revision
            );
        }
        List<CompanionPopulationEvidenceSource> sources = new ArrayList<>(catalog.providers().size());
        List<String> generationParts = new ArrayList<>(catalog.providers().size() + 1);
        for (Map.Entry<String, CustomContainerPopulationEvidenceProvider> entry : catalog.providers()) {
            CompanionPopulationEvidenceSource source = Objects.requireNonNull(
                    entry.getValue().createEvidenceSource(),
                    "custom evidence source"
            );
            CompanionPopulationEvidenceSource.Descriptor descriptor = source.descriptor();
            if (descriptor.dimension() != CompanionPopulationCoverageRecord.Dimension.CUSTOM_CONTAINERS) {
                throw new IllegalArgumentException(
                        "Custom provider returned the wrong coverage dimension: " + entry.getKey()
                );
            }
            sources.add(source);
            generationParts.add(entry.getKey() + "=" + descriptor.scanGeneration());
        }
        synchronized (this) {
            if (revision != catalog.revision()) {
                throw new IllegalStateException(
                        "Custom container reconciliation catalog changed while sources were created."
                );
            }
        }
        generationParts.add("sealed=" + catalog.sealed());
        generationParts.add("declaration=" + (catalog.declaration() == null ? "" : catalog.declaration()));
        return new Snapshot(
                List.copyOf(sources),
                catalog.sealed(),
                catalog.declaration(),
                ReconciliationGeneration.forStrings("custom-container-catalog", generationParts)
        );
    }

    @Nonnull
    private List<Map.Entry<String, CustomContainerPopulationEvidenceProvider>> providerSnapshot() {
        List<Map.Entry<String, CustomContainerPopulationEvidenceProvider>> snapshot =
                new ArrayList<>(providers.size());
        for (Map.Entry<String, CustomContainerPopulationEvidenceProvider> entry : providers.entrySet()) {
            snapshot.add(Map.entry(entry.getKey(), entry.getValue()));
        }
        return List.copyOf(snapshot);
    }

    private record Catalog(
            @Nonnull List<Map.Entry<String, CustomContainerPopulationEvidenceProvider>> providers,
            boolean sealed,
            @Nullable String declaration,
            long revision
    ) {
    }

    public record Snapshot(@Nonnull List<CompanionPopulationEvidenceSource> sources,
                           boolean sealed,
                           @Nullable String declaration,
                           @Nonnull String generation) {
        public Snapshot {
            sources = List.copyOf(sources);
        }
    }
}
