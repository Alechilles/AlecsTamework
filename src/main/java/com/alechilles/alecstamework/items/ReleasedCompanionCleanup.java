package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceQueries;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;

/** Removes exact terminal aliases on their world thread, including animals loaded after release. */
public final class ReleasedCompanionCleanup {
    private final PublicPersistenceQueries queries;

    public ReleasedCompanionCleanup(@Nonnull PublicPersistenceQueries queries) {
        this.queries = java.util.Objects.requireNonNull(queries);
    }

    /** Event-only terminal cleanup; ordinary load initialization still runs. */
    public void onNpcAdded(@Nonnull String worldName, @Nonnull UUID alias) {
        var profile = queries.projectedProfile(new NpcAlias(alias)).orElse(null);
        if (profile == null || profile.lifecycleState() != LifecycleState.RELEASED) return;
        remove(queries, worldName, alias).exceptionally(failure -> {
            com.hypixel.hytale.logger.HytaleLogger.forEnclosingClass().at(java.util.logging.Level.WARNING)
                    .withCause(failure).log("Could not remove released companion %s on load", alias);
            return null;
        });
    }

    /** Carries only stable IDs across the durable read and deferred world callback. */
    @Nonnull
    public static CompletionStage<Void> remove(@Nonnull PublicPersistenceQueries queries,
                                               @Nonnull String worldName, @Nonnull UUID alias) {
        return queries.findProfile(new NpcAlias(alias)).thenCompose(read -> {
            if (read instanceof PersistenceReadResult.Failed<?> failed) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "released_cleanup_read_failed: " + failed.failure()));
            }
            if (!(read instanceof PersistenceReadResult.Found<CompanionProfileReadModel> found)
                    || !matches(found.value(), alias)) return CompletableFuture.completedFuture(null);
            var universe = Universe.get();
            var world = universe == null ? null : universe.getWorld(worldName);
            if (world == null || !world.isAlive()) return CompletableFuture.completedFuture(null);
            CompletableFuture<Void> done = new CompletableFuture<>();
            world.execute(() -> {
                try {
                    var currentWorld = Universe.get().getWorld(worldName);
                    if (currentWorld == null || currentWorld != world) { done.complete(null); return; }
                    var store = currentWorld.getEntityStore().getStore();
                    var ref = currentWorld.getEntityRef(alias);
                    if (ref != null && ref.isValid() && store.getComponent(ref, NPCEntity.getComponentType()) != null
                            && CommandGenericTargetAuthority.allowsGenericTargetMutation(ref, store)) {
                        // Recheck publication at execution time; never remove a replaced or re-owned profile.
                        var latest = queries.projectedProfile(new NpcAlias(alias)).orElse(null);
                        if (latest == null || !latest.profileId().equals(found.value().identity().profileId())
                                || latest.lifecycleState() != LifecycleState.RELEASED
                                || latest.ownerId() != null || latest.currentAlias() == null
                                || !alias.equals(latest.currentAlias().value())) {
                            done.complete(null); return;
                        }
                        var ownerType = TameworkOwnerComponent.getComponentType();
                        var tamedType = TameworkTamedComponent.getComponentType();
                        var linksType = TameworkCommandLinksComponent.getComponentType();
                        if (ownerType == null || tamedType == null || linksType == null) {
                            throw new IllegalStateException("released_cleanup_components_unavailable");
                        }
                        store.tryRemoveComponent(ref, ownerType);
                        store.tryRemoveComponent(ref, tamedType);
                        store.tryRemoveComponent(ref, linksType);
                        store.removeEntity(ref, RemoveReason.REMOVE);
                    }
                    done.complete(null);
                } catch (RuntimeException failure) { done.completeExceptionally(failure); }
            });
            return done.orTimeout(10, TimeUnit.SECONDS);
        });
    }

    static boolean matches(CompanionProfileReadModel profile, UUID alias) {
        return profile.lifecycle().state() == LifecycleState.RELEASED
                && profile.lifecycle().ownerId() == null && profile.lifecycle().activeOperationId() == null
                && !profile.lifecycle().quarantined() && profile.currentAlias() != null
                && profile.currentAlias().state() == com.alechilles.alecstamework.companion.identity.CompanionAlias.State.CURRENT
                && alias.equals(profile.currentAlias().alias().value());
    }
}
