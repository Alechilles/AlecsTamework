package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.ownership.BreedingChildProjectionMarker;
import com.alechilles.alecstamework.ownership.PlannedCompanionSpawnProbe;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Finds a journal-reserved child by deterministic UUID and durable projection marker. */
final class BreedingChildProjectionProbe {
    private BreedingChildProjectionProbe() {
    }

    @Nonnull
    static Result probe(@Nonnull Store<EntityStore> store,
                        @Nonnull UUID plannedNpcUuid,
                        @Nonnull TameworkProjectionIdentityComponent expectedMarker) {
        World world = store.getExternalData() != null
                ? store.getExternalData().getWorld() : null;
        PlannedCompanionSpawnProbe.Result exact = PlannedCompanionSpawnProbe.probe(
                world, store, plannedNpcUuid
        );
        ComponentType<EntityStore, TameworkProjectionIdentityComponent> markerType =
                TameworkProjectionIdentityComponent.getComponentType();
        if (markerType == null) {
            return Result.ambiguous();
        }
        if (exact.outcomeAmbiguous()) {
            return Result.ambiguous();
        }
        try {
            Ref<EntityStore> exactRef = exact.present() ? exact.ref() : null;
            TameworkProjectionIdentityComponent exactMarker = exactRef == null
                    ? null : store.getComponent(exactRef, markerType);
            boolean exactMarkerMatches = BreedingChildProjectionMarker.matches(
                    exactMarker, expectedMarker
            );
            int otherMatches = countMatchingMarkers(
                    store, markerType, expectedMarker, exactRef
            );
            Outcome outcome = classify(
                    exact.present() ? ExactOutcome.PRESENT : ExactOutcome.ABSENT,
                    exactMarkerMatches,
                    otherMatches
            );
            return switch (outcome) {
                case PRESENT -> Result.present(exact.ref(), exact.npc());
                case ABSENT -> Result.absent();
                case AMBIGUOUS -> Result.ambiguous();
            };
        } catch (RuntimeException | LinkageError failure) {
            return Result.ambiguous();
        }
    }

    private static int countMatchingMarkers(
            Store<EntityStore> store,
            ComponentType<EntityStore, TameworkProjectionIdentityComponent> markerType,
            TameworkProjectionIdentityComponent expectedMarker,
            @Nullable Ref<EntityStore> excludedRef) {
        ComponentType<EntityStore, NPCEntity> npcType = NPCEntity.getComponentType();
        if (npcType == null) {
            throw new IllegalStateException("NPC component type unavailable");
        }
        int[] matches = new int[1];
        store.assertThread();
        store.forEachChunk(
                Query.and(markerType, npcType),
                (ArchetypeChunk<EntityStore> chunk,
                 CommandBuffer<EntityStore> ignored) -> {
                    for (int index = 0; index < chunk.size(); index++) {
                        Ref<EntityStore> ref = chunk.getReferenceTo(index);
                        TameworkProjectionIdentityComponent marker =
                                chunk.getComponent(index, markerType);
                        NPCEntity npc = chunk.getComponent(index, npcType);
                        boolean excluded = ref != null && excludedRef != null
                                && (ref == excludedRef || excludedRef.equals(ref));
                        if (!excluded && ref != null && ref.isValid() && npc != null
                                && BreedingChildProjectionMarker.matches(
                                        marker, expectedMarker
                                )) {
                            matches[0]++;
                        }
                    }
                }
        );
        return matches[0];
    }

    static Outcome classify(ExactOutcome exactOutcome,
                            boolean exactMarkerMatches,
                            int matchingMarkerCount) {
        if (exactOutcome == ExactOutcome.PRESENT) {
            return exactMarkerMatches && matchingMarkerCount == 0
                    ? Outcome.PRESENT : Outcome.AMBIGUOUS;
        }
        if (exactOutcome == ExactOutcome.AMBIGUOUS || matchingMarkerCount != 0) {
            return Outcome.AMBIGUOUS;
        }
        return Outcome.ABSENT;
    }

    enum ExactOutcome {
        PRESENT,
        ABSENT,
        AMBIGUOUS
    }

    enum Outcome {
        PRESENT,
        ABSENT,
        AMBIGUOUS
    }

    record Result(@Nullable Ref<EntityStore> ref,
                  @Nullable NPCEntity npc,
                  boolean absenceProven) {
        @Nonnull
        static Result present(@Nonnull Ref<EntityStore> ref, @Nonnull NPCEntity npc) {
            return new Result(ref, npc, false);
        }

        @Nonnull
        static Result absent() {
            return new Result(null, null, true);
        }

        @Nonnull
        static Result ambiguous() {
            return new Result(null, null, false);
        }

        boolean present() {
            return ref != null && npc != null;
        }

        boolean outcomeAmbiguous() {
            return !present() && !absenceProven;
        }
    }
}
