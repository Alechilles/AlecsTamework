package com.alechilles.alecstamework.runtime;

import java.util.Objects;
import java.util.function.Consumer;

/** Installs prepared systems on the matching Hytale store registry. */
public final class TameworkRuntimeRegistrationTarget
        implements TameworkRuntimeRegistrationContext.RegistrationTarget {
    private final Consumer<Object> entitySystemRegistrar;
    private final Consumer<Object> chunkSystemRegistrar;

    /** Creates the host target from registry-bound installers. */
    public TameworkRuntimeRegistrationTarget(
            Consumer<Object> entitySystemRegistrar,
            Consumer<Object> chunkSystemRegistrar
    ) {
        this.entitySystemRegistrar = Objects.requireNonNull(
                entitySystemRegistrar, "Entity system registrar is required"
        );
        this.chunkSystemRegistrar = Objects.requireNonNull(
                chunkSystemRegistrar, "Chunk system registrar is required"
        );
    }

    @Override
    public AutoCloseable register(
            TameworkRuntimeRegistrationContext.RegistrationKind kind,
            String participantId
    ) {
        return () -> { };
    }

    @Override
    public AutoCloseable register(
            TameworkRuntimeRegistrationContext.RegistrationKind kind,
            String participantId,
            Object resource
    ) {
        if (resource == null) {
            return () -> { };
        }
        switch (kind) {
            case ECS_SYSTEM -> entitySystemRegistrar.accept(resource);
            case CHUNK_SYSTEM -> chunkSystemRegistrar.accept(resource);
            default -> {
                // Listener and worker participants perform their own host action.
            }
        }
        return () -> { };
    }
}
