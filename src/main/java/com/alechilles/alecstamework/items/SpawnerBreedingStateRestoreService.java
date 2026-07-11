package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.config.assets.TwHappinessConfig;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkHappinessComponent;
import com.alechilles.alecstamework.npc.progression.BreedingTimeService;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.alechilles.alecstamework.npc.progression.HappinessTimestampPolicy;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Restores breeding state from captured spawner metadata while preserving signed cooldown time.
 */
final class SpawnerBreedingStateRestoreService {
    private SpawnerBreedingStateRestoreService() {
    }

    static void restore(ItemStack stack, Ref<EntityStore> npcRef, Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkBreedingComponent> type = TameworkBreedingComponent.getComponentType();
        if (type == null) {
            return;
        }
        CapturedBreedingState captured = CapturedBreedingState.read(stack);
        if (!captured.hasData()) {
            return;
        }
        TameworkBreedingComponent existing = store.getComponent(npcRef, type);
        long nowMs = BreedingTimeService.resolveCurrentTimeMs(store);
        TameworkBreedingComponent restored = buildComponent(captured, existing, npcRef, store, nowMs);
        store.putComponent(npcRef, type, restored);
    }

    private static TameworkBreedingComponent buildComponent(
            CapturedBreedingState captured,
            @Nullable TameworkBreedingComponent existing,
            Ref<EntityStore> npcRef,
            Store<EntityStore> store,
            long nowMs) {
        String configId = captured.resolveConfigId(existing);
        double happiness = resolveHappiness(captured, existing, npcRef, store);
        long lastHappinessUpdateMs = resolveHappinessTimestamp(npcRef, store);
        long cooldownUntilMs = captured.resolveCooldownUntil(existing);
        BreedingTimeService.CooldownTiming timing = resolveRestoredCooldownTiming(
                cooldownUntilMs,
                existing,
                nowMs
        );
        boolean enabled = captured.enabled() != null
                ? captured.enabled()
                : existing != null && existing.isEnabled();
        UUID partner = captured.partnerUuid() != null
                ? captured.partnerUuid()
                : existing != null ? existing.getLastPartnerUuid() : null;
        boolean ready = resolveReady(configId, happiness, cooldownUntilMs, nowMs, enabled, npcRef, store);
        return new TameworkBreedingComponent(
                configId,
                happiness,
                HappinessTimestampPolicy.orNow(lastHappinessUpdateMs),
                ready,
                enabled,
                cooldownUntilMs,
                partner,
                timing.startedAtMs(),
                timing.durationMs()
        );
    }

    private static boolean resolveReady(@Nullable String configId,
                                        double happiness,
                                        long cooldownUntilMs,
                                        long nowMs,
                                        boolean enabled,
                                        Ref<EntityStore> npcRef,
                                        Store<EntityStore> store) {
        if (!enabled
                || configId == null
                || configId.isBlank()
                || BreedingTimeService.isDeadlineActive(cooldownUntilMs, nowMs)) {
            return false;
        }
        TwBreedingConfig config = TwBreedingConfig.resolveById(configId);
        if (config == null) {
            return false;
        }
        String roleId = CompanionRoleIdResolver.resolveRoleId(npcRef, store);
        return happiness >= TameworkRuntimeSettings.breedingHappinessThreshold(
                config.resolveHappiness(roleId).getThreshold(),
                TwHappinessConfig.isEnabledForRole(roleId)
        );
    }

    private static double resolveHappiness(CapturedBreedingState captured,
                                           @Nullable TameworkBreedingComponent existing,
                                           Ref<EntityStore> npcRef,
                                           Store<EntityStore> store) {
        TameworkHappinessComponent happiness = resolveHappinessComponent(npcRef, store);
        if (happiness != null && Double.isFinite(happiness.getValue())) {
            return happiness.getValue();
        }
        if (captured.legacyHappiness() != null) {
            return captured.legacyHappiness();
        }
        return existing != null ? existing.getHappiness() : 0.0;
    }

    private static long resolveHappinessTimestamp(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        TameworkHappinessComponent happiness = resolveHappinessComponent(npcRef, store);
        return happiness != null ? happiness.getLastUpdateMs() : 0L;
    }

    @Nullable
    private static TameworkHappinessComponent resolveHappinessComponent(
            Ref<EntityStore> npcRef,
            Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkHappinessComponent> type = TameworkHappinessComponent.getComponentType();
        return type != null ? store.getComponent(npcRef, type) : null;
    }

    static BreedingTimeService.CooldownTiming resolveRestoredCooldownTiming(
            long cooldownUntilMs,
            @Nullable TameworkBreedingComponent existing,
            long nowMs) {
        long startedAtMs = existing != null ? existing.getCooldownStartedAtMs() : 0L;
        long durationMs = existing != null ? existing.getCooldownDurationMs() : 0L;
        boolean preserveExisting = existing != null
                && cooldownUntilMs == existing.getCooldownUntilMs()
                && BreedingTimeService.isDeadlineActive(cooldownUntilMs, nowMs)
                && startedAtMs != 0L
                && durationMs > 0L;
        if (preserveExisting) {
            return new BreedingTimeService.CooldownTiming(cooldownUntilMs, startedAtMs, durationMs);
        }
        return BreedingTimeService.reconstructCooldownTiming(cooldownUntilMs, nowMs);
    }

    /** Raw breeding fields read from one captured spawner item. */
    private record CapturedBreedingState(@Nullable String configId,
                                         @Nullable Double legacyHappiness,
                                         @Nullable Boolean enabled,
                                         @Nullable Long cooldownUntilMs,
                                         @Nullable UUID partnerUuid) {
        static CapturedBreedingState read(ItemStack stack) {
            return new CapturedBreedingState(
                    stack.getFromMetadataOrNull(TameworkMetadataKeys.BREEDING_CONFIG_ID, Codec.STRING),
                    stack.getFromMetadataOrNull(TameworkMetadataKeys.BREEDING_HAPPINESS, Codec.DOUBLE),
                    stack.getFromMetadataOrNull(TameworkMetadataKeys.BREEDING_ENABLED, Codec.BOOLEAN),
                    stack.getFromMetadataOrNull(TameworkMetadataKeys.BREEDING_COOLDOWN_UNTIL, Codec.LONG),
                    stack.getFromMetadataOrNull(TameworkMetadataKeys.BREEDING_LAST_PARTNER_UUID, Codec.UUID_STRING)
            );
        }

        boolean hasData() {
            return (configId != null && !configId.isBlank())
                    || legacyHappiness != null
                    || enabled != null
                    || cooldownUntilMs != null
                    || partnerUuid != null;
        }

        @Nullable
        String resolveConfigId(@Nullable TameworkBreedingComponent existing) {
            return configId != null && !configId.isBlank()
                    ? configId
                    : existing != null ? existing.getConfigId() : null;
        }

        long resolveCooldownUntil(@Nullable TameworkBreedingComponent existing) {
            return cooldownUntilMs != null
                    ? cooldownUntilMs
                    : existing != null ? existing.getCooldownUntilMs() : 0L;
        }
    }
}
