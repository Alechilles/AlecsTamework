package com.alechilles.alecstamework.items;

import java.util.Comparator;

/** Deterministic ordering shared by loaded-identity probes and atomic snapshots. */
final class LoadedNpcObservationOrder {
    static final Comparator<LoadedNpcIdentityIndex.LoadedNpcObservation> COMPARATOR = Comparator
            .comparing(LoadedNpcIdentityIndex.LoadedNpcObservation::location,
                    Comparator.comparing(LoadedNpcIdentityIndex.Location::worldName)
                            .thenComparing(LoadedNpcIdentityIndex.Location::storeIdentity))
            .thenComparing(observation -> observation.componentUuid() != null
                    ? observation.componentUuid().toString() : "")
            .thenComparing(observation -> observation.legacyNpcUuid() != null
                    ? observation.legacyNpcUuid().toString() : "");

    private LoadedNpcObservationOrder() {
    }
}
