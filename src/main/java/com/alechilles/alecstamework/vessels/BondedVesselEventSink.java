package com.alechilles.alecstamework.vessels;

import com.alechilles.alecstamework.api.TameworkEvent;
import javax.annotation.Nonnull;

/** Isolates public post-commit event delivery from vessel mutation success. */
@FunctionalInterface
public interface BondedVesselEventSink {
    BondedVesselEventSink NO_OP = event -> { };

    void emit(@Nonnull TameworkEvent event);
}
