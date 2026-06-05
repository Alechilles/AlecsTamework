package com.alechilles.alecstamework.damage;

import com.alechilles.alecstamework.items.RecentRespawnTraceService;
import com.alechilles.alecstamework.items.RespawnTraceLogSupport;
import com.alechilles.alecstamework.npc.NpcDisplayNameComponentService;
import com.alechilles.alecstamework.ownership.OwnerNameUtil;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Locale;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Records attacker -> victim hit relationships from applied damage events.
 */
public final class DamageTargetMemorySystem extends DamageEventSystem {
    private static final float MIN_RECORDED_DAMAGE = 0.0f;
    private final DamageTargetMemoryService memoryService = DamageTargetMemoryService.getInstance();

    @Override
    public SystemGroup<EntityStore> getGroup() {
        return DamageModule.get().getInspectDamageGroup();
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
        if (damage == null || damage.isCancelled() || damage.getAmount() <= MIN_RECORDED_DAMAGE) {
            return;
        }
        Damage.Source source = damage.getSource();
        Ref<EntityStore> victimRef = chunk.getReferenceTo(index);
        if (victimRef == null || !victimRef.isValid()) {
            return;
        }
        UUID victimUuid = resolveNpcUuid(victimRef, store);
        if (!(source instanceof Damage.EntitySource entitySource)) {
            if (victimUuid != null) {
                recordRespawnTraceDamage(victimUuid, victimRef, store, null, damage);
            }
            return;
        }
        Ref<EntityStore> attackerRef = entitySource.getRef();
        if (attackerRef == null || !attackerRef.isValid()) {
            return;
        }
        if (attackerRef.equals(victimRef)) {
            return;
        }
        long nowMs = resolveCurrentTimeMs(store);
        memoryService.recordHit(attackerRef, victimRef, nowMs);
        DamageTargetMemoryService.RecentAttackerSnapshot attackerSnapshot = resolveAttackerSnapshot(attackerRef, store, nowMs);
        if (victimUuid != null && attackerSnapshot != null) {
            memoryService.recordAttacker(victimUuid, attackerSnapshot, nowMs);
        }
        if (victimUuid != null) {
            recordRespawnTraceDamage(victimUuid, victimRef, store, attackerSnapshot, damage);
        }
    }

    private void recordRespawnTraceDamage(@Nonnull UUID victimUuid,
                                          @Nonnull Ref<EntityStore> victimRef,
                                          @Nonnull Store<EntityStore> store,
                                          @Nullable DamageTargetMemoryService.RecentAttackerSnapshot attackerSnapshot,
                                          @Nonnull Damage damage) {
        if (!RespawnTraceLogSupport.isEnabled()) {
            return;
        }
        long traceNowMs = System.currentTimeMillis();
        RecentRespawnTraceService service = RecentRespawnTraceService.getInstance();
        RecentRespawnTraceService.Trace trace = service.getRecentTrace(victimUuid, traceNowMs);
        if (trace == null || trace.firstDamage() != null) {
            return;
        }
        RecentRespawnTraceService.DamageEvent event = new RecentRespawnTraceService.DamageEvent(
                describeAttacker(attackerSnapshot),
                describeDamageSource(damage),
                String.format(Locale.ROOT, "%.3f", damage.getAmount()),
                RespawnTraceLogSupport.readHealth(victimRef, store)
        );
        if (service.recordFirstDamage(victimUuid, event, traceNowMs)) {
            RecentRespawnTraceService.Trace updated = service.getRecentTrace(victimUuid, traceNowMs);
            RespawnTraceLogSupport.warn(
                    updated,
                    "first_damage " + event.describe()
                            + " " + RespawnTraceLogSupport.describeNpcState(victimRef, store)
            );
        }
    }

    @Nonnull
    private String describeAttacker(@Nullable DamageTargetMemoryService.RecentAttackerSnapshot attackerSnapshot) {
        if (attackerSnapshot == null) {
            return "ENTITY:<unknown>";
        }
        String name = isBlank(attackerSnapshot.attackerName()) ? "<unnamed>" : attackerSnapshot.attackerName();
        return attackerSnapshot.attackerKind() + ":" + name + ":" + attackerSnapshot.attackerUuid();
    }

    @Nonnull
    private String describeDamageSource(@Nonnull Damage damage) {
        String sourceType = damage.getSource() != null
                ? damage.getSource().getClass().getSimpleName()
                : "<null-source>";
        String cause = damage.getCause() != null ? String.valueOf(damage.getCause().getId()) : "<null-cause>";
        return sourceType + "/" + cause;
    }

    private long resolveCurrentTimeMs(@Nonnull Store<EntityStore> store) {
        WorldTimeResource worldTime = store.getResource(WorldTimeResource.getResourceType());
        return worldTime != null ? worldTime.getGameTime().toEpochMilli() : System.currentTimeMillis();
    }

    @Nullable
    private UUID resolveNpcUuid(@Nonnull Ref<EntityStore> victimRef, @Nonnull Store<EntityStore> store) {
        NPCEntity victimNpc = store.getComponent(victimRef, NPCEntity.getComponentType());
        return victimNpc != null ? victimNpc.getUuid() : null;
    }

    @Nullable
    private DamageTargetMemoryService.RecentAttackerSnapshot resolveAttackerSnapshot(@Nonnull Ref<EntityStore> attackerRef,
                                                                                     @Nonnull Store<EntityStore> store,
                                                                                     long nowMs) {
        PlayerRef attackerPlayerRef = store.getComponent(attackerRef, PlayerRef.getComponentType());
        if (attackerPlayerRef != null && attackerPlayerRef.getUuid() != null) {
            Player attackerPlayer = store.getComponent(attackerRef, Player.getComponentType());
            String playerName = attackerPlayer != null ? OwnerNameUtil.resolve(attackerPlayer) : attackerPlayerRef.getUsername();
            return new DamageTargetMemoryService.RecentAttackerSnapshot(
                    attackerPlayerRef.getUuid(),
                    DamageTargetMemoryService.AttackerKind.PLAYER,
                    isBlank(playerName) ? attackerPlayerRef.getUsername() : playerName,
                    nowMs
            );
        }
        NPCEntity attackerNpc = store.getComponent(attackerRef, NPCEntity.getComponentType());
        if (attackerNpc != null && attackerNpc.getUuid() != null) {
            return new DamageTargetMemoryService.RecentAttackerSnapshot(
                    attackerNpc.getUuid(),
                    DamageTargetMemoryService.AttackerKind.NPC,
                    resolveNpcDisplayName(attackerRef, store, attackerNpc),
                    nowMs
            );
        }
        return null;
    }

    @Nonnull
    private String resolveNpcDisplayName(@Nonnull Ref<EntityStore> npcRef,
                                         @Nonnull Store<EntityStore> store,
                                         @Nonnull NPCEntity npc) {
        String componentName = NpcDisplayNameComponentService.resolvePersistentOrRuntimeName(npcRef, store);
        if (!isBlank(componentName)) {
            return componentName;
        }
        String legacy = npc.getLegacyDisplayName();
        if (!isBlank(legacy)) {
            return legacy;
        }
        String roleId = npc.getRoleName();
        if (isBlank(roleId) && npc.getRoleIndex() >= 0) {
            NPCPlugin npcPlugin = NPCPlugin.get();
            if (npcPlugin != null) {
                roleId = npcPlugin.getName(npc.getRoleIndex());
            }
        }
        return isBlank(roleId) ? "NPC" : roleId;
    }

    private boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }
}
