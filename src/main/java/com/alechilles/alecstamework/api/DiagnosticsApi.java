package com.alechilles.alecstamework.api;

import javax.annotation.Nonnull;

public interface DiagnosticsApi {
    @Nonnull
    PersistenceDiagnosticsView getPersistenceDiagnostics();
}
