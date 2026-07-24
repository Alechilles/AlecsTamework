package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.CaptureRequirementContext;
import com.alechilles.alecstamework.api.CaptureRequirementPhase;
import com.alechilles.alecstamework.api.CaptureChanceMode;
import com.alechilles.alecstamework.api.internal.CaptureRequirementRuntime;
import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.items.capturepolicy.CapturePolicyRegistry;
import com.alechilles.alecstamework.items.capturepolicy.SpawnerCaptureChanceService;
import com.alechilles.alecstamework.items.capturepolicy.runtime.CaptureEntropySource;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Freezes and evaluates one capture roll without a journal or recovery graph.
 *
 * <p>The attempt UUID is both the gameplay intent identity and the sole entropy input. Failed
 * rolls are terminal process-local outcomes and create no durable persistence state.</p>
 */
final class SpawnerCaptureRollService {
    private final CapturePolicyRegistry policies;
    private final CaptureRequirementRuntime requirements;
    private final SpawnerCaptureChanceService chance;
    private final CaptureEntropySource entropy;
    private final SpawnerCapturePolicyService capturePolicy;
    private final SpawnerRolePolicyService roles;

    SpawnerCaptureRollService(
            @Nonnull CapturePolicyRegistry policies,
            @Nonnull CaptureRequirementRuntime requirements,
            @Nonnull SpawnerCapturePolicyService capturePolicy,
            @Nonnull SpawnerRolePolicyService roles
    ) {
        this(
                policies,
                requirements,
                new SpawnerCaptureChanceService(requirements),
                CaptureEntropySource.sha256(),
                capturePolicy,
                roles
        );
    }

    SpawnerCaptureRollService(
            CapturePolicyRegistry policies,
            CaptureRequirementRuntime requirements,
            SpawnerCaptureChanceService chance,
            CaptureEntropySource entropy,
            SpawnerCapturePolicyService capturePolicy,
            SpawnerRolePolicyService roles
    ) {
        this.policies = Objects.requireNonNull(policies, "policies");
        this.requirements = Objects.requireNonNull(
                requirements, "requirements"
        );
        this.chance = Objects.requireNonNull(chance, "chance");
        this.entropy = Objects.requireNonNull(entropy, "entropy");
        this.capturePolicy = Objects.requireNonNull(
                capturePolicy, "capturePolicy"
        );
        this.roles = Objects.requireNonNull(roles, "roles");
    }

    @Nullable
    Resolution evaluate(
            @Nullable Player player,
            @Nullable Ref<EntityStore> targetRef,
            @Nullable ItemStack source,
            @Nullable ItemFeatureConfig config,
            @Nullable CaptureAttemptHandle attempt
    ) {
        World world = player == null ? null : player.getWorld();
        Store<EntityStore> store = world == null
                || world.getEntityStore() == null
                ? null
                : world.getEntityStore().getStore();
        NPCEntity npc = store == null || targetRef == null
                ? null
                : store.getComponent(targetRef, NPCEntity.getComponentType());
        UUID targetUuid = componentUuid(store, targetRef);
        String roleId = roles.resolveRoleIdFromNpc(npc);
        if (player == null || source == null || source.isEmpty()
                || config == null || attempt == null || world == null
                || targetUuid == null || roleId == null
                || roleId.isBlank()) {
            return null;
        }

        SpawnerCapturePolicyService.CaptureHealth health =
                capturePolicy.resolveCaptureHealth(targetRef, store);
        ItemFeatureConfig.CaptureItemMechanics mechanics =
                config.getCaptureMechanics();
        if (health == null
                && mechanics.chanceMode() != CaptureChanceMode.GUARANTEED) {
            return null;
        }
        double currentHealth = health == null ? 1.0D : health.currentHealth();
        double maximumHealth = health == null ? 1.0D : health.maximumHealth();
        long generation = Math.max(
                0L, requirements.captureRequirementGeneration()
        );
        CaptureRequirementContext context = new CaptureRequirementContext(
                attempt.attemptId(),
                CaptureRequirementPhase.FINAL_REVALIDATION,
                player.getUuid(),
                targetUuid,
                targetUuid.toString(),
                roleId,
                world.getName(),
                source.getItemId(),
                currentHealth / maximumHealth,
                CaptureRequirementContext.UNKNOWN_PROFILE_REVISION
        );
        SpawnerCaptureChanceService.Evaluation evaluation = chance.evaluate(
                mechanics,
                policies.snapshot().resolveForRole(roleId).orElse(null),
                currentHealth,
                maximumHealth,
                context,
                generation,
                () -> entropy.sample(attempt.attemptId())
        );
        return new Resolution(
                evaluation,
                targetUuid,
                roleId,
                health,
                generation
        );
    }

    @Nullable
    private UUID componentUuid(
            Store<EntityStore> store,
            Ref<EntityStore> reference
    ) {
        if (store == null || reference == null || !reference.isValid()
                || UUIDComponent.getComponentType() == null) {
            return null;
        }
        UUIDComponent identity = store.getComponent(
                reference, UUIDComponent.getComponentType()
        );
        return identity == null ? null : identity.getUuid();
    }

    record Resolution(
            @Nonnull SpawnerCaptureChanceService.Evaluation evaluation,
            @Nonnull UUID targetUuid,
            @Nonnull String roleId,
            @Nullable SpawnerCapturePolicyService.CaptureHealth health,
            long requirementGeneration
    ) {
    }
}
