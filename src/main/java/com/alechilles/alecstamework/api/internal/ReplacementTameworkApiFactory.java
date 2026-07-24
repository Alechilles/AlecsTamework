package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.InteractionExtensionApi;
import com.alechilles.alecstamework.api.TraitEffectApi;
import com.alechilles.alecstamework.damage.SimpleClaimsTamedDamagePolicy;
import com.alechilles.alecstamework.items.CommandLinkedNpcStateSnapshotService;
import com.alechilles.alecstamework.persistence.facade.ReplacementNpcProfilesApi;
import com.alechilles.alecstamework.persistence.facade.ReplacementPersistenceDiagnosticsApi;
import com.alechilles.alecstamework.persistence.facade.ReplacementProfileDataApi;
import com.alechilles.alecstamework.persistence.runtime.PersistenceBootstrap;
import com.alechilles.alecstamework.persistence.runtime.PersistenceDomainFacades;
import java.time.Duration;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Final public-API composition over the replacement domain facade bundle. */
public final class ReplacementTameworkApiFactory {
    private ReplacementTameworkApiFactory() {
    }

    @Nonnull
    public static TameworkApiImpl create(
            @Nonnull PersistenceBootstrap persistence,
            @Nonnull Duration readTimeout,
            @Nonnull LongSupplier clock,
            @Nonnull TameworkEventBus eventBus,
            @Nullable CommandLinkedNpcStateSnapshotService snapshots,
            @Nonnull InteractionExtensionApi interactionExtensions,
            @Nonnull TraitEffectApi traitEffects,
            @Nonnull SimpleClaimsTamedDamagePolicy damagePolicy
    ) {
        if (persistence == null || readTimeout == null || clock == null) {
            throw new IllegalArgumentException(
                    "Complete replacement API composition is required"
            );
        }
        PersistenceDomainFacades facades = persistence.facades();
        return new TameworkApiImpl(
                new ReplacementNpcProfilesApi(
                        facades.queries(), readTimeout
                ),
                new ReplacementProfileDataApi(
                        facades.queries(), facades.operations(), clock
                ),
                new ReplacementPersistenceDiagnosticsApi(persistence),
                eventBus,
                snapshots,
                interactionExtensions,
                traitEffects,
                damagePolicy
        );
    }
}
