package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.api.CaptureRequirementContext;
import com.alechilles.alecstamework.api.CaptureRequirementDecision;
import com.alechilles.alecstamework.api.CaptureRequirementSpec;
import com.alechilles.alecstamework.api.CaptureSourceConsumption;
import com.alechilles.alecstamework.api.CaptureSuccessDisposition;
import com.alechilles.alecstamework.api.internal.CaptureRequirementRuntime;
import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.config.CommandItemRegistry;
import com.alechilles.alecstamework.config.bonded.BondedCompanionRosterRegistry;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.items.capturepolicy.CapturePolicyRegistry;
import com.alechilles.alecstamework.items.capturepolicy.SpawnerCaptureChanceService;
import com.alechilles.alecstamework.items.persistence.SpawnerCaptureAuthor;
import com.alechilles.alecstamework.items.persistence.SpawnerCaptureIntent;
import com.alechilles.alecstamework.items.persistence.SpawnerCapturedArtifactIdentity;
import com.alechilles.alecstamework.items.persistence.SpawnerCapturedArtifactReleaseAuthor;
import com.alechilles.alecstamework.items.persistence
        .SpawnerTameAndLinkEvidenceSource;
import com.alechilles.alecstamework.localization.TranslationRegistry;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.Entity;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerInteractEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Thin gameplay orchestrator for canonical spawner capture and captured-artifact release.
 *
 * <p>Only immutable attempt handles and active channel handoffs are process-local. A successful
 * roll is submitted to the canonical persistence operation; failed rolls create no durable
 * journal, recovery row, or second lifecycle authority.</p>
 */
public final class SpawnerFeatureHandler {
    private final HytaleLogger logger;
    private final ItemFeatureRegistry registry;
    private final SpawnerRolePolicyService roles;
    private final SpawnerItemStackMetadataService itemMetadata;
    private final SpawnerPlayerInventoryService inventory;
    private final SpawnerCapturePolicyService capturePolicy;
    private final SpawnerCaptureRollService captureRolls;
    private final SpawnerCaptureIntentFactory captureIntents;
    private final SpawnerReleaseIntentFactory releaseIntents;
    private final SpawnerEffectService effects;
    @Nullable
    private final SpawnerCaptureAuthor captureAuthor;
    @Nullable
    private final SpawnerCapturedArtifactReleaseAuthor releaseAuthor;
    private final SpawnerCaptureChannelService channels;
    @Nullable private final BondedCompanionCaptureAuthor bondedCaptureAuthor;
    @Nullable private final BondedCompanionRosterRegistry bondedRosters;
    private final BondedCompanionCaptureAdmissionService bondedAdmission;

    /** Creates the released spawner composition over the canonical operation authors. */
    public SpawnerFeatureHandler(
            @Nonnull HytaleLogger logger,
            @Nonnull ItemFeatureRegistry registry,
            @Nullable TranslationRegistry translations,
            @Nonnull SpawnerCaptureAuthor captureAuthor,
            @Nonnull SpawnerCapturedArtifactReleaseAuthor releaseAuthor,
            @Nonnull CapturePolicyRegistry capturePolicies,
            @Nonnull CaptureRequirementRuntime captureRequirements
    ) {
        this(
                logger,
                registry,
                translations,
                captureAuthor,
                releaseAuthor,
                capturePolicies,
                captureRequirements,
                SpawnerTameAndLinkEvidenceSource.unavailable(),
                null, null, null
        );
    }

    /** Creates the canonical spawner composition with tame/link evidence. */
    public SpawnerFeatureHandler(
            @Nonnull HytaleLogger logger,
            @Nonnull ItemFeatureRegistry registry,
            @Nullable TranslationRegistry translations,
            @Nonnull SpawnerCaptureAuthor captureAuthor,
            @Nonnull SpawnerCapturedArtifactReleaseAuthor releaseAuthor,
            @Nonnull CapturePolicyRegistry capturePolicies,
            @Nonnull CaptureRequirementRuntime captureRequirements,
            @Nonnull SpawnerTameAndLinkEvidenceSource tameAndLinkEvidence
    ) {
        this(
                logger,
                registry,
                translations,
                Objects.requireNonNull(captureAuthor, "captureAuthor"),
                Objects.requireNonNull(releaseAuthor, "releaseAuthor"),
                Objects.requireNonNull(capturePolicies, "capturePolicies"),
                Objects.requireNonNull(
                        captureRequirements, "captureRequirements"
                ),
                Objects.requireNonNull(
                        tameAndLinkEvidence, "tameAndLinkEvidence"
                ),
                null, null, null,
                true
        );
    }

    /** Creates the canonical composition with the isolated bonded capture route. */
    public SpawnerFeatureHandler(
            @Nonnull HytaleLogger logger,
            @Nonnull ItemFeatureRegistry registry,
            @Nullable TranslationRegistry translations,
            @Nonnull SpawnerCaptureAuthor captureAuthor,
            @Nonnull SpawnerCapturedArtifactReleaseAuthor releaseAuthor,
            @Nonnull CapturePolicyRegistry capturePolicies,
            @Nonnull CaptureRequirementRuntime captureRequirements,
            @Nonnull SpawnerTameAndLinkEvidenceSource tameAndLinkEvidence,
            @Nonnull BondedCompanionCaptureAuthor bondedCaptureAuthor,
            @Nonnull BondedCompanionRosterRegistry bondedRosters,
            @Nonnull CommandItemRegistry commandItems
    ) {
        this(logger, registry, translations, captureAuthor, releaseAuthor,
                capturePolicies, captureRequirements, tameAndLinkEvidence,
                bondedCaptureAuthor, bondedRosters, commandItems, true);
    }

    private SpawnerFeatureHandler(
            HytaleLogger logger,
            ItemFeatureRegistry registry,
            TranslationRegistry translations,
            SpawnerCaptureAuthor captureAuthor,
            SpawnerCapturedArtifactReleaseAuthor releaseAuthor,
            CapturePolicyRegistry capturePolicies,
            CaptureRequirementRuntime captureRequirements,
            SpawnerTameAndLinkEvidenceSource tameAndLinkEvidence,
            BondedCompanionCaptureAuthor bondedCaptureAuthor,
            BondedCompanionRosterRegistry bondedRosters,
            CommandItemRegistry commandItems,
            boolean canonicalComposition
    ) {
        this.logger = logger;
        this.registry = registry;
        this.captureAuthor = captureAuthor;
        this.releaseAuthor = releaseAuthor;
        this.bondedCaptureAuthor = bondedCaptureAuthor;
        this.bondedRosters = bondedRosters;
        this.roles = new SpawnerRolePolicyService(logger);
        this.inventory = new SpawnerPlayerInventoryService();
        SpawnerCaptureMetadataService captureMetadata =
                new SpawnerCaptureMetadataService(logger, registry);
        SpawnerNpcProgressionMetadataService progression =
                new SpawnerNpcProgressionMetadataService();
        this.itemMetadata = new SpawnerItemStackMetadataService(
                registry, captureMetadata, progression
        );
        SpawnerNpcStateService npcState = new SpawnerNpcStateService();
        SpawnerNpcIdentityService npcIdentity =
                new SpawnerNpcIdentityService();
        SpawnerOwnershipPolicyService ownership =
                new SpawnerOwnershipPolicyService();
        this.capturePolicy = new SpawnerCapturePolicyService(
                logger,
                roles,
                npcState,
                ownership,
                npcIdentity
        );
        this.bondedAdmission = new BondedCompanionCaptureAdmissionService(
                capturePolicy, commandItems);
        this.captureRolls = new SpawnerCaptureRollService(
                capturePolicies,
                captureRequirements,
                capturePolicy,
                roles,
                new SpawnerCaptureResolutionFactory(
                        registry == null
                                ? new ItemFeatureRegistry()
                                : registry,
                        System::currentTimeMillis
                ),
                (actorUuid, itemConfigId, attemptId, nowMs) ->
                        captureAuthor != null
                                && captureAuthor.failureCooldownActive(
                                        actorUuid,
                                        itemConfigId,
                                        attemptId,
                                        nowMs
                                )
        );
        this.captureIntents = new SpawnerCaptureIntentFactory(
                captureMetadata,
                progression,
                itemMetadata,
                new SpawnerItemDisplayMetadataService(translations),
                npcState,
                npcIdentity,
                tameAndLinkEvidence
        );
        SpawnerSpawnPositionService positions =
                new SpawnerSpawnPositionService(logger);
        this.releaseIntents = new SpawnerReleaseIntentFactory(
                positions, inventory, itemMetadata, ownership
        );
        this.effects = new SpawnerEffectService();
        this.channels = new SpawnerCaptureChannelService();
    }

    /** Focused package test seam for pure config resolution. */
    SpawnerFeatureHandler(
            HytaleLogger logger,
            ItemFeatureRegistry registry,
            TranslationRegistry translations
    ) {
        this(
                logger,
                registry,
                translations,
                null,
                null,
                new CapturePolicyRegistry(),
                NoCaptureRequirements.INSTANCE,
                SpawnerTameAndLinkEvidenceSource.unavailable(),
                null, null, null,
                false
        );
    }

    /** Routes a direct interaction to capture or release. */
    public boolean handle(PlayerInteractEvent event, ItemFeatureConfig config) {
        if (event == null || config == null || !config.isSpawnerEnabled()) {
            return false;
        }
        ItemStack source = event.getItemInHand();
        if (source == null || source.isEmpty()
                || (event.getActionType() != InteractionType.Primary
                && event.getActionType() != InteractionType.Use)) {
            return false;
        }
        Entity target = event.getTargetEntity();
        if (target instanceof NPCEntity npc) {
            Ref<EntityStore> targetRef = npc.getReference();
            CaptureAttemptHandle attempt = prepareCaptureAttempt(
                    event.getPlayer(), source, null
            );
            return targetRef != null && attempt != null
                    && captureFromNpcAction(
                            event.getPlayer(),
                            targetRef,
                            source,
                            config,
                            attempt
                    );
        }
        return spawnFromItem(
                event.getPlayer(), source, config, null, null
        );
    }

    /** Routes a packet interaction after re-reading the exact hotbar slot. */
    public void handlePacket(
            Player player,
            String itemId,
            int activeHotbarSlot,
            int targetEntityId,
            InteractionType interactionType,
            ItemFeatureConfig config
    ) {
        if (player == null || itemId == null || activeHotbarSlot < 0
                || (interactionType != InteractionType.Primary
                && interactionType != InteractionType.Use)) {
            return;
        }
        ItemStack source = inventory.getHotbarItem(
                player, activeHotbarSlot
        );
        ItemFeatureConfig resolved = source == null
                ? null
                : resolveConfigForItem(source);
        if (resolved == null) {
            resolved = config;
        }
        if (source == null || source.isEmpty() || resolved == null
                || !resolved.isSpawnerEnabled()) {
            return;
        }
        if (targetEntityId > 0) {
            Ref<EntityStore> target = inventory.resolveEntityRef(
                    player, targetEntityId, null
            );
            CaptureAttemptHandle attempt = prepareCaptureAttempt(
                    player, source, activeHotbarSlot
            );
            if (target != null && attempt != null) {
                captureFromNpcAction(
                        player, target, source, resolved, attempt
                );
            }
            return;
        }
        spawnFromItem(
                player, source, resolved, activeHotbarSlot, null
        );
    }

    public boolean canCaptureInteraction(
            Player player,
            Ref<EntityStore> targetRef,
            ItemStack source
    ) {
        ItemFeatureConfig config = resolveConfigForItem(source);
        return config != null && config.isSpawnerEnabled()
                && !itemMetadata.isAlreadyCaptured(source)
                && capturePolicy.canCapture(
                        player, targetRef, config, source
                );
    }

    public boolean canBeginCaptureChannelInteraction(
            Player player,
            Ref<EntityStore> targetRef,
            ItemStack source
    ) {
        ItemFeatureConfig config = resolveConfigForItem(source);
        return config != null && config.isSpawnerEnabled()
                && !itemMetadata.isAlreadyCaptured(source)
                && capturePolicy.canBeginCaptureChannel(
                        player, targetRef, config, source
                );
    }

    public boolean beginCaptureChannel(
            Player player,
            Ref<EntityStore> targetRef,
            ItemStack source,
            int sourceHotbarSlot,
            String beamParticleSystem,
            double beamNativeLength,
            double beamNativeDurationSeconds,
            boolean scaleBeamToTarget,
            boolean beamFromTarget,
            double channelDurationSeconds,
            CaptureHomingProjectileSettings homingProjectileSettings
    ) {
        ItemStack liveSource = sourceHotbarSlot < 0
                ? null : inventory.getHotbarItem(player, sourceHotbarSlot);
        if (liveSource == null || liveSource.isEmpty()
                || source == null || !Objects.equals(
                source.getItemId(), liveSource.getItemId()
        )) {
            logCaptureChannelDiagnostic(
                    "begin-denied reason=live-source-slot-mismatch"
            );
            return false;
        }
        if (!canBeginCaptureChannelInteraction(
                player, targetRef, liveSource
        )) {
            logCaptureChannelDiagnostic("begin-denied reason=eligibility");
            return false;
        }
        CaptureAttemptHandle attempt = prepareCaptureAttempt(
                player, liveSource, sourceHotbarSlot
        );
        if (attempt == null) {
            logCaptureChannelDiagnostic(
                    "begin-denied reason=source-not-in-exact-hotbar-slot"
            );
            return false;
        }
        ItemFeatureConfig config = resolveConfigForItem(liveSource);
        boolean started = channels.start(
                player,
                targetRef,
                config,
                attempt,
                beamParticleSystem,
                beamNativeLength,
                beamNativeDurationSeconds,
                scaleBeamToTarget,
                beamFromTarget,
                channelDurationSeconds,
                homingProjectileSettings
        );
        if (!started) {
            logCaptureChannelDiagnostic(
                    "begin-denied reason=channel-session-unavailable"
            );
        }
        return started;
    }

    public void endCaptureChannel(
            Player player,
            Ref<EntityStore> targetRef,
            ItemStack source
    ) {
        ItemFeatureConfig config = resolveConfigForItem(source);
        channels.end(player, targetRef, config);
    }

    public boolean completeCaptureChannel(
            Player player,
            Ref<EntityStore> targetRef,
            ItemStack source,
            @Nullable String captureBurstParticleSystem
    ) {
        CaptureAttemptHandle attempt = channels.take(player);
        endCaptureChannel(player, targetRef, source);
        return attempt != null && captureFromItemInteraction(
                player, source, targetRef,
                captureBurstParticleSystem, attempt
        );
    }

    public boolean canSpawnInteraction(ItemStack source) {
        ItemFeatureConfig config = buildSpawnerConfigForInteraction(
                resolveConfigForItem(source), null
        );
        if (source == null || source.isEmpty() || config == null
                || !config.isSpawnerEnabled()
                || !itemMetadata.isFilledItem(source, config)) {
            return false;
        }
        String roleId = roles.resolveSpawnRoleId(source);
        return roleId != null && roles.isRoleAllowed(roleId, config)
                && SpawnerCapturedArtifactIdentity.isSupported(source);
    }

    public boolean captureFromItemInteraction(
            Player player,
            ItemStack source,
            Ref<EntityStore> targetRef,
            @Nonnull CaptureAttemptHandle attempt
    ) {
        return captureFromItemInteraction(
                player, source, targetRef, null, attempt
        );
    }

    private boolean captureFromItemInteraction(
            Player player,
            ItemStack source,
            Ref<EntityStore> targetRef,
            @Nullable String captureBurstParticleSystem,
            CaptureAttemptHandle attempt
    ) {
        ItemFeatureConfig config = resolveConfigForItem(source);
        return config != null && captureFromNpcActionInternal(
                player,
                targetRef,
                source,
                config,
                attempt,
                captureBurstParticleSystem
        );
    }

    @Nullable
    public CaptureAttemptHandle prepareCaptureAttempt(
            Player player,
            ItemStack source,
            @Nullable Integer hotbarSlot
    ) {
        return prepareCaptureAttemptInternal(
                player, source, hotbarSlot, null, null
        );
    }

    @Nullable
    private CaptureAttemptHandle prepareCaptureAttemptInternal(
            Player player,
            ItemStack source,
            @Nullable Integer hotbarSlot,
            @Nullable String callerNamespace,
            @Nullable String idempotencyKey
    ) {
        Integer exactSlot = inventory.resolveExactHotbarSlot(
                player, source, hotbarSlot
        );
        if (exactSlot == null) {
            return null;
        }
        ItemStack exactSource = inventory.getHotbarItem(player, exactSlot);
        if (exactSource == null || exactSource.isEmpty()) {
            return null;
        }
        return callerNamespace == null
                ? CaptureAttemptHandle.forDispatch(
                        exactSlot, exactSource
                )
                : CaptureAttemptHandle.forCaller(
                        callerNamespace,
                        idempotencyKey,
                        exactSlot,
                        exactSource
                );
    }

    public boolean spawnFromItemInteraction(
            Player player,
            ItemStack source,
            @Nullable Integer hotbarSlot,
            String emptyItemIdOverride,
            Boolean spawnAssignsOwnerOverride
    ) {
        ItemFeatureConfig config = buildSpawnerConfigForInteraction(
                resolveConfigForItem(source),
                spawnAssignsOwnerOverride
        );
        return config != null && spawnFromItem(
                player, source, config, hotbarSlot,
                emptyItemIdOverride
        );
    }

    public boolean captureFromNpcAction(
            Player player,
            Ref<EntityStore> targetRef,
            ItemStack source,
            ItemFeatureConfig config,
            @Nonnull CaptureAttemptHandle attempt
    ) {
        return captureFromNpcActionInternal(
                player, targetRef, source, config, attempt, null
        );
    }

    private boolean captureFromNpcActionInternal(
            Player player,
            Ref<EntityStore> targetRef,
            ItemStack source,
            ItemFeatureConfig config,
            @Nonnull CaptureAttemptHandle attempt,
            @Nullable String captureParticleSystemOverride
    ) {
        ItemFeatureConfig resolved = buildSpawnerConfigForInteraction(
                config, null
        );
        String denial = captureAdmissionDenial(
                player, targetRef, source, resolved, attempt
        );
        if (denial != null) {
            logCaptureChannelDiagnostic("terminal-denied reason=" + denial
                    + " item=" + (source == null ? null : source.getItemId())
                    + " expectedFingerprint=" + attempt.sourceFingerprint()
                    + " actualFingerprint=" + currentSourceFingerprint(player, attempt));
            return false;
        }
        SpawnerCaptureRollService.Resolution roll = captureRolls.evaluate(
                player, targetRef, source, resolved, attempt
        );
        if (roll == null || roll.evaluation().outcome()
                == SpawnerCaptureChanceService.Outcome.DENIED) {
            logCaptureChannelDiagnostic("terminal-denied reason=roll-unavailable-or-denied"
                    + " item=" + source.getItemId());
            return false;
        }
        if (resolved.getCaptureMechanics().successDisposition()
                == CaptureSuccessDisposition.STORE_BONDED_COMPANION) {
            return captureBonded(
                    player, targetRef, source, resolved, attempt, roll,
                    captureParticleSystemOverride);
        }
        if (roll.evaluation().outcome()
                == SpawnerCaptureChanceService.Outcome.FAILED_ROLL
                && resolved.getCaptureMechanics().sourceConsumption()
                != CaptureSourceConsumption.RESOLVED_ATTEMPT) {
            effects.playCaptureFailureEffects(
                    player.getWorld(),
                    targetRef,
                    resolved.getCaptureMechanics()
            );
            return false;
        }
        SpawnerCaptureIntent intent = captureIntents.create(
                player,
                targetRef,
                source,
                resolved,
                attempt,
                roll,
                captureParticleSystemOverride
        );
        if (intent == null) {
            logCaptureChannelDiagnostic("terminal-denied reason=intent-unavailable"
                    + " item=" + source.getItemId()
                    + " evidence=" + captureIntents.lastEvidenceFailureReason());
            return false;
        }
        captureAuthor.capture(intent);
        return true;
    }

    @Nullable
    private String captureAdmissionDenial(
            @Nullable Player player,
            @Nullable Ref<EntityStore> targetRef,
            @Nullable ItemStack source,
            @Nullable ItemFeatureConfig resolved,
            @Nonnull CaptureAttemptHandle attempt
    ) {
        boolean bonded = resolved != null && resolved.getCaptureMechanics()
                .successDisposition()
                == CaptureSuccessDisposition.STORE_BONDED_COMPANION;
        if (bonded ? bondedCaptureAuthor == null : captureAuthor == null) {
            return "capture-author-unavailable";
        }
        if (player == null) return "player-unavailable";
        if (targetRef == null || !targetRef.isValid()) return "target-unavailable";
        if (source == null || source.isEmpty()) return "source-unavailable";
        if (resolved == null) return "item-config-unavailable";
        if (source.getQuantity() != 1
                && resolved.getCaptureMechanics().successDisposition()
                == CaptureSuccessDisposition.CAPTURED_ITEM) {
            return "stacked-captured-item-source";
        }
        if (itemMetadata.isAlreadyCaptured(source)) return "source-already-captured";
        if (!sourceMatches(player, attempt)) return "source-fingerprint-mismatch";
        if (!capturePolicy.canCapture(player, targetRef, resolved, source)) {
            return "terminal-policy-revalidation";
        }
        return null;
    }

    private boolean captureBonded(
            Player player, Ref<EntityStore> targetRef, ItemStack source,
            ItemFeatureConfig config, CaptureAttemptHandle attempt,
            SpawnerCaptureRollService.Resolution roll,
            @Nullable String particleOverride
    ) {
        if (bondedCaptureAuthor == null || bondedRosters == null) return false;
        BondedCompanionCaptureIntent intent = captureIntents.createBonded(
                player, targetRef, source, config, attempt, roll,
                bondedRosters.snapshot().revision(), bondedAdmission.hasToolAccess(
                        player, config), bondedAdmission.isTranquilized(player, targetRef),
                particleOverride);
        if (intent == null) return false;
        bondedCaptureAuthor.capture(intent);
        return true;
    }

    private boolean spawnFromItem(
            Player player,
            ItemStack source,
            ItemFeatureConfig config,
            @Nullable Integer hotbarSlot,
            @Nullable String emptyItemIdOverride
    ) {
        if (releaseAuthor == null || !canSpawnInteraction(source)) {
            return false;
        }
        SpawnerReleaseIntentFactory.PreparedRelease prepared =
                releaseIntents.prepare(
                        player,
                        source,
                        config,
                        hotbarSlot,
                        emptyItemIdOverride
                );
        if (prepared == null) {
            return false;
        }
        releaseAuthor.release(
                prepared.intent(),
                ignored -> prepared.placement()
        );
        return true;
    }

    @Nullable
    private ItemFeatureConfig resolveConfigForItem(ItemStack source) {
        if (registry == null || source == null
                || source.getItemId() == null) {
            return null;
        }
        ItemFeatureConfig direct = registry.get(source.getItemId());
        if (direct != null) {
            return direct;
        }
        String emptyItemId = itemMetadata.resolveEmptyItemId(
                source.getItemId()
        );
        return emptyItemId == null ? null : registry.get(emptyItemId);
    }

    private ItemFeatureConfig buildSpawnerConfigForInteraction(
            ItemFeatureConfig baseConfig,
            Boolean spawnAssignsOwnerOverride
    ) {
        return SpawnerInteractionConfigResolver.resolve(
                baseConfig, spawnAssignsOwnerOverride
        );
    }

    private boolean sourceMatches(
            Player player,
            CaptureAttemptHandle attempt
    ) {
        ItemStack current = inventory.getHotbarItem(
                player, attempt.hotbarSlot()
        );
        return current != null && !current.isEmpty()
                && attempt.sourceFingerprint().equals(
                        SpawnerSourceFingerprint.of(current)
                );
    }

    @Nullable
    private String currentSourceFingerprint(
            @Nullable Player player,
            @Nonnull CaptureAttemptHandle attempt
    ) {
        if (player == null) return null;
        ItemStack current = inventory.getHotbarItem(player, attempt.hotbarSlot());
        return current == null || current.isEmpty()
                ? null
                : SpawnerSourceFingerprint.of(current);
    }

    public void logCaptureChannelDiagnostic(String message) {
        Tamework plugin = Tamework.getInstance();
        log(plugin != null && plugin.isDebugSpawnerEnabled() ? Level.INFO : Level.FINE,
                "Spawner capture channel: " + message);
    }

    private void log(Level level, String message) {
        if (logger != null) {
            logger.at(level).log(message);
        }
    }

    private void logSpawnerFlowDebug(String message) {
        Tamework plugin = Tamework.getInstance();
        if (plugin != null && plugin.isDebugSpawnerEnabled()) {
            log(Level.INFO, "Spawner flow debug: " + message);
        }
    }

    @Nullable
    static UUID resolveCapturedOwnerMetadata(
            @Nullable UUID existingOwner,
            boolean captureClearsOwner
    ) {
        return captureClearsOwner ? null : existingOwner;
    }

    private enum NoCaptureRequirements
            implements CaptureRequirementRuntime {
        INSTANCE;

        @Override
        public long captureRequirementGeneration() {
            return 0L;
        }

        @Override
        public CaptureRequirementDecision evaluateCaptureRequirement(
                CaptureRequirementSpec spec,
                CaptureRequirementContext context,
                long expectedGeneration
        ) {
            return CaptureRequirementDecision.deny(
                    "capture-requirement-runtime-unavailable"
            );
        }
    }
}
