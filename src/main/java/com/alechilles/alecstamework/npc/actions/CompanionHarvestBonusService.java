package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.api.HusbandryOutcomeContext;
import com.alechilles.alecstamework.api.HusbandryOutcomeKind;
import com.alechilles.alecstamework.api.HusbandryOutcomeModifiers;
import com.alechilles.alecstamework.api.internal.HusbandryOutcomeRuntime;
import com.alechilles.alecstamework.npc.compat.NpcSupportAccess;
import com.alechilles.alecstamework.npc.params.StdScopeLookupCache;
import com.alechilles.alecstamework.npc.progression.CompanionProgressionModifierService;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.EntitySupport;
import com.hypixel.hytale.server.npc.util.expression.StdScope;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Shared harvest bonus policy helpers for loose drops and container harvests. */
final class CompanionHarvestBonusService {
    static final String HARVEST_BONUS_MODE_PARAM = "HarvestBonusMode";
    private static final String HARVEST_DOUBLE_DROP_CHANCE_EFFECT_KEY = "HarvestDoubleDropChanceMultiplier";
    private static final String MODE_DROP_DUPLICATE = "DropDuplicate";
    private static final String MODE_COOLDOWN_PRESERVE = "CooldownPreserve";
    private static final String MODE_DISABLED = "Disabled";
    private static final ConcurrentMap<UUID, Long> COOLDOWN_SKIP_TOKENS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<UUID, Long> COOLDOWN_HANDLED_TOKENS = new ConcurrentHashMap<>();
    private static final StdScopeLookupCache SCOPE_LOOKUP = new StdScopeLookupCache();

    private CompanionHarvestBonusService() {
    }

    static boolean shouldDuplicateDrops(@Nullable String mode,
                                        double multiplier,
                                        @Nonnull DoubleSupplier random) {
        if (!MODE_DROP_DUPLICATE.equalsIgnoreCase(normalizeMode(mode))) {
            return false;
        }
        return roll(multiplier, random);
    }

    /** Resolves trait and husbandry harvest bonuses with one authoritative roll path. */
    static int resolveBonusCopies(@Nullable Ref<EntityStore> npcRef,
                                  @Nullable Store<EntityStore> store,
                                  @Nullable Role role,
                                  @Nullable String productId,
                                  @Nonnull DoubleSupplier random) {
        boolean traitDuplicate = shouldDuplicateDrops(
                resolveHarvestBonusMode(role),
                resolveHarvestMultiplier(npcRef, store),
                random
        );
        HusbandryOutcomeModifiers modifiers = HusbandryOutcomeRuntime.resolve(
                HusbandryOutcomeKind.HARVEST_YIELD,
                npcRef,
                store,
                role,
                productId
        );
        return resolveBonusCopies(traitDuplicate, modifiers, random);
    }

    /** Resolves a harvest bonus for a pre-built action context. */
    static int resolveBonusCopies(boolean traitDuplicate,
                                  @Nonnull HusbandryOutcomeContext context,
                                  @Nonnull DoubleSupplier random) {
        return resolveBonusCopies(
                traitDuplicate,
                HusbandryOutcomeRuntime.resolve(context),
                random
        );
    }

    /** Combines an existing trait proc with the provider's harvest rolls. */
    static int resolveBonusCopies(boolean traitDuplicate,
                                  @Nullable HusbandryOutcomeModifiers modifiers,
                                  @Nonnull DoubleSupplier random) {
        int copies = traitDuplicate ? 1 : 0;
        HusbandryOutcomeModifiers safe = modifiers == null
                ? HusbandryOutcomeModifiers.identity() : modifiers;
        if (rollChance(safe.bonusOutputChance(), random)) {
            copies += rollChance(safe.tripleOutputChance(), random) ? 2 : 1;
        }
        return copies;
    }

    static boolean shouldPreserveCooldown(@Nullable String mode,
                                          double multiplier,
                                          @Nonnull DoubleSupplier random) {
        return MODE_COOLDOWN_PRESERVE.equalsIgnoreCase(normalizeMode(mode)) && roll(multiplier, random);
    }

    static boolean shouldDuplicateDrops(@Nullable Ref<EntityStore> npcRef,
                                        @Nullable Store<EntityStore> store,
                                        @Nullable Role role) {
        return shouldDuplicateDrops(
                resolveHarvestBonusMode(role),
                resolveHarvestMultiplier(npcRef, store),
                ThreadLocalRandom.current()::nextDouble
        );
    }

    static boolean shouldPreserveCooldown(@Nullable Ref<EntityStore> npcRef,
                                          @Nullable Store<EntityStore> store,
                                          @Nullable Role role) {
        return shouldPreserveCooldown(
                resolveHarvestBonusMode(role),
                resolveHarvestMultiplier(npcRef, store),
                ThreadLocalRandom.current()::nextDouble
        );
    }

    static boolean shouldPreserveCooldown(@Nullable String mode,
                                          @Nullable Ref<EntityStore> npcRef,
                                          @Nullable Store<EntityStore> store) {
        return shouldPreserveCooldown(
                mode,
                resolveHarvestMultiplier(npcRef, store),
                ThreadLocalRandom.current()::nextDouble
        );
    }

    static void markCooldownSkip(@Nullable Ref<EntityStore> npcRef, @Nullable Store<EntityStore> store) {
        UUID npcUuid = resolveNpcUuid(npcRef, store);
        if (npcUuid != null) {
            COOLDOWN_SKIP_TOKENS.put(npcUuid, System.nanoTime());
        }
    }

    static boolean consumeCooldownSkip(@Nullable Ref<EntityStore> npcRef, @Nullable Store<EntityStore> store) {
        UUID npcUuid = resolveNpcUuid(npcRef, store);
        return npcUuid != null && COOLDOWN_SKIP_TOKENS.remove(npcUuid) != null;
    }

    static void markCooldownHandled(@Nullable Ref<EntityStore> npcRef, @Nullable Store<EntityStore> store) {
        UUID npcUuid = resolveNpcUuid(npcRef, store);
        if (npcUuid != null) {
            COOLDOWN_HANDLED_TOKENS.put(npcUuid, System.nanoTime());
        }
    }

    static boolean consumeCooldownHandled(@Nullable Ref<EntityStore> npcRef, @Nullable Store<EntityStore> store) {
        UUID npcUuid = resolveNpcUuid(npcRef, store);
        return npcUuid != null && COOLDOWN_HANDLED_TOKENS.remove(npcUuid) != null;
    }

    @Nonnull
    private static String normalizeMode(@Nullable String mode) {
        if (mode == null || mode.isBlank()) {
            return MODE_DROP_DUPLICATE;
        }
        String trimmed = mode.trim();
        if (MODE_COOLDOWN_PRESERVE.equalsIgnoreCase(trimmed)) {
            return MODE_COOLDOWN_PRESERVE;
        }
        if (MODE_DISABLED.equalsIgnoreCase(trimmed)) {
            return MODE_DISABLED;
        }
        return MODE_DROP_DUPLICATE;
    }

    private static boolean roll(double multiplier, @Nonnull DoubleSupplier random) {
        if (!Double.isFinite(multiplier)) {
            return false;
        }
        double chance = Math.max(0.0, Math.min(1.0, multiplier - 1.0));
        return chance > 0.0 && random.getAsDouble() < chance;
    }

    private static boolean rollChance(double chance, @Nonnull DoubleSupplier random) {
        if (!Double.isFinite(chance)) {
            return false;
        }
        double bounded = Math.max(0.0, Math.min(1.0, chance));
        return random.getAsDouble() < bounded;
    }

    private static double resolveHarvestMultiplier(@Nullable Ref<EntityStore> npcRef,
                                                   @Nullable Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return 1.0;
        }
        return CompanionProgressionModifierService.resolveMultiplier(
                npcRef,
                store,
                HARVEST_DOUBLE_DROP_CHANCE_EFFECT_KEY,
                1.0
        );
    }

    @Nullable
    private static String resolveHarvestBonusMode(@Nullable Role role) {
        if (role == null) {
            return null;
        }
        EntitySupport support = NpcSupportAccess.entity(role, null, null);
        StdScope scope = support != null ? support.getSensorScope() : null;
        return SCOPE_LOOKUP.getString(scope, HARVEST_BONUS_MODE_PARAM);
    }

    @Nullable
    private static UUID resolveNpcUuid(@Nullable Ref<EntityStore> npcRef, @Nullable Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return null;
        }
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        return npc != null ? npc.getUuid() : null;
    }
}
