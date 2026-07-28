package com.alechilles.alecstamework.companion.coop.runtime;

import com.alechilles.alecstamework.companion.coop.CompanionCoopCaptureRequest;
import com.alechilles.alecstamework.companion.coop.CoopCapturedItemSourceEvidence;
import com.alechilles.alecstamework.items.persistence.HytaleCapturedArtifactAdapter;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.runtime.player.HytalePlayerDurabilityBarrier;
import com.alechilles.alecstamework.persistence.runtime.player.TameworkInventoryOperationReceiptsComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import javax.annotation.Nonnull;

/**
 * Creates captured-item coop attempts inside one already-scheduled Hytale world boundary.
 */
public final class HytaleCompanionCoopCapturedItemAttemptFactory
        implements CompanionCoopCapturedItemAttemptFactory {
    private final World world;
    private final Store<EntityStore> store;
    private final ComponentType<
            EntityStore,
            TameworkInventoryOperationReceiptsComponent> receiptType;
    private final HytaleCapturedArtifactAdapter artifacts;
    private final CoopTransitionEffectSink effects;

    public HytaleCompanionCoopCapturedItemAttemptFactory(
            @Nonnull World world,
            @Nonnull Store<EntityStore> store,
            @Nonnull ComponentType<
                    EntityStore,
                    TameworkInventoryOperationReceiptsComponent> receiptType
    ) {
        this(
                world,
                store,
                receiptType,
                new HytaleCapturedArtifactAdapter(),
                CoopTransitionEffectSink.NONE
        );
    }

    public HytaleCompanionCoopCapturedItemAttemptFactory(
            @Nonnull World world,
            @Nonnull Store<EntityStore> store,
            @Nonnull ComponentType<
                    EntityStore,
                    TameworkInventoryOperationReceiptsComponent> receiptType,
            @Nonnull HytaleCapturedArtifactAdapter artifacts,
            @Nonnull CoopTransitionEffectSink effects
    ) {
        this.world = Objects.requireNonNull(world, "world");
        this.store = Objects.requireNonNull(store, "store");
        this.receiptType = Objects.requireNonNull(
                receiptType, "receiptType"
        );
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.effects = Objects.requireNonNull(effects, "effects");
    }

    @Override
    @Nonnull
    public CompanionCoopCapturedItemAttempt open(
            @Nonnull CompanionCoopCaptureRequest request,
            @Nonnull OperationEnvelope operation
    ) {
        if (request == null || operation == null
                || !(request.source()
                instanceof CoopCapturedItemSourceEvidence source)) {
            throw new IllegalArgumentException(
                    "Captured-item coop attempt evidence is required"
            );
        }
        store.assertThread();
        return new HytaleCompanionCoopCapturedItemAttemptGateway(
                world,
                store,
                request,
                operation,
                receiptType,
                artifacts,
                new HytalePlayerDurabilityBarrier(
                        world,
                        store,
                        source.sourceWorldKey(),
                        source.actorUuid()
                ),
                effects
        );
    }
}
