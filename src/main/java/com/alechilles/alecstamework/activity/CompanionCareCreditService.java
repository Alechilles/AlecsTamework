package com.alechilles.alecstamework.activity;

import com.alechilles.alecstamework.npc.compat.NpcAlarmAccess;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.util.Alarm;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Owns the shared per-companion care-credit cooldown.
 *
 * <p>The default gate uses the existing NPC alarm. No state is persisted by
 * this service. Manual and autonomous care call the same gate.</p>
 */
public final class CompanionCareCreditService {
    /** Existing alarm name used by the husbandry feed cooldown. */
    public static final String CARE_CREDIT_ALARM = "Husbandry_Feed_Activity";
    /** Existing owner-care cooldown in seconds. */
    public static final long CARE_CREDIT_COOLDOWN_SECONDS = 600L;

    private final CreditGate creditGate;
    private final boolean liveAlarm;

    /** Creates a service backed by the live NPC alarm. */
    public CompanionCareCreditService() {
        this.creditGate = (ignoredCompanion, ignoredOwner) -> false;
        this.liveAlarm = true;
    }

    /** Creates a service with a narrow gate for deterministic behavior tests. */
    public CompanionCareCreditService(@Nonnull CreditGate creditGate) {
        this.creditGate = Objects.requireNonNull(creditGate, "creditGate");
        this.liveAlarm = false;
    }

    /** Attempts to consume the care credit for one companion and owner pair. */
    public boolean tryAcquire(
            @Nullable UUID companionId,
            @Nullable UUID ownerId
    ) {
        if (companionId == null || ownerId == null) {
            return false;
        }
        try {
            return creditGate.tryAcquire(companionId, ownerId);
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    /** Attempts to consume the same credit from the live NPC alarm. */
    public boolean tryAcquire(
            @Nullable Ref<EntityStore> npcRef,
            @Nullable Store<EntityStore> store
    ) {
        UUID companionId = companionId(npcRef, store);
        UUID ownerId = ownerId(npcRef, store);
        if (liveAlarm) {
            return tryAcquireNpcAlarm(npcRef, store);
        }
        return tryAcquire(companionId, ownerId);
    }

    @Nullable
    private static UUID companionId(
            @Nullable Ref<EntityStore> npcRef,
            @Nullable Store<EntityStore> store
    ) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return null;
        }
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        return npc == null ? null : npc.getUuid();
    }

    @Nullable
    private static UUID ownerId(
            @Nullable Ref<EntityStore> npcRef,
            @Nullable Store<EntityStore> store
    ) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return null;
        }
        var ownerType = com.alechilles.alecstamework.npc.components
                .TameworkOwnerComponent.getComponentType();
        if (ownerType == null) {
            return null;
        }
        var owner = store.getComponent(npcRef, ownerType);
        return owner == null ? null : owner.getOwnerId();
    }

    /** Narrow gate abstraction shared by manual and autonomous producers. */
    @FunctionalInterface
    public interface CreditGate {
        boolean tryAcquire(@Nonnull UUID companionId, @Nonnull UUID ownerId);
    }

    /** Applies the existing alarm policy to a live NPC. */
    public static boolean tryAcquireNpcAlarm(
            @Nullable Ref<EntityStore> npcRef,
            @Nullable Store<EntityStore> store
    ) {
        if (npcRef == null || !npcRef.isValid() || store == null
                || store.getComponent(npcRef, NPCEntity.getComponentType()) == null) {
            return false;
        }
        Alarm alarm = NpcAlarmAccess.resolveAlarm(
                npcRef, store, CARE_CREDIT_ALARM
        );
        if (alarm == null) {
            return false;
        }
        Instant now = Instant.now();
        if (alarm.isSet() && !alarm.hasPassed(now)) {
            return false;
        }
        try {
            alarm.set(
                    npcRef,
                    now.plusSeconds(CARE_CREDIT_COOLDOWN_SECONDS),
                    store
            );
            return true;
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }
}
