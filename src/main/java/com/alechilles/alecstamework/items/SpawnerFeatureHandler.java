package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.api.CaptureSuccessDisposition;
import com.alechilles.alecstamework.api.internal.CaptureRequirementRuntime;
import com.alechilles.alecstamework.companion.capture.CaptureAttemptResolution;
import com.alechilles.alecstamework.companion.flow.CaptureFlow;
import com.alechilles.alecstamework.companion.flow.CompanionBodies;
import com.alechilles.alecstamework.companion.flow.CompanionBodyFacts;
import com.alechilles.alecstamework.companion.flow.CompanionTransitions;
import com.alechilles.alecstamework.companion.flow.CompanionWorldTime;
import com.alechilles.alecstamework.companion.flow.HytaleCaptureDelivery;
import com.alechilles.alecstamework.companion.flow.RestoreFlow;
import com.alechilles.alecstamework.companion.flow.RestoreRules;
import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.item.CaptureItemKeys;
import com.alechilles.alecstamework.companion.live.CompanionSnapshots;
import com.alechilles.alecstamework.companion.live.CompanionSummaries;
import com.alechilles.alecstamework.companion.live.TameworkCompanionComponent;
import com.alechilles.alecstamework.companion.store.SnapshotEnvelope;
import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.items.capturepolicy.CapturePolicyRegistry;
import com.alechilles.alecstamework.items.capturepolicy.SpawnerCaptureChanceService;
import com.alechilles.alecstamework.items.persistence.SpawnerCapturedArtifactIdentity;
import com.alechilles.alecstamework.items.persistence.SpawnerPublishedEffect;
import com.alechilles.alecstamework.localization.TranslationRegistry;
import com.alechilles.alecstamework.ownership.OwnerNameUtil;
import com.alechilles.alecstamework.ui.TameworkUiMessageService;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Capture into a capture item and release from one (spec 8.2, 8.3), over the companion index.
 *
 * <p>Capture rolls on the body's world thread (the interaction or NPC action callback), takes the
 * snapshot there, and hands the commit to {@link CaptureFlow}; {@link HytaleCaptureDelivery}
 * removes the body and gives the item once the commit is written. Release reads the record for
 * ownership and runs {@link RestoreFlow} with reason RELEASE. Feedback returns to the player's
 * current world by UUID; no live component crosses a thread.
 *
 * <p>Only CAPTURED_ITEM captures run here. Bonded captures (phase 6) and tame-and-link captures
 * are refused. Items in the 2.x and 4.x formats are refused until their migration.
 */
public final class SpawnerFeatureHandler {
    private static final String SPAWNER_KEYS = "tamework.ui.notifications.spawner.";

    private final HytaleLogger logger;
    private final ItemFeatureRegistry registry;
    private final SpawnerRolePolicyService roles;
    private final SpawnerItemStackMetadataService itemMetadata;
    private final SpawnerPlayerInventoryService inventory;
    private final SpawnerCapturePolicyService capturePolicy;
    private final SpawnerCaptureResolutionFactory resolutions;
    private final SpawnerCaptureFailureCooldowns cooldowns = new SpawnerCaptureFailureCooldowns();
    private final SpawnerCaptureRollService captureRolls;
    private final SpawnerCapturedItemFactory capturedItems;
    private final SpawnerReleaseIntentFactory releaseIntents;
    private final SpawnerEffectService effects = new SpawnerEffectService();
    private final SpawnerCaptureChannelService channels = new SpawnerCaptureChannelService();
    private final TameworkUiMessageService messages = new TameworkUiMessageService();
    private final CompanionIndex index;
    private final CaptureFlow<Ref<EntityStore>> captureFlow;
    private final RestoreFlow<Ref<EntityStore>> restoreFlow;
    private final HytaleCaptureDelivery delivery;
    private final CompanionSnapshots snapshots;
    private final CompanionSummaries summaries;

    public SpawnerFeatureHandler(
            @Nonnull HytaleLogger logger,
            @Nonnull ItemFeatureRegistry registry,
            @Nullable TranslationRegistry translations,
            @Nonnull CapturePolicyRegistry capturePolicies,
            @Nonnull CaptureRequirementRuntime captureRequirements,
            @Nonnull CompanionIndex index,
            @Nonnull CaptureFlow<Ref<EntityStore>> captureFlow,
            @Nonnull RestoreFlow<Ref<EntityStore>> restoreFlow,
            @Nonnull HytaleCaptureDelivery delivery,
            @Nonnull CompanionSnapshots snapshots,
            @Nonnull CompanionSummaries summaries
    ) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.index = Objects.requireNonNull(index, "index");
        this.captureFlow = Objects.requireNonNull(captureFlow, "captureFlow");
        this.restoreFlow = Objects.requireNonNull(restoreFlow, "restoreFlow");
        this.delivery = Objects.requireNonNull(delivery, "delivery");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.summaries = Objects.requireNonNull(summaries, "summaries");
        this.roles = new SpawnerRolePolicyService(logger);
        this.inventory = new SpawnerPlayerInventoryService();
        SpawnerCaptureMetadataService captureMetadata = new SpawnerCaptureMetadataService(logger, registry);
        this.itemMetadata = new SpawnerItemStackMetadataService(
                registry, captureMetadata, new SpawnerNpcProgressionMetadataService());
        SpawnerNpcIdentityService npcIdentity = new SpawnerNpcIdentityService();
        SpawnerOwnershipPolicyService ownership = new SpawnerOwnershipPolicyService();
        this.capturePolicy = new SpawnerCapturePolicyService(
                logger, roles, new SpawnerNpcStateService(), ownership, npcIdentity);
        this.resolutions = new SpawnerCaptureResolutionFactory(registry, System::currentTimeMillis);
        this.captureRolls = new SpawnerCaptureRollService(
                Objects.requireNonNull(capturePolicies, "capturePolicies"),
                Objects.requireNonNull(captureRequirements, "captureRequirements"),
                capturePolicy, roles, resolutions, cooldowns);
        this.capturedItems = new SpawnerCapturedItemFactory(
                captureMetadata, itemMetadata, new SpawnerItemDisplayMetadataService(translations), npcIdentity);
        this.releaseIntents = new SpawnerReleaseIntentFactory(
                new SpawnerSpawnPositionService(logger), inventory, itemMetadata, ownership);
    }

    public boolean canCaptureInteraction(
            Player player,
            Ref<EntityStore> targetRef,
            ItemStack source
    ) {
        ItemFeatureConfig config = resolveConfigForItem(source);
        return config != null && config.isSpawnerEnabled()
                && !itemMetadata.isAlreadyCaptured(source)
                && capturePolicy.canCapture(player, targetRef, config, source);
    }

    public boolean canBeginCaptureChannelInteraction(
            Player player,
            Ref<EntityStore> targetRef,
            ItemStack source
    ) {
        ItemFeatureConfig config = resolveConfigForItem(source);
        return config != null && config.isSpawnerEnabled()
                && !itemMetadata.isAlreadyCaptured(source)
                && capturePolicy.canBeginCaptureChannel(player, targetRef, config, source);
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
                || source == null || !Objects.equals(source.getItemId(), liveSource.getItemId())) {
            logCaptureChannelDiagnostic("begin-denied reason=live-source-slot-mismatch");
            return false;
        }
        if (!canBeginCaptureChannelInteraction(player, targetRef, liveSource)) {
            logCaptureChannelDiagnostic("begin-denied reason=eligibility");
            return false;
        }
        CaptureAttemptHandle attempt = prepareCaptureAttempt(player, liveSource, sourceHotbarSlot);
        if (attempt == null) {
            logCaptureChannelDiagnostic("begin-denied reason=source-not-in-exact-hotbar-slot");
            return false;
        }
        boolean started = channels.start(
                player,
                targetRef,
                resolveConfigForItem(liveSource),
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
            logCaptureChannelDiagnostic("begin-denied reason=channel-session-unavailable");
        }
        return started;
    }

    public void endCaptureChannel(
            Player player,
            Ref<EntityStore> targetRef,
            ItemStack source
    ) {
        channels.end(player, targetRef, resolveConfigForItem(source));
    }

    public boolean completeCaptureChannel(
            Player player,
            Ref<EntityStore> targetRef,
            ItemStack source,
            @Nullable String captureBurstParticleSystem
    ) {
        CaptureAttemptHandle attempt = channels.take(player);
        endCaptureChannel(player, targetRef, source);
        ItemFeatureConfig config = resolveConfigForItem(source);
        return attempt != null && config != null
                && capture(player, targetRef, source, config, attempt, captureBurstParticleSystem);
    }

    /**
     * Whether the held item is a filled capture item this handler may try to release. Old-format
     * items pass so the release can tell the player why it refuses them.
     */
    public boolean canSpawnInteraction(ItemStack source) {
        ItemFeatureConfig config = buildSpawnerConfigForInteraction(resolveConfigForItem(source), null);
        if (source == null || source.isEmpty() || config == null
                || !config.isSpawnerEnabled()
                || source.getItemId() == null || !itemMetadata.isAlreadyCaptured(source)) {
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
        ItemFeatureConfig config = resolveConfigForItem(source);
        return config != null && capture(player, targetRef, source, config, attempt, null);
    }

    /** Records the exact hotbar slot and source stack at the start of an interaction. */
    @Nullable
    public CaptureAttemptHandle prepareCaptureAttempt(
            Player player,
            ItemStack source,
            @Nullable Integer hotbarSlot
    ) {
        Integer exactSlot = inventory.resolveExactHotbarSlot(player, source, hotbarSlot);
        if (exactSlot == null) {
            return null;
        }
        ItemStack exactSource = inventory.getHotbarItem(player, exactSlot);
        return exactSource == null || exactSource.isEmpty()
                ? null
                : CaptureAttemptHandle.forDispatch(exactSlot, exactSource);
    }

    public boolean spawnFromItemInteraction(
            Player player,
            ItemStack source,
            @Nullable Integer hotbarSlot,
            String emptyItemIdOverride,
            Boolean spawnAssignsOwnerOverride
    ) {
        ItemFeatureConfig config = buildSpawnerConfigForInteraction(
                resolveConfigForItem(source), spawnAssignsOwnerOverride);
        return config != null && release(player, source, config, hotbarSlot, emptyItemIdOverride);
    }

    public boolean captureFromNpcAction(
            Player player,
            Ref<EntityStore> targetRef,
            ItemStack source,
            ItemFeatureConfig config,
            @Nonnull CaptureAttemptHandle attempt
    ) {
        return capture(player, targetRef, source, config, attempt, null);
    }

    /**
     * Rolls and, on success, starts the commit. Returns true when the attempt resolved (a capture
     * started, or a failed roll spent its source); false when nothing changed.
     */
    private boolean capture(
            Player player,
            Ref<EntityStore> targetRef,
            ItemStack source,
            ItemFeatureConfig config,
            @Nonnull CaptureAttemptHandle attempt,
            @Nullable String captureParticleSystemOverride
    ) {
        ItemFeatureConfig resolved = buildSpawnerConfigForInteraction(config, null);
        String denial = captureAdmissionDenial(player, targetRef, source, resolved, attempt);
        if (denial != null) {
            logCaptureChannelDiagnostic("terminal-denied reason=" + denial
                    + " item=" + (source == null ? null : source.getItemId()));
            return false;
        }
        SpawnerCaptureRollService.Resolution roll = captureRolls.evaluate(
                player, targetRef, source, resolved, attempt);
        if (roll == null || roll.evaluation().outcome() == SpawnerCaptureChanceService.Outcome.DENIED) {
            logCaptureChannelDiagnostic("terminal-denied reason=roll-unavailable-or-denied"
                    + " item=" + source.getItemId());
            return false;
        }
        if (roll.evaluation().outcome() == SpawnerCaptureChanceService.Outcome.FAILED_ROLL) {
            return failedRoll(player, targetRef, source, resolved, attempt, roll.terminal());
        }
        return captureIntoItem(player, targetRef, source, resolved, attempt, roll.roleId(),
                captureParticleSystemOverride);
    }

    @Nullable
    private String captureAdmissionDenial(
            @Nullable Player player,
            @Nullable Ref<EntityStore> targetRef,
            @Nullable ItemStack source,
            @Nullable ItemFeatureConfig resolved,
            @Nonnull CaptureAttemptHandle attempt
    ) {
        if (player == null) return "player-unavailable";
        if (targetRef == null || !targetRef.isValid()) return "target-unavailable";
        if (source == null || source.isEmpty()) return "source-unavailable";
        if (resolved == null) return "item-config-unavailable";
        CaptureSuccessDisposition disposition = resolved.getCaptureMechanics().successDisposition();
        if (disposition != CaptureSuccessDisposition.CAPTURED_ITEM) {
            // Bonded captures arrive with phase 6; tame-and-link captures are not wired yet.
            return "disposition-unavailable-" + disposition;
        }
        if (source.getQuantity() != 1) return "stacked-captured-item-source";
        if (itemMetadata.isAlreadyCaptured(source)) return "source-already-captured";
        if (!sourceMatches(player, attempt)) return "source-fingerprint-mismatch";
        if (!capturePolicy.canCapture(player, targetRef, resolved, source)) {
            return "terminal-policy-revalidation";
        }
        return null;
    }

    /**
     * A failed roll plays the failure effects. Under {@code RESOLVED_ATTEMPT} consumption it also
     * spends one source item at the recorded slot (best effort) and starts the failure cooldown.
     */
    private boolean failedRoll(Player player, Ref<EntityStore> targetRef, ItemStack source,
                               ItemFeatureConfig resolved, CaptureAttemptHandle attempt,
                               @Nullable CaptureAttemptResolution terminal) {
        effects.playCaptureFailureEffects(player.getWorld(), targetRef, resolved.getCaptureMechanics());
        if (terminal == null) {
            return false;
        }
        ItemStack current = inventory.getHotbarItem(player, attempt.hotbarSlot());
        if (current != null && !current.isEmpty()) {
            SpawnerSourceItemTransaction spend = new SpawnerSourceItemTransaction(
                    inventory, player, attempt.hotbarSlot(), current, logger, "capture-failed-roll");
            if (spend.consumeOne()) {
                spend.commit();
            }
        }
        Long cooldownUntil = terminal.failureCooldownUntilMs();
        if (cooldownUntil != null) {
            cooldowns.record(player.getUuid(), resolutions.itemConfigId(source.getItemId()),
                    cooldownUntil, resolutions.nowMs());
        }
        return true;
    }

    /** Runs on the body's world thread: reads the body, snapshots it and starts the commit. */
    private boolean captureIntoItem(Player player, Ref<EntityStore> targetRef, ItemStack source,
                                    ItemFeatureConfig resolved, CaptureAttemptHandle attempt, String roleId,
                                    @Nullable String particleSystemOverride) {
        World world = player.getWorld();
        Store<EntityStore> store = world == null || world.getEntityStore() == null
                ? null : world.getEntityStore().getStore();
        if (store == null || targetRef.getStore() != store) {
            logCaptureChannelDiagnostic("terminal-denied reason=target-in-other-world");
            return false;
        }
        TameworkCompanionComponent stamp = store.getComponent(targetRef, TameworkCompanionComponent.getComponentType());
        UUID stampedId = stamp == null ? null : stamp.getProfileId();
        long stampedGeneration = stampedId == null ? 0L : stamp.getGeneration();
        CompanionTransitions.BodyFacts facts = CompanionBodyFacts.read(targetRef, store, summaries);
        SnapshotEnvelope snapshot = facts == null ? null : snapshots.capture(targetRef, store,
                stampedId != null ? stampedId : facts.npcUuid(), stampedGeneration, world.getName(),
                CompanionWorldTime.gameTimeMs(store));
        if (snapshot == null) {
            warn(player, "captureEvidenceFailed");
            return false;
        }
        UUID owner = captureOwner(facts.ownerUuid(), resolved.isCaptureClearsOwner(),
                resolved.isCaptureTamesTarget(), player.getUuid());
        String ownerName = owner == null ? null
                : owner.equals(facts.ownerUuid()) ? facts.ownerName() : OwnerNameUtil.resolve(player);
        ItemStack item = capturedItems.build(player, targetRef, store, source, resolved, roleId, owner);
        ItemStack expectedSource = inventory.getHotbarItem(player, attempt.hotbarSlot());
        String particles = particleSystemOverride == null || particleSystemOverride.isBlank()
                ? resolved.getCaptureParticleSystem() : particleSystemOverride;
        SpawnerPublishedEffect effect = new SpawnerPublishedEffect(
                facts.x(), facts.y(), facts.z(), particles, resolved.getCaptureSoundEvent());
        UUID playerUuid = player.getUuid();
        int slot = attempt.hotbarSlot();
        try {
            captureFlow.capture(new CaptureFlow.Capture<>(stampedId, stampedGeneration, targetRef, facts, owner,
                            ownerName, snapshot.data()))
                    .whenComplete((outcome, error) -> {
                        if (error != null) {
                            logger.at(Level.WARNING).withCause(error).log("Capture commit failed unexpectedly");
                        }
                        finishCapture(outcome, targetRef, stampedId, stampedGeneration,
                                item, expectedSource, playerUuid, slot, effect);
                    });
        } catch (RuntimeException failure) {
            logger.at(Level.WARNING).withCause(failure).log("Capture commit could not start");
            warn(player, "captureUnavailable");
            return false;
        }
        return true;
    }

    /** Runs on whichever thread completed the commit; hands live work to world threads. */
    private void finishCapture(@Nullable CaptureFlow.Outcome outcome, Ref<EntityStore> body,
                               @Nullable UUID stampedId, long stampedGeneration, ItemStack item,
                               @Nullable ItemStack expectedSource, UUID playerUuid, int slot,
                               SpawnerPublishedEffect effect) {
        CaptureFlow.Result result = outcome == null ? CaptureFlow.Result.COMMIT_FAILED : outcome.result();
        switch (result) {
            case CAPTURED -> delivery.deliver(new HytaleCaptureDelivery.Handover(body, outcome.itemRef(), item,
                    playerUuid, slot, expectedSource == null ? ItemStack.EMPTY : expectedSource,
                    world -> effects.playPublishedEffect(world, effect)));
            case CONFLICT -> {
                // A newer change replaced the commit after the body was unregistered: its stamp is stale.
                CompanionRecord now = stampedId == null ? null : index.get(stampedId);
                if (stampedId != null && (now == null || now.generation() > stampedGeneration)) {
                    CompanionBodies.removeOnOwnWorld(body);
                }
                warnLater(playerUuid, "captureProfileConflict");
            }
            case NOT_CAPTURABLE -> warnLater(playerUuid, "captureProfileConflict");
            case COMMIT_FAILED -> warnLater(playerUuid, "captureUnavailable");
        }
    }

    /**
     * Releases a 5.0 capture item through {@link RestoreFlow}. Ownership checks read the record,
     * not the item. Returns true when the restore started.
     */
    private boolean release(
            Player player,
            ItemStack source,
            ItemFeatureConfig config,
            @Nullable Integer hotbarSlot,
            @Nullable String emptyItemIdOverride
    ) {
        if (!canSpawnInteraction(source)) {
            return false;
        }
        CaptureItemKeys.Ref ref = CaptureItemKeys.readIndexItem(source);
        if (ref == null) {
            // 2.x and 4.x items are migrated in phase 7; until then they cannot be released.
            warn(player, "releaseInvalidContext");
            return false;
        }
        CompanionRecord record = index.get(ref.profileId());
        UUID recordOwner = record == null ? null : record.ownerUuid();
        SpawnerReleaseIntentFactory.PreparedRelease prepared =
                releaseIntents.prepare(player, source, config, hotbarSlot, emptyItemIdOverride, recordOwner);
        if (prepared == null) {
            return false;
        }
        RestoreFlow.Request request = RestoreFlow.Request.of(ref.profileId(), RestoreRules.Reason.RELEASE,
                RestoreFlow.Destination.of(prepared.placement())).withGeneration(ref.generation());
        RestoreFlow.Owner owner = releaseOwner(recordOwner, config.isSpawnAssignsOwner(), player.getUuid(),
                OwnerNameUtil.resolve(player));
        if (owner != null) {
            request = request.withOwner(owner);
        }
        UUID playerUuid = player.getUuid();
        restoreFlow.restore(request).whenComplete((result, error) -> {
            if (error != null) {
                logger.at(Level.WARNING).withCause(error).log("Release of companion %s failed unexpectedly",
                        ref.profileId());
            }
            RestoreFlow.Result outcome = error != null || result == null ? RestoreFlow.Result.COMMIT_FAILED : result;
            HytaleCaptureDelivery.onPlayerWorld(playerUuid,
                    (world, store, actorRef, actor) -> finishRelease(outcome, world, actor, ref, prepared),
                    null);
        });
        return true;
    }

    /** Runs on the player's current world thread. */
    private void finishRelease(RestoreFlow.Result result, World world, Player player, CaptureItemKeys.Ref ref,
                               SpawnerReleaseIntentFactory.PreparedRelease prepared) {
        switch (result) {
            case RESTORED -> {
                emptyHeldCapture(player, ref, prepared);
                if (world.getName().equals(prepared.placement().worldKey())) {
                    effects.playPublishedEffect(world, prepared.effect());
                }
            }
            case STALE, NOT_ALLOWED -> {
                // The item no longer matches its record; it becomes an empty capture item and
                // never changes who owns the companion.
                emptyHeldCapture(player, ref, prepared);
                warn(player, "releaseProfileConflict");
            }
            case NOT_FOUND -> warn(player, "releaseProfileConflict");
            case NO_SNAPSHOT -> warn(player, "releaseEvidenceFailed");
            case OWNED_LIMIT -> messages.showKey(player, NotificationStyle.Warning, "tamework.ui.population.ownedLimit");
            case GROUP_LIMIT -> messages.showKey(player, NotificationStyle.Warning, "tamework.ui.population.groupLimit");
            default -> warn(player, "releaseFailed");
        }
    }

    /** Compare-then-replace: only a slot still holding this profile at this generation is emptied. */
    private void emptyHeldCapture(Player player, CaptureItemKeys.Ref ref,
                                  SpawnerReleaseIntentFactory.PreparedRelease prepared) {
        ItemStack current = inventory.getHotbarItem(player, prepared.slot());
        if (ref.equals(CaptureItemKeys.readIndexItem(current))) {
            inventory.updateHotbarSlot(player, prepared.slot(), prepared.receipt());
        }
    }

    /**
     * The record owner after a capture: none when {@code ClearsOwner}, otherwise the body's owner;
     * an unowned body captured by an item that tames it gets the capturing player.
     */
    @Nullable
    static UUID captureOwner(@Nullable UUID bodyOwner, boolean clearsOwner, boolean tamesTarget,
                             @Nonnull UUID capturingPlayer) {
        if (clearsOwner) {
            return null;
        }
        if (bodyOwner == null && tamesTarget) {
            return capturingPlayer;
        }
        return bodyOwner;
    }

    /**
     * The owner a release asks for. An owned record keeps its owner (null: no change). An unowned
     * one goes to the releaser when the item assigns owners, otherwise it comes back unowned.
     */
    @Nullable
    static RestoreFlow.Owner releaseOwner(@Nullable UUID recordOwner, boolean assignsOwner,
                                          @Nonnull UUID releaser, @Nullable String releaserName) {
        if (recordOwner != null) {
            return null;
        }
        return assignsOwner ? new RestoreFlow.Owner(releaser, releaserName) : new RestoreFlow.Owner(null, null);
    }

    @Nullable
    private ItemFeatureConfig resolveConfigForItem(ItemStack source) {
        if (source == null || source.getItemId() == null) {
            return null;
        }
        ItemFeatureConfig direct = registry.get(source.getItemId());
        if (direct != null) {
            return direct;
        }
        String emptyItemId = itemMetadata.resolveEmptyItemId(source.getItemId());
        return emptyItemId == null ? null : registry.get(emptyItemId);
    }

    @Nullable
    private static ItemFeatureConfig buildSpawnerConfigForInteraction(
            @Nullable ItemFeatureConfig baseConfig,
            @Nullable Boolean spawnAssignsOwnerOverride
    ) {
        return SpawnerInteractionConfigResolver.resolve(baseConfig, spawnAssignsOwnerOverride);
    }

    private boolean sourceMatches(Player player, CaptureAttemptHandle attempt) {
        ItemStack current = inventory.getHotbarItem(player, attempt.hotbarSlot());
        return current != null && !current.isEmpty()
                && attempt.sourceFingerprint().equals(SpawnerSourceFingerprint.of(current));
    }

    /** Shows a spawner warning now. Call on the player's world thread. */
    private void warn(Player player, String key) {
        messages.showKey(player, NotificationStyle.Warning, SPAWNER_KEYS + key);
    }

    /** Shows a spawner warning from any thread, on the player's current world. */
    private void warnLater(UUID playerUuid, String key) {
        HytaleCaptureDelivery.onPlayerWorld(playerUuid, (world, store, ref, player) -> warn(player, key), null);
    }

    public void logCaptureChannelDiagnostic(String message) {
        Tamework plugin = Tamework.getInstance();
        logger.at(plugin != null && plugin.isDebugSpawnerEnabled() ? Level.INFO : Level.FINE)
                .log("Spawner capture channel: " + message);
    }
}
