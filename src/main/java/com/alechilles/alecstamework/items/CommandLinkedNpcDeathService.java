package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCompanionConfig;
import com.alechilles.alecstamework.config.assets.TwTalentConfig;
import com.alechilles.alecstamework.damage.DamageTargetMemoryService;
import com.alechilles.alecstamework.damage.RecentNeedsDeathCauseService;
import com.alechilles.alecstamework.items.persistence.DeathSnapshotV2Payload;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.progression.CompanionTalentService;
import com.alechilles.alecstamework.npc.progression.TalentIdCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Keeps process-local death presentation detail for linked companions.
 *
 * <p>The replacement dormant operation owns durable death state and immutable restoration
 * snapshots. This service has no repository, file, write queue, profile writer, or restart
 * authority.</p>
 */
public final class CommandLinkedNpcDeathService {
    private static final long RECENT_ATTACKER_MAX_AGE_MS = 30_000L;
    private static final String REVIVE_COOLDOWN_MULTIPLIER_EFFECT_KEY =
            "ReviveCooldownMultiplier";

    private final ConcurrentHashMap<UUID, DeadLinkedNpcSnapshot> deadByNpc =
            new ConcurrentHashMap<>();
    private final java.util.Set<UUID> permanentlyReleasedDeaths =
            ConcurrentHashMap.newKeySet();
    @Nullable
    private final CommandLinkedNpcStateSnapshotService stateSnapshotService;

    public CommandLinkedNpcDeathService() {
        this(null);
    }

    public CommandLinkedNpcDeathService(
            @Nullable CommandLinkedNpcStateSnapshotService stateSnapshotService
    ) {
        this.stateSnapshotService = stateSnapshotService;
    }

    public void onNpcAdded(
            Ref<EntityStore> reference,
            Store<EntityStore> store
    ) {
        NPCEntity npc = npc(reference, store);
        if (npc == null || npc.getUuid() == null) {
            return;
        }
        permanentlyReleasedDeaths.remove(npc.getUuid());
        deadByNpc.remove(npc.getUuid());
    }

    public void onNpcRemoved(
            Ref<EntityStore> reference,
            RemoveReason reason,
            Store<EntityStore> store
    ) {
        NPCEntity npc = npc(reference, store);
        if (npc == null || npc.getUuid() == null) {
            return;
        }
        UUID npcUuid = npc.getUuid();
        if (!wasDeathRemoval(reference, reason, store)) {
            permanentlyReleasedDeaths.remove(npcUuid);
            deadByNpc.remove(npcUuid);
            return;
        }
        TameworkCommandLinksComponent links = store.getComponent(
                reference, TameworkCommandLinksComponent.getComponentType()
        );
        if (links == null || links.getToolIds() == null
                || links.getToolIds().length == 0) {
            deadByNpc.remove(npcUuid);
            return;
        }
        String roleId = roleId(npc);
        if (!CompanionRevivePolicy.supportsRevive(roleId, links)) {
            permanentlyReleasedDeaths.add(npcUuid);
            deadByNpc.remove(npcUuid);
            return;
        }
        permanentlyReleasedDeaths.remove(npcUuid);
        DeadLinkedNpcSnapshot cached = currentSnapshot(
                reference, store, npcUuid
        );
        if (cached == null) {
            return;
        }
        long diedAtMs = System.currentTimeMillis();
        DeathDetails death = resolveDeathDetails(npcUuid, diedAtMs);
        DeadLinkedNpcSnapshot detail = new DeadLinkedNpcSnapshot(
                npcUuid,
                cached.ownerId() != null
                        ? cached.ownerId()
                        : links.getOwnerId(),
                cached.ownerName(),
                sanitizeToolIds(links.getToolIds()),
                firstNonBlank(cached.roleId(), roleId, null),
                cached.tamed(),
                cached.customName(),
                firstNonBlank(
                        cached.displayName(), roleId, "Dead companion"
                ),
                cached.lastKnownPosition(),
                links.hasHome()
                        ? links.getHomePosition()
                        : cached.homePosition(),
                diedAtMs,
                diedAtMs + resolveRespawnCooldownMs(roleId, cached),
                cached.breedingConfigId(),
                cached.breedingHappiness(),
                cached.breedingCooldownUntilMs(),
                cached.breedingLastPartnerUuid(),
                cached.traitsConfigId(),
                cached.traitsRollSeed(),
                cached.traitsValues(),
                cached.happinessConfigId(),
                cached.happinessValue(),
                cached.happinessLastUpdateMs(),
                cached.lifeStage(),
                cached.lifeStageBornAtMs(),
                cached.lifeStageAdolescentAtMs(),
                cached.lifeStageAdultAtMs(),
                cached.lifeStageFullyGrownAtMs(),
                cached.lifeStageBabyScale(),
                cached.lifeStageAdolescentScale(),
                cached.lifeStageAdolescentSwitchScale(),
                cached.lifeStageAdultStartScale(),
                cached.lifeStageAdultSwitchScale(),
                cached.lifeStageAdultScale(),
                cached.lifeStageGrowthScalingEnabled(),
                cached.attachmentsConfigId(),
                cached.attachmentsValues(),
                cached.breedingEnabled(),
                cached.levelingConfigId(),
                cached.levelingLevel(),
                cached.levelingTotalXp(),
                cached.talentsConfigId(),
                cached.talentsSpentPoints(),
                cached.purchasedTalentIds(),
                death.causeKind(),
                death.sourceName(),
                cached.lifeStageGender()
        );
        deadByNpc.put(npcUuid, detail);
    }

    public boolean isPermanentlyReleasedDeath(@Nullable UUID npcUuid) {
        return npcUuid != null
                && permanentlyReleasedDeaths.contains(npcUuid);
    }

    @Nullable
    public DeadLinkedNpcSnapshot getDeadSnapshot(UUID npcUuid) {
        return npcUuid == null ? null : deadByNpc.get(npcUuid);
    }

    @Nullable
    public DeadLinkedNpcSnapshot getDeadSnapshotForTool(
            UUID npcUuid,
            String toolId,
            @Nullable UUID ownerUuid
    ) {
        DeadLinkedNpcSnapshot snapshot = getDeadSnapshot(npcUuid);
        return snapshot != null
                && snapshot.containsToolId(toolId)
                && ownerCompatible(snapshot, ownerUuid)
                ? snapshot
                : null;
    }

    @Nullable
    public DeadLinkedNpcSnapshot getDeadSnapshotForOwner(
            UUID npcUuid,
            @Nullable UUID ownerUuid
    ) {
        DeadLinkedNpcSnapshot snapshot = getDeadSnapshot(npcUuid);
        return snapshot != null && ownerCompatible(snapshot, ownerUuid)
                ? snapshot
                : null;
    }

    public void clearDeadSnapshot(UUID npcUuid) {
        if (npcUuid == null) {
            return;
        }
        if (stateSnapshotService != null) {
            stateSnapshotService.clearSnapshot(npcUuid);
        }
        deadByNpc.remove(npcUuid);
    }

    @Nullable
    private DeadLinkedNpcSnapshot currentSnapshot(
            Ref<EntityStore> reference,
            Store<EntityStore> store,
            UUID npcUuid
    ) {
        if (stateSnapshotService == null) {
            return null;
        }
        stateSnapshotService.refreshFromEntity(reference, store);
        return stateSnapshotService.getSnapshot(npcUuid);
    }

    @Nullable
    private NPCEntity npc(
            Ref<EntityStore> reference,
            Store<EntityStore> store
    ) {
        if (reference == null || !reference.isValid() || store == null) {
            return null;
        }
        return store.getComponent(reference, NPCEntity.getComponentType());
    }

    private boolean wasDeathRemoval(
            Ref<EntityStore> reference,
            RemoveReason reason,
            Store<EntityStore> store
    ) {
        try {
            if (reference != null && reference.isValid() && store != null
                    && store.getArchetype(reference).contains(
                    DeathComponent.getComponentType()
            )) {
                return true;
            }
        } catch (RuntimeException ignored) {
            // Fall through to the explicit removal reason.
        }
        String text = reason == null ? null : reason.toString();
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        return normalized.contains("death")
                || normalized.contains("killed");
    }

    private long resolveRespawnCooldownMs(
            @Nullable String roleId,
            DeadLinkedNpcSnapshot snapshot
    ) {
        long configured = TwCompanionConfig.resolveEffectiveForRole(roleId)
                .getDeadRespawnCooldownMs();
        double multiplier = resolveSnapshotTalentMultiplier(snapshot);
        double scaled = Math.max(0L, configured) * multiplier;
        return Double.isFinite(scaled)
                ? Math.max(0L, Math.round(scaled))
                : Math.max(0L, configured);
    }

    private double resolveSnapshotTalentMultiplier(
            DeadLinkedNpcSnapshot snapshot
    ) {
        if (snapshot.talentsConfigId() == null
                || snapshot.purchasedTalentIds() == null) {
            return 1.0;
        }
        TwTalentConfig config = TwTalentConfig.resolveById(
                snapshot.talentsConfigId()
        );
        return config == null
                ? 1.0
                : CompanionTalentService.resolvePurchasedEffectMultiplier(
                config,
                TalentIdCodec.decode(snapshot.purchasedTalentIds()),
                REVIVE_COOLDOWN_MULTIPLIER_EFFECT_KEY,
                1.0
        );
    }

    @Nonnull
    private DeathDetails resolveDeathDetails(UUID npcUuid, long diedAtMs) {
        DeathSnapshotV2Payload.DeathCauseKind needs =
                RecentNeedsDeathCauseService.getInstance()
                .consumeRecent(npcUuid, diedAtMs);
        if (needs != null) {
            return new DeathDetails(mapDeathCauseKind(needs), null);
        }
        DamageTargetMemoryService.RecentAttackerSnapshot attacker =
                DamageTargetMemoryService.getInstance().getRecentAttacker(
                        npcUuid,
                        RECENT_ATTACKER_MAX_AGE_MS,
                        diedAtMs
                );
        if (attacker == null) {
            return new DeathDetails(DeathCauseKind.UNKNOWN, null);
        }
        DeathCauseKind cause = switch (attacker.attackerKind()) {
            case PLAYER -> DeathCauseKind.PLAYER;
            case NPC -> DeathCauseKind.NPC;
            default -> DeathCauseKind.UNKNOWN;
        };
        return new DeathDetails(cause, attacker.attackerName());
    }

    @Nonnull
    private DeathCauseKind mapDeathCauseKind(
            DeathSnapshotV2Payload.DeathCauseKind causeKind
    ) {
        return switch (causeKind) {
            case STARVATION -> DeathCauseKind.STARVATION;
            case DEHYDRATION -> DeathCauseKind.DEHYDRATION;
            case STARVATION_AND_DEHYDRATION ->
                    DeathCauseKind.STARVATION_AND_DEHYDRATION;
            case PLAYER -> DeathCauseKind.PLAYER;
            case NPC -> DeathCauseKind.NPC;
            case ENVIRONMENT -> DeathCauseKind.ENVIRONMENT;
            case UNKNOWN -> DeathCauseKind.UNKNOWN;
        };
    }

    private boolean ownerCompatible(
            DeadLinkedNpcSnapshot snapshot,
            @Nullable UUID ownerUuid
    ) {
        return snapshot.ownerId() == null
                || ownerUuid == null
                || snapshot.ownerId().equals(ownerUuid);
    }

    @Nullable
    private String roleId(NPCEntity npc) {
        String role = npc.getRoleName();
        return role == null || role.isBlank() ? null : role;
    }

    private String[] sanitizeToolIds(@Nullable String[] toolIds) {
        if (toolIds == null || toolIds.length == 0) {
            return new String[0];
        }
        return Arrays.stream(toolIds)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toArray(String[]::new);
    }

    @Nullable
    private String firstNonBlank(
            @Nullable String first,
            @Nullable String second,
            @Nullable String third
    ) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return third == null || third.isBlank() ? null : third;
    }

    public enum DeathCauseKind {
        STARVATION,
        DEHYDRATION,
        STARVATION_AND_DEHYDRATION,
        PLAYER,
        NPC,
        ENVIRONMENT,
        UNKNOWN
    }

    private record DeathDetails(
            @Nullable DeathCauseKind causeKind,
            @Nullable String sourceName
    ) {
    }

    /**
     * Immutable full-state detail copied before a linked companion leaves the live world.
     */
    public record DeadLinkedNpcSnapshot(
            UUID npcUuid,
            @Nullable UUID ownerId,
            @Nullable String ownerName,
            String[] toolIds,
            @Nullable String roleId,
            boolean tamed,
            @Nullable String customName,
            @Nullable String displayName,
            @Nullable Vector3d lastKnownPosition,
            @Nullable Vector3d homePosition,
            long diedAtMs,
            long respawnAvailableAtMs,
            @Nullable String breedingConfigId,
            @Nullable Double breedingHappiness,
            long breedingCooldownUntilMs,
            @Nullable UUID breedingLastPartnerUuid,
            @Nullable String traitsConfigId,
            long traitsRollSeed,
            @Nullable String traitsValues,
            @Nullable String happinessConfigId,
            @Nullable Double happinessValue,
            long happinessLastUpdateMs,
            @Nullable String lifeStage,
            long lifeStageBornAtMs,
            long lifeStageAdolescentAtMs,
            long lifeStageAdultAtMs,
            long lifeStageFullyGrownAtMs,
            double lifeStageBabyScale,
            double lifeStageAdolescentScale,
            double lifeStageAdolescentSwitchScale,
            double lifeStageAdultStartScale,
            double lifeStageAdultSwitchScale,
            double lifeStageAdultScale,
            boolean lifeStageGrowthScalingEnabled,
            @Nullable String attachmentsConfigId,
            @Nullable String attachmentsValues,
            boolean breedingEnabled,
            @Nullable String levelingConfigId,
            int levelingLevel,
            double levelingTotalXp,
            @Nullable String talentsConfigId,
            int talentsSpentPoints,
            @Nullable String purchasedTalentIds,
            @Nullable DeathCauseKind deathCauseKind,
            @Nullable String deathSourceName,
            @Nullable String lifeStageGender
    ) {
        public DeadLinkedNpcSnapshot {
            toolIds = toolIds == null
                    ? new String[0]
                    : toolIds.clone();
            lastKnownPosition = lastKnownPosition == null
                    ? null
                    : new Vector3d(lastKnownPosition);
            homePosition = homePosition == null
                    ? null
                    : new Vector3d(homePosition);
        }

        @Override
        public String[] toolIds() {
            return toolIds.clone();
        }

        @Override
        public Vector3d lastKnownPosition() {
            return lastKnownPosition == null
                    ? null
                    : new Vector3d(lastKnownPosition);
        }

        @Override
        public Vector3d homePosition() {
            return homePosition == null
                    ? null
                    : new Vector3d(homePosition);
        }

        public boolean containsToolId(String toolId) {
            if (toolId == null || toolId.isBlank()) {
                return false;
            }
            for (String value : toolIds) {
                if (toolId.equals(value)) {
                    return true;
                }
            }
            return false;
        }

        public boolean isRespawnReady() {
            return System.currentTimeMillis() >= respawnAvailableAtMs;
        }
    }
}
