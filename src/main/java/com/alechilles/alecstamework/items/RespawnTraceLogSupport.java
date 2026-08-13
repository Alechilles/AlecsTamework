package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.companion.snapshot.SnapshotDecodeResult;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Shared formatting and emission for respawn trace diagnostics.
 */
public final class RespawnTraceLogSupport {
    private static final String PREFIX = "[tw-respawn-trace] ";

    private RespawnTraceLogSupport() {
    }

    public static boolean isEnabled() {
        Tamework plugin = Tamework.getInstance();
        return plugin != null && plugin.isDebugRespawnTraceEnabled();
    }

    @Nonnull
    public static RecentRespawnTraceService.Trace startTrace(@Nonnull String branch,
                                                             @Nullable UUID originalNpcUuid,
                                                             @Nullable UUID ownerUuid,
                                                             @Nullable String roleId,
                                                             @Nullable String toolId) {
        return RecentRespawnTraceService.getInstance().startTrace(
                branch,
                originalNpcUuid,
                ownerUuid,
                roleId,
                toolId,
                System.currentTimeMillis()
        );
    }

    @Nullable
    public static RecentRespawnTraceService.Trace recordReplacement(
            @Nullable RecentRespawnTraceService.Trace trace,
            @Nullable UUID replacementNpcUuid) {
        return RecentRespawnTraceService.getInstance().recordReplacementNpc(
                trace,
                replacementNpcUuid,
                System.currentTimeMillis()
        );
    }

    public static void log(@Nullable RecentRespawnTraceService.Trace trace, @Nonnull String message) {
        if (!isEnabled()) {
            return;
        }
        String prefix = trace == null
                ? ""
                : RecentRespawnTraceService.getInstance().describe(trace, System.currentTimeMillis()) + " ";
        emit(Level.INFO, prefix + message);
    }

    public static void warn(@Nullable RecentRespawnTraceService.Trace trace, @Nonnull String message) {
        if (!isEnabled()) {
            return;
        }
        String prefix = trace == null
                ? ""
                : RecentRespawnTraceService.getInstance().describe(trace, System.currentTimeMillis()) + " ";
        emit(Level.WARNING, prefix + message);
    }

    /** Logs the health and needs state that a return projection will restore. */
    public static void logSnapshot(
            @Nullable RecentRespawnTraceService.Trace trace,
            @Nonnull String stage,
            @Nullable CoopResidentStateSnapshotService.CoopResidentStateSnapshot snapshot) {
        if (!isEnabled()) {
            return;
        }
        log(trace, "snapshot stage=" + stage + " " + describeSnapshot(snapshot));
    }

    /** Logs either a decoded stored projection or its stable decode failure. */
    public static void logDecodedProjection(
            @Nullable RecentRespawnTraceService.Trace trace,
            @Nonnull String stage,
            @Nonnull SnapshotDecodeResult<
                    CoopResidentStateSnapshotService.CoopResidentStateSnapshot> decoded) {
        if (decoded instanceof SnapshotDecodeResult.Decoded<
                CoopResidentStateSnapshotService.CoopResidentStateSnapshot> found) {
            logSnapshot(trace, stage, found.value());
            return;
        }
        SnapshotDecodeResult.Failed<?> failed =
                (SnapshotDecodeResult.Failed<?>) decoded;
        warn(trace, "snapshot stage=" + stage
                + " decode=failed code=" + failed.code());
    }

    /**
     * Correlates a projected NPC with immediate damage callbacks and schedules two short probes.
     */
    public static void logProjectionResult(
            @Nullable World world,
            @Nullable UUID npcUuid,
            @Nullable RecentRespawnTraceService.Trace trace,
            @Nonnull String stage,
            @Nonnull String result,
            boolean confirmed) {
        if (!isEnabled() || world == null || npcUuid == null) {
            return;
        }
        Ref<EntityStore> npcRef = world.getEntityRef(npcUuid);
        Store<EntityStore> store = world.getEntityStore() == null
                ? null
                : world.getEntityStore().getStore();
        RecentRespawnTraceService.Trace correlated =
                npcRef != null && npcRef.isValid()
                        ? recordReplacement(trace, npcUuid)
                        : trace;
        log(correlated, "projection stage=" + stage
                + " result=" + result
                + " npc=" + npcUuid
                + " " + describeNpcState(npcRef, store));
        if (confirmed) {
            scheduleProbe(world, npcUuid, correlated, 250L, stage + "_after_250ms");
            scheduleProbe(world, npcUuid, correlated, 1000L, stage + "_after_1000ms");
        }
    }

    @Nonnull
    public static String describeSnapshot(
            @Nullable CoopResidentStateSnapshotService.CoopResidentStateSnapshot snapshot) {
        if (snapshot == null) {
            return "state=<null>";
        }
        return String.format(
                Locale.ROOT,
                "source=%s role=%s health=%s/%s percent=%s needs={%s} capturedAtMs=%d",
                snapshot.npcUuid(),
                snapshot.roleId(),
                number(snapshot.currentHealth()),
                number(snapshot.maximumHealth()),
                number(snapshot.healthPercent()),
                describeNeeds(snapshot.needs()),
                snapshot.capturedAtMs()
        );
    }

    public static void scheduleProbe(@Nullable World world,
                                     @Nullable UUID npcUuid,
                                     @Nullable RecentRespawnTraceService.Trace trace,
                                     long delayMs,
                                     @Nonnull String stage) {
        if (!isEnabled() || world == null || npcUuid == null) {
            return;
        }
        CompletableFuture.runAsync(
                () -> world.execute(() -> {
                    if (!isEnabled()) {
                        return;
                    }
                    Ref<EntityStore> npcRef = world.getEntityRef(npcUuid);
                    Store<EntityStore> store = world.getEntityStore() != null
                            ? world.getEntityStore().getStore()
                            : null;
                    log(trace, "probe stage=" + stage
                            + " npc=" + npcUuid
                            + " " + describeNpcState(npcRef, store));
                }),
                CompletableFuture.delayedExecutor(Math.max(0L, delayMs), TimeUnit.MILLISECONDS)
        );
    }

    @Nonnull
    public static String describeNpcState(@Nullable Ref<EntityStore> npcRef,
                                          @Nullable Store<EntityStore> store) {
        if (npcRef == null) {
            return "ref=<null>";
        }
        if (!npcRef.isValid()) {
            return "refValid=false";
        }
        if (store == null) {
            return "refValid=true store=<null>";
        }
        StringBuilder builder = new StringBuilder(160)
                .append("refValid=true")
                .append(" deathComponent=").append(hasDeathComponent(npcRef, store))
                .append(" health=").append(readHealth(npcRef, store))
                .append(" npc=").append(hasComponent(npcRef, store, NPCEntity.getComponentType()))
                .append(" links=").append(hasComponent(npcRef, store, TameworkCommandLinksComponent.getComponentType()))
                .append(" owner=").append(hasComponent(npcRef, store, TameworkOwnerComponent.getComponentType()))
                .append(" tamed=").append(hasComponent(npcRef, store, TameworkTamedComponent.getComponentType()))
                .append(" needs=").append(hasComponent(npcRef, store, TameworkNeedsComponent.getComponentType()));
        TransformComponent transform = safeGetComponent(npcRef, store, TransformComponent.getComponentType());
        if (transform != null) {
            builder.append(" pos=").append(String.valueOf(transform.getPosition()));
        }
        return builder.toString();
    }

    @Nonnull
    public static String readHealth(@Nullable Ref<EntityStore> npcRef,
                                    @Nullable Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return "<unknown>";
        }
        ComponentType<EntityStore, EntityStatMap> statType = EntityStatMap.getComponentType();
        if (statType == null) {
            return "<no-stat-type>";
        }
        EntityStatMap statMap = safeGetComponent(npcRef, store, statType);
        if (statMap == null) {
            return "<no-stat-map>";
        }
        int healthIndex = EntityStatType.getAssetMap().getIndex("Health");
        if (healthIndex < 0) {
            return "<no-health-stat>";
        }
        EntityStatValue value = statMap.get(healthIndex);
        if (value == null) {
            return "<no-health-value>";
        }
        return String.format(Locale.ROOT, "%.3f/%.3f", value.get(), value.getMax());
    }

    private static boolean hasDeathComponent(@Nonnull Ref<EntityStore> npcRef,
                                             @Nonnull Store<EntityStore> store) {
        try {
            return store.getArchetype(npcRef).contains(DeathComponent.getComponentType());
        } catch (Exception ignored) {
            return false;
        }
    }

    private static <T extends Component<EntityStore>> boolean hasComponent(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nullable ComponentType<EntityStore, T> type) {
        return type != null && safeGetComponent(ref, store, type) != null;
    }

    @Nullable
    private static <T extends Component<EntityStore>> T safeGetComponent(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nullable ComponentType<EntityStore, T> type) {
        if (type == null) {
            return null;
        }
        try {
            return store.getComponent(ref, type);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void emit(@Nonnull Level level, @Nonnull String message) {
        Tamework plugin = Tamework.getInstance();
        if (plugin == null || plugin.getLogger() == null) {
            return;
        }
        plugin.getLogger().at(level).log(PREFIX + message);
    }

    @Nonnull
    private static String number(@Nullable Double value) {
        return value == null
                ? "<none>"
                : String.format(Locale.ROOT, "%.3f", value);
    }

    @Nonnull
    private static String describeNeeds(@Nullable TameworkNeedsComponent needs) {
        if (needs == null) {
            return "<none>";
        }
        return String.format(
                Locale.ROOT,
                "config=%s hunger=%.3f thirst=%.3f pending=%.6f last=%d sweep=%d baseline=%.3f allowance=%.3f managed=%.3f",
                needs.getConfigId(), needs.getHunger(), needs.getThirst(),
                needs.getPendingNeedsDamage(), needs.getLastUpdateMs(),
                needs.getLastPassiveSweepMs(),
                needs.getRegenSuppressionBaselineHealth(),
                needs.getRegenSuppressionAllowedHeal(),
                needs.getLastManagedHealth()
        );
    }
}
