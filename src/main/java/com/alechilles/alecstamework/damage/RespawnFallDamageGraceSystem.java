package com.alechilles.alecstamework.damage;

import com.alechilles.alecstamework.items.RecentRespawnTraceService;
import com.alechilles.alecstamework.items.RespawnTraceLogSupport;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Logger;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Suppresses invalid engine fall damage during the first moments after companion spawning.
 */
public final class RespawnFallDamageGraceSystem extends DamageEventSystem {
    private static final Logger LOGGER = Logger.getLogger(RespawnFallDamageGraceSystem.class.getName());
    private static final long FALL_DAMAGE_GRACE_MS = 2_000L;
    private static final String FALL_CAUSE_ID = "Fall";

    @Override
    public SystemGroup<EntityStore> getGroup() {
        return DamageModule.get().getFilterDamageGroup();
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Query.any();
    }

    @Override
    public void handle(int index,
                       ArchetypeChunk<EntityStore> chunk,
                       Store<EntityStore> store,
                       CommandBuffer<EntityStore> commandBuffer,
                       Damage damage) {
        if (damage == null || damage.isCancelled() || damage.getAmount() <= 0.0f || !isFallDamage(damage)) {
            return;
        }
        Ref<EntityStore> targetRef = chunk != null ? chunk.getReferenceTo(index) : null;
        if (targetRef == null || !targetRef.isValid() || store == null) {
            return;
        }
        UUID npcUuid = resolveNpcUuid(targetRef, store);
        if (npcUuid == null) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        RecentRespawnTraceService.Trace trace = RecentRespawnTraceService.getInstance().getRecentTrace(npcUuid, nowMs);
        RecentSpawnProtectionService.Protection protection =
                RecentSpawnProtectionService.getInstance().getRecentProtection(npcUuid, nowMs);
        if (!isWithinFallDamageGrace(trace, nowMs) && !isWithinSpawnProtectionGrace(protection, nowMs)) {
            return;
        }

        float amount = damage.getAmount();
        damage.setAmount(0.0f);
        damage.setCancelled(true);
        String message = "fall_damage_grace_cancelled amount=" + String.format(Locale.ROOT, "%.3f", amount)
                + " graceMs=" + FALL_DAMAGE_GRACE_MS
                + " " + RespawnTraceLogSupport.describeNpcState(targetRef, store);
        if (trace != null) {
            RespawnTraceLogSupport.warn(trace, message);
        } else if (protection != null) {
            LOGGER.warning("[tw-spawn-protection] branch=" + protection.branch()
                    + " role=" + protection.roleId()
                    + " npc=" + protection.npcUuid()
                    + " ageMs=" + (nowMs - protection.recordedAtMs())
                    + " " + message);
        }
    }

    static boolean isWithinFallDamageGrace(@Nullable RecentRespawnTraceService.Trace trace, long nowMs) {
        if (trace == null || trace.replacementRecordedAtMs() <= 0L) {
            return false;
        }
        long ageMs = nowMs - trace.replacementRecordedAtMs();
        return ageMs >= 0L && ageMs <= FALL_DAMAGE_GRACE_MS;
    }

    static boolean isWithinSpawnProtectionGrace(@Nullable RecentSpawnProtectionService.Protection protection, long nowMs) {
        if (protection == null || protection.recordedAtMs() <= 0L) {
            return false;
        }
        long ageMs = nowMs - protection.recordedAtMs();
        return ageMs >= 0L && ageMs <= FALL_DAMAGE_GRACE_MS;
    }

    private boolean isFallDamage(@Nonnull Damage damage) {
        if (damage.getCause() == null || damage.getCause().getId() == null) {
            return false;
        }
        return FALL_CAUSE_ID.equalsIgnoreCase(String.valueOf(damage.getCause().getId()));
    }

    @Nullable
    private UUID resolveNpcUuid(@Nonnull Ref<EntityStore> targetRef, @Nonnull Store<EntityStore> store) {
        NPCEntity npc = store.getComponent(targetRef, NPCEntity.getComponentType());
        return npc != null ? npc.getUuid() : null;
    }
}
