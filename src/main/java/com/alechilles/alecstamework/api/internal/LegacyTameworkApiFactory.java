package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.InteractionExtensionApi;
import com.alechilles.alecstamework.api.TraitEffectApi;
import com.alechilles.alecstamework.damage.SimpleClaimsTamedDamagePolicy;
import com.alechilles.alecstamework.items.CommandLinkedNpcStateSnapshotService;
import com.alechilles.alecstamework.persistence.sqlite.LegacyNpcProfilesApi;
import com.alechilles.alecstamework.persistence.sqlite.LegacyProfileDataApi;
import com.alechilles.alecstamework.persistence.sqlite.TameworkPersistenceRuntime;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Isolates the temporary, unsupported legacy persistence composition.
 *
 * <p>The replacement runtime bypasses this factory and supplies the same
 * immutable API boundaries directly. This class can therefore be deleted with
 * the legacy runtime without changing {@link TameworkApiImpl}.</p>
 */
public final class LegacyTameworkApiFactory {
    private LegacyTameworkApiFactory() {
    }

    @Nonnull
    public static TameworkApiImpl create(
            @Nonnull TameworkPersistenceRuntime persistence,
            @Nonnull TameworkEventBus eventBus,
            @Nullable CommandLinkedNpcStateSnapshotService snapshots,
            @Nonnull InteractionExtensionApi interactionExtensions,
            @Nonnull TraitEffectApi traitEffects
    ) {
        return create(
                persistence,
                eventBus,
                snapshots,
                interactionExtensions,
                traitEffects,
                new SimpleClaimsTamedDamagePolicy(),
                UnavailablePopulationPolicyAuthority.INSTANCE
        );
    }

    @Nonnull
    public static TameworkApiImpl create(
            @Nonnull TameworkPersistenceRuntime persistence,
            @Nonnull TameworkEventBus eventBus,
            @Nullable CommandLinkedNpcStateSnapshotService snapshots,
            @Nonnull InteractionExtensionApi interactionExtensions,
            @Nonnull TraitEffectApi traitEffects,
            @Nonnull SimpleClaimsTamedDamagePolicy damagePolicy
    ) {
        return create(
                persistence,
                eventBus,
                snapshots,
                interactionExtensions,
                traitEffects,
                damagePolicy,
                UnavailablePopulationPolicyAuthority.INSTANCE
        );
    }

    @Nonnull
    public static TameworkApiImpl create(
            @Nonnull TameworkPersistenceRuntime persistence,
            @Nonnull TameworkEventBus eventBus,
            @Nullable CommandLinkedNpcStateSnapshotService snapshots,
            @Nonnull InteractionExtensionApi interactionExtensions,
            @Nonnull TraitEffectApi traitEffects,
            @Nonnull SimpleClaimsTamedDamagePolicy damagePolicy,
            @Nonnull PopulationPolicyAuthority populationAuthority
    ) {
        Objects.requireNonNull(persistence, "persistence");
        Objects.requireNonNull(populationAuthority, "populationAuthority");
        PopulationPolicyApiDelegate population =
                new PopulationPolicyApiDelegate(populationAuthority);
        return new TameworkApiImpl(
                new LegacyNpcProfilesApi(
                        persistence.getNpcProfileRepository()
                ),
                new LegacyProfileDataApi(
                        persistence.getApiProfileDataRepository()
                ),
                new TameworkDiagnosticsApi(persistence, population),
                eventBus,
                snapshots,
                interactionExtensions,
                traitEffects,
                damagePolicy,
                populationAuthority
        );
    }
}
