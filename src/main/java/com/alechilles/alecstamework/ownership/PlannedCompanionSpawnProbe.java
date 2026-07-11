package com.alechilles.alecstamework.ownership;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Resolves the authoritative outcome of an exception-ambiguous deterministic NPC spawn. */
public final class PlannedCompanionSpawnProbe {
    private PlannedCompanionSpawnProbe() {
    }

    @Nonnull
    public static Result probe(@Nullable World world,
                               @Nullable Store<EntityStore> store,
                               @Nonnull UUID plannedNpcUuid) {
        Objects.requireNonNull(plannedNpcUuid, "plannedNpcUuid");
        if (world == null || store == null) {
            return Result.ambiguous();
        }
        try {
            Ref<EntityStore> ref = world.getEntityRef(plannedNpcUuid);
            if (ref == null || !ref.isValid()) {
                return Result.absent();
            }
            ComponentType<EntityStore, UUIDComponent> uuidType = UUIDComponent.getComponentType();
            ComponentType<EntityStore, NPCEntity> npcType = NPCEntity.getComponentType();
            if (uuidType == null || npcType == null) {
                return Result.ambiguous();
            }
            UUIDComponent identity = store.getComponent(ref, uuidType);
            NPCEntity npc = store.getComponent(ref, npcType);
            Outcome outcome = classify(
                    true,
                    identity == null ? null : identity.getUuid(),
                    plannedNpcUuid,
                    npc != null
            );
            return outcome == Outcome.PRESENT
                    ? Result.present(ref, npc)
                    : Result.ambiguous();
        } catch (RuntimeException | LinkageError failure) {
            return Result.ambiguous();
        }
    }

    @Nonnull
    static Outcome classify(boolean validReference,
                            @Nullable UUID observedNpcUuid,
                            @Nonnull UUID plannedNpcUuid,
                            boolean npcComponentPresent) {
        if (!validReference) {
            return Outcome.ABSENT;
        }
        return plannedNpcUuid.equals(observedNpcUuid) && npcComponentPresent
                ? Outcome.PRESENT
                : Outcome.AMBIGUOUS;
    }

    enum Outcome {
        PRESENT,
        ABSENT,
        AMBIGUOUS
    }

    public record Result(@Nullable Ref<EntityStore> ref,
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

        public boolean present() {
            return ref != null && npc != null;
        }

        public boolean outcomeAmbiguous() {
            return !present() && !absenceProven;
        }
    }
}
