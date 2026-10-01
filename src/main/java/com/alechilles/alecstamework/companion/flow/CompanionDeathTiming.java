package com.alechilles.alecstamework.companion.flow;

import com.alechilles.alecstamework.config.assets.TwCompanionConfig;
import com.alechilles.alecstamework.damage.DamageTargetMemoryService;
import com.alechilles.alecstamework.damage.RecentNeedsDeathCauseService;
import com.alechilles.alecstamework.items.persistence.DeathSnapshotV2Payload;
import com.alechilles.alecstamework.npc.progression.CompanionProgressionModifierService;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Revive time and cause of a companion death, shared by the companion index and the old dormant
 * death path. Call on the body's world thread. Consumes the recent needs-death hint for the NPC.
 */
public final class CompanionDeathTiming {
    private static final String REVIVE_COOLDOWN_MULTIPLIER = "ReviveCooldownMultiplier";
    private static final long RECENT_ATTACKER_MAX_AGE_MS = 30_000L;

    /** Why the companion died. */
    public enum Kind {
        STARVATION,
        DEHYDRATION,
        STARVATION_AND_DEHYDRATION,
        PLAYER,
        NPC,
        ENVIRONMENT,
        UNKNOWN
    }

    /**
     * @param reviveAvailableAtMs wall-clock time the companion can be revived
     * @param attackerName        the recent attacker's name, when one was remembered
     */
    public record Timing(long reviveAvailableAtMs, @Nonnull Kind kind, @Nullable String attackerName) {
        public Timing {
            Objects.requireNonNull(kind, "kind");
        }

        /** The kind's name, followed by {@code ":" + attackerName} when there is an attacker name. */
        @Nonnull
        public String cause() {
            return attackerName == null ? kind.name() : kind.name() + ":" + attackerName;
        }
    }

    private CompanionDeathTiming() {
    }

    /**
     * Precondition: not an old-age death; callers handle old age first.
     *
     * @param ref the dying body, or null when the death is seen only at removal; then the
     *            progression modifier is not read and the multiplier is 1.0
     */
    @Nonnull
    public static Timing resolve(@Nullable Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                 @Nullable UUID npcUuid, @Nullable String roleId,
                                 @Nonnull DeathComponent death, long diedAtMs) {
        DamageTargetMemoryService.RecentAttackerSnapshot attacker =
                DamageTargetMemoryService.getInstance().getRecentAttacker(npcUuid, RECENT_ATTACKER_MAX_AGE_MS, diedAtMs);
        DeathSnapshotV2Payload.DeathCauseKind needs =
                RecentNeedsDeathCauseService.getInstance().consumeRecent(npcUuid, diedAtMs);
        Kind kind = needs != null
                ? Kind.valueOf(needs.name())
                : attacker != null
                ? attackerKind(attacker)
                : persistedKind(death);
        long cooldown = reviveCooldownMs(ref, store, roleId);
        String attackerName = attacker == null ? null : attacker.attackerName();
        return new Timing(saturatingAdd(diedAtMs, cooldown), kind,
                attackerName == null || attackerName.isBlank() ? null : attackerName);
    }

    private static long reviveCooldownMs(@Nullable Ref<EntityStore> ref, Store<EntityStore> store,
                                         @Nullable String roleId) {
        // Revive.GameplayCooldownMs is authoritative; a legacy DeadRespawnCooldown value flows into it
        // when the config has no explicit Revive block.
        long configured = Math.max(0L,
                TwCompanionConfig.resolveEffectiveForRole(roleId).getRevive().getGameplayCooldownMs());
        double multiplier = ref == null
                ? 1.0
                : CompanionProgressionModifierService.resolveMultiplier(ref, store, REVIVE_COOLDOWN_MULTIPLIER, 1.0);
        if (!Double.isFinite(multiplier) || multiplier <= 0.0) {
            multiplier = 1.0;
        }
        double scaled = configured * multiplier;
        return Double.isFinite(scaled) ? Math.max(0L, Math.round(scaled)) : configured;
    }

    private static Kind attackerKind(DamageTargetMemoryService.RecentAttackerSnapshot attacker) {
        return switch (attacker.attackerKind()) {
            case PLAYER -> Kind.PLAYER;
            case NPC -> Kind.NPC;
            case OTHER -> Kind.UNKNOWN;
        };
    }

    private static Kind persistedKind(DeathComponent death) {
        if (death.getDeathCause() == null) {
            return Kind.UNKNOWN;
        }
        String id = death.getDeathCause().getId();
        if (id == null || id.isBlank()) {
            return Kind.UNKNOWN;
        }
        String normalized = id.toLowerCase(Locale.ROOT);
        return normalized.contains("physical") || normalized.contains("projectile") ? Kind.UNKNOWN : Kind.ENVIRONMENT;
    }

    private static long saturatingAdd(long value, long nonnegativeDelta) {
        return value > Long.MAX_VALUE - nonnegativeDelta ? Long.MAX_VALUE : value + nonnegativeDelta;
    }
}
