package com.alechilles.alecstamework.runtime.activation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Builds activation evidence from immutable effective-content facts.
 *
 * <p>This collector is a bootstrap-only pure Java boundary. It does not read
 * Hytale registries, schedule work, or run from a tick callback.</p>
 */
public final class TameworkAssetActivationEvidenceCollector {
    /** Collects direct content evidence from one immutable fact sequence. */
    public TameworkActivationEvidence collect(Iterable<? extends TameworkEffectiveAssetFact> facts) {
        Objects.requireNonNull(facts, "Effective asset facts are required");
        List<TameworkEffectiveAssetFact> ordered = new ArrayList<>();
        for (TameworkEffectiveAssetFact fact : facts) {
            ordered.add(Objects.requireNonNull(fact, "Effective asset facts cannot contain null"));
        }
        ordered.sort(Comparator.comparing((TameworkEffectiveAssetFact fact) -> fact.module().id())
                .thenComparing(TameworkEffectiveAssetFact::source));

        TameworkActivationEvidence.Builder evidence = TameworkActivationEvidence.builder();
        for (TameworkEffectiveAssetFact fact : ordered) {
            if (fact.hasEffectiveContent()) {
                evidence.content(fact.module(), fact.source());
            }
        }
        return evidence.build();
    }
}
