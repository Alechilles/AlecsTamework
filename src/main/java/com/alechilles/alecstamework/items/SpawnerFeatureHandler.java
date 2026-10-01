package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.api.CaptureAttemptOutcome;
import com.alechilles.alecstamework.api.CaptureAttemptResolvedEvent;
import com.alechilles.alecstamework.api.CaptureSuccessDisposition;
import com.alechilles.alecstamework.api.internal.CaptureRequirementRuntime;
import com.alechilles.alecstamework.companion.admission.CompanionAdmission;
import com.alechilles.alecstamework.companion.admission.CompanionAdmissionGate;
import com.alechilles.alecstamework.companion.capture.CaptureAttemptFormula;
import com.alechilles.alecstamework.companion.capture.CaptureAttemptResolution;
import com.alechilles.alecstamework.companion.flow.CaptureFlow;
import com.alechilles.alecstamework.companion.flow.CompanionBodies;
import com.alechilles.alecstamework.companion.flow.CompanionBodyFacts;
import com.alechilles.alecstamework.companion.flow.CompanionRegistration;
import com.alechilles.alecstamework.companion.flow.CompanionTransitions;
import com.alechilles.alecstamework.companion.flow.CompanionWorldTime;
import com.alechilles.alecstamework.companion.flow.HytaleCaptureDelivery;
import com.alechilles.alecstamework.companion.flow.RestoreFlow;
import com.alechilles.alecstamework.companion.flow.RestoreRules;
import com.alechilles.alecstamework.companion.flow.SnapshotPatch;
import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.item.CaptureItemKeys;
import com.alechilles.alecstamework.companion.item.CaptureItemOwnership;
import com.alechilles.alecstamework.companion.live.CompanionSaves;
import com.alechilles.alecstamework.companion.live.CompanionSnapshots;
import com.alechilles.alecstamework.companion.live.CompanionSummaries;
import com.alechilles.alecstamework.companion.live.LoadedBodies;
import com.alechilles.alecstamework.companion.live.TameworkCompanionComponent;
import com.alechilles.alecstamework.companion.store.SnapshotEnvelope;
import com.alechilles.alecstamework.config.CommandItemRegistry;
import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.items.capturepolicy.CapturePolicyRegistry;
import com.alechilles.alecstamework.items.locate.CaptureItemHolderSystems;
import com.alechilles.alecstamework.items.locate.CapturedItemLocationIndex;
import com.alechilles.alecstamework.items.locate.CapturedItemMetadata;
import com.alechilles.alecstamework.items.locate.CapturedItemTracker;
import com.alechilles.alecstamework.items.capturepolicy.SpawnerCaptureChanceService;
import com.alechilles.alecstamework.items.persistence.SpawnerCapturedArtifactIdentity;
import com.alechilles.alecstamework.items.persistence.SpawnerPublishedEffect;
import com.alechilles.alecstamework.effects.TameworkEntityEffectService;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.alechilles.alecstamework.localization.TranslationRegistry;
import com.alechilles.alecstamework.npc.TamedStateResolver;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.npc.progression.CompanionProgressionBootstrapService;
import com.alechilles.alecstamework.npc.spawning.CompanionSpawnAuthorityService;
import com.alechilles.alecstamework.ownership.OwnerNameUtil;
import com.alechilles.alecstamework.settings.CaptureItemOwnershipMode;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import com.alechilles.alecstamework.ui.TameworkUiMessageService;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.systems.RoleChangeSystem;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.bson.BsonDocument;

/**
 * Capture into a capture item and release from one (spec 8.2, 8.3), over the companion index.
 *
 * <p>Capture rolls on the body's world thread (the interaction or NPC action callback), takes the
 * snapshot there, and hands the commit to {@link CaptureFlow}; {@link HytaleCaptureDelivery}
 * removes the body and gives the item once the commit is written. Release reads the record for
 * ownership and runs {@link RestoreFlow} with reason RELEASE. Feedback returns to the player's
 * current world by UUID; no live component crosses a thread.
 *
 * <p>A TAME_AND_COMMAND_LINK capture tames the wild body in place for the capturing player and
 * registers it as a member of the item's command-family roster (record {@code rosterId}). A
 * capture that succeeds, and a failed roll that spent its source, publish
 * {@link CaptureAttemptResolvedEvent} on the body's world thread. Bonded
 * captures (phase 6) are refused. Items in the 2.x and 4.x formats are refused until their
 * migration.
 */
public final class SpawnerFeatureHandler {
    private static final String SPAWNER_KEYS = "tamework.ui.notifications.spawner.";
    private static final String TRANQUILIZER_EFFECT_ID = "Tw_Status_Tranquilized";

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
    private final SpawnerItemDisplayMetadataService displayMetadata;
    private final SpawnerReleaseIntentFactory releaseIntents;
    private final SpawnerEffectService effects = new SpawnerEffectService();
    private final SpawnerCaptureChannelService channels = new SpawnerCaptureChannelService();
    private final TameworkUiMessageService messages = new TameworkUiMessageService();
    private final CompanionIndex index;
    private final LoadedBodies<Ref<EntityStore>> loaded;
    private final CaptureFlow<Ref<EntityStore>> captureFlow;
    private final RestoreFlow<Ref<EntityStore>> restoreFlow;
    private final HytaleCaptureDelivery delivery;
    private final CompanionSnapshots snapshots;
    private final CompanionSummaries summaries;
    private final CompanionAdmissionGate admissionGate;
    private final CommandItemRegistry commandItems;
    private final Consumer<CaptureAttemptResolvedEvent> captureResolved;
    /** Profiles (or unstamped NPC UUIDs) with a capture commit in flight. */
    private final Set<UUID> capturing = ConcurrentHashMap.newKeySet();
    /** Profiles with a release in flight. */
    private final Set<UUID> releasing = ConcurrentHashMap.newKeySet();

    public SpawnerFeatureHandler(
            @Nonnull HytaleLogger logger,
            @Nonnull ItemFeatureRegistry registry,
            @Nullable TranslationRegistry translations,
            @Nonnull CapturePolicyRegistry capturePolicies,
            @Nonnull CaptureRequirementRuntime captureRequirements,
            @Nonnull CompanionIndex index,
            @Nonnull LoadedBodies<Ref<EntityStore>> loaded,
            @Nonnull CaptureFlow<Ref<EntityStore>> captureFlow,
            @Nonnull RestoreFlow<Ref<EntityStore>> restoreFlow,
            @Nonnull HytaleCaptureDelivery delivery,
            @Nonnull CompanionSnapshots snapshots,
            @Nonnull CompanionSummaries summaries,
            @Nonnull CompanionAdmissionGate admissionGate,
            @Nonnull CommandItemRegistry commandItems,
            @Nonnull Consumer<CaptureAttemptResolvedEvent> captureResolved
    ) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.index = Objects.requireNonNull(index, "index");
        this.loaded = Objects.requireNonNull(loaded, "loaded");
        this.captureFlow = Objects.requireNonNull(captureFlow, "captureFlow");
        this.restoreFlow = Objects.requireNonNull(restoreFlow, "restoreFlow");
        this.delivery = Objects.requireNonNull(delivery, "delivery");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.summaries = Objects.requireNonNull(summaries, "summaries");
        this.admissionGate = Objects.requireNonNull(admissionGate, "admissionGate");
        this.commandItems = Objects.requireNonNull(commandItems, "commandItems");
        this.captureResolved = Objects.requireNonNull(captureResolved, "captureResolved");
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
        this.displayMetadata = new SpawnerItemDisplayMetadataService(translations);
        this.capturedItems = new SpawnerCapturedItemFactory(
                captureMetadata, itemMetadata, displayMetadata, npcIdentity);
        this.releaseIntents = new SpawnerReleaseIntentFactory(
                new SpawnerSpawnPositionService(logger), inventory, itemMetadata);
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
        ItemFeatureConfig config = resolveConfigForItem(source);
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
            String emptyItemIdOverride
    ) {
        ItemFeatureConfig config = resolveConfigForItem(source);
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
        String denial = captureAdmissionDenial(player, targetRef, source, config, attempt);
        if (denial != null) {
            logCaptureChannelDiagnostic("terminal-denied reason=" + denial
                    + " item=" + (source == null ? null : source.getItemId()));
            return false;
        }
        PreRoll prepared = preRoll(player, targetRef, source, config);
        if (prepared == null) {
            return false;
        }
        SpawnerCaptureRollService.Resolution roll = captureRolls.evaluate(
                player, targetRef, source, config, attempt);
        if (roll == null || roll.evaluation().outcome() == SpawnerCaptureChanceService.Outcome.DENIED) {
            logCaptureChannelDiagnostic("terminal-denied reason=roll-unavailable-or-denied"
                    + " item=" + source.getItemId());
            return false;
        }
        if (roll.evaluation().outcome() == SpawnerCaptureChanceService.Outcome.FAILED_ROLL) {
            return failedRoll(player, targetRef, source, config, attempt, roll);
        }
        UUID actor = player.getUuid();
        String sourceItemId = source.getItemId();
        Consumer<UUID> resolved = profileId -> publishResolved(actor, sourceItemId, roll, profileId);
        if (config.getCaptureMechanics().successDisposition() == CaptureSuccessDisposition.TAME_AND_COMMAND_LINK) {
            return tameAndLink(player, targetRef, config, attempt, prepared.targetRole(),
                    captureParticleSystemOverride, resolved);
        }
        return captureIntoItem(player, targetRef, source, config, attempt, roll.roleId(),
                captureParticleSystemOverride, resolved);
    }

    /**
     * Publishes the resolved attempt. The index has no operation ids, so the attempt id stands in
     * for one, and there is no replay evidence. {@code profileId} is null for a failed roll on a
     * body with no companion record. Never throws: a capture must not fail on its notification.
     */
    private void publishResolved(UUID actor, String sourceItemId, SpawnerCaptureRollService.Resolution roll,
                                 @Nullable UUID profileId) {
        CaptureAttemptResolution terminal = roll.terminal();
        if (terminal == null) {
            return;
        }
        try {
            CaptureAttemptFormula formula = terminal.formula();
            long now = System.currentTimeMillis();
            captureResolved.accept(new CaptureAttemptResolvedEvent(
                    terminal.attemptId(),
                    terminal.attemptId(),
                    actor,
                    roll.targetUuid(),
                    profileId == null ? null : profileId.toString(),
                    terminal.targetRoleId(),
                    sourceItemId,
                    formula.itemConfigId(),
                    formula.itemConfigRevision(),
                    formula.policyConfigId(),
                    formula.policyConfigId() == null ? -1L : formula.policyConfigRevision(),
                    formula.itemPower(),
                    formula.minimumPower(),
                    terminal.currentHealth(),
                    terminal.maximumHealth(),
                    terminal.missingHealthFraction(),
                    formula.missingHealthBonus(),
                    terminal.effectiveChance(),
                    terminal.guaranteed(),
                    terminal.successful() ? CaptureAttemptOutcome.CAPTURED : CaptureAttemptOutcome.FAILED_ROLL,
                    terminal.reason(),
                    now,
                    now,
                    null));
        } catch (RuntimeException | LinkageError failure) {
            logger.at(Level.WARNING).withCause(failure).log(
                    "The resolved capture attempt %s could not be published", terminal.attemptId());
        }
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
        if (disposition == CaptureSuccessDisposition.TAME_AND_COMMAND_LINK) {
            // The roster member is the live body: only a wild target that this item tames qualifies.
            if (!resolved.isCaptureTamesTarget()) return "tame-and-link-without-tames-target";
        } else if (disposition != CaptureSuccessDisposition.CAPTURED_ITEM) {
            // Bonded captures arrive with phase 6.
            return "disposition-unavailable-" + disposition;
        } else if (source.getQuantity() != 1) {
            return "stacked-captured-item-source";
        }
        if (itemMetadata.isAlreadyCaptured(source)) return "source-already-captured";
        if (!sourceMatches(player, attempt)) return "source-fingerprint-mismatch";
        if (!capturePolicy.canCapture(player, targetRef, resolved, source)) {
            return "terminal-policy-revalidation";
        }
        return null;
    }

    /** What {@link #preRoll} resolved: the tamed role of a tame-and-link capture, else null. */
    private record PreRoll(@Nullable String targetRole) {
    }

    /**
     * Checks that must pass before the roll, since a failed roll may spend the source: a capture
     * into an item refuses a command-family roster member and, while capture items are bound to
     * their owner, another player's companion; it checks the caps for a capture that
     * gives the companion a new owner or whose item would move it to the capturer; a tame-and-link
     * capture checks the target body, the tamed role, the command item gates and the caps. Returns
     * null when refused; the player is told why. World thread.
     */
    @Nullable
    private PreRoll preRoll(Player player, Ref<EntityStore> targetRef, ItemStack source,
                            ItemFeatureConfig resolved) {
        World world = player.getWorld();
        Store<EntityStore> store = world == null || world.getEntityStore() == null
                ? null : world.getEntityStore().getStore();
        if (store == null || targetRef.getStore() != store) {
            logCaptureChannelDiagnostic("terminal-denied reason=target-in-other-world");
            return null;
        }
        CompanionTransitions.BodyFacts facts = CompanionBodyFacts.read(targetRef, store, summaries);
        if (facts == null) {
            warn(player, "captureEvidenceFailed");
            return null;
        }
        String sourceRole = roles.resolveRoleIdFromNpc(store.getComponent(targetRef, NPCEntity.getComponentType()));
        ItemFeatureConfig.CaptureItemMechanics mechanics = resolved.getCaptureMechanics();
        if (mechanics.successDisposition() != CaptureSuccessDisposition.TAME_AND_COMMAND_LINK) {
            if (isRosterMember(store.getComponent(targetRef, TameworkCompanionComponent.getComponentType()))) {
                // Only its command-family item may act on a roster member; nothing is spent.
                warn(player, "captureFailed");
                return null;
            }
            UUID owner = CaptureItemOwnership.captureOwner(facts.ownerUuid(),
                    TamedStateResolver.isTamed(targetRef, store), resolved.isCaptureTamesTarget(), player.getUuid());
            if (CaptureItemOwnership.captureRefused(TameworkRuntimeSettings.current().captureItemOwnership(),
                    owner, player.getUuid())) {
                // The capturer could not hold the filled item, so it would drop at the body.
                String ownerName = facts.ownerName();
                messages.showKey(player, NotificationStyle.Warning,
                        "tamework.ui.notifications.captureItem.ownerOnlyCapture",
                        ownerName != null && !ownerName.isBlank() ? ownerName : LocalizedText.resolve(player,
                                "tamework.ui.notifications.captureItem.anotherPlayer"));
                return null;
            }
            String role = sourceRole == null ? facts.roleId() : sourceRole;
            if (owner != null && facts.ownerUuid() == null
                    && refusedByCaps(player, owner, role, world.getName(), false)) {
                return null;
            }
            // The filled item lands with the capturer: when it would move the companion to them and
            // an ineligible holder cannot pick it up, refuse now so it cannot be stranded on the ground.
            if (owner != null && !owner.equals(player.getUuid()) && blocksIneligibleHolders(resolved)
                    && refusedByCaps(player, player.getUuid(), role, world.getName(), false)) {
                return null;
            }
            return new PreRoll(null);
        }
        if (facts.ownerUuid() != null
                || store.getComponent(targetRef, TameworkCompanionComponent.getComponentType()) != null) {
            warn(player, "captureProfileConflict");
            return null;
        }
        String tamedRole = sourceRole == null ? null : resolved.resolveCaptureTamedRole(sourceRole);
        String targetRole = tamedRole == null ? sourceRole : tamedRole;
        NPCPlugin plugin = NPCPlugin.get();
        if (targetRole == null || plugin == null || plugin.getIndex(targetRole) < 0) {
            refuseMisconfigured(player, source, "tamed-role-missing", mechanics, targetRole);
            return null;
        }
        // One read of the command config: a reload in between must not change what the gates checked.
        TwCommandItemConfig command = mechanics.requiredCommandConfigId() == null ? null
                : commandItems.getByConfigId(mechanics.requiredCommandConfigId());
        String denial = commandAccessDenial(mechanics, command, targetRole);
        if (denial != null) {
            refuseMisconfigured(player, source, denial, mechanics, targetRole);
            return null;
        }
        if (!holdsCommandItem(player, command, source)) {
            messages.showKey(player, NotificationStyle.Warning, SPAWNER_KEYS + "commandItemRequired",
                    new CommandItemDisplayResolver().resolveItemDisplayName(player, firstItemId(command)));
            return null;
        }
        if (refusedByCaps(player, player.getUuid(), targetRole, world.getName(), true)) {
            return null;
        }
        return new PreRoll(targetRole);
    }

    /** A non-bonded member of a command-family roster, which a generic capture item may not take. */
    private boolean isRosterMember(@Nullable TameworkCompanionComponent stamp) {
        UUID id = stamp == null ? null : stamp.getProfileId();
        CompanionRecord record = id == null ? null : index.get(id);
        return record != null && record.rosterId() != null && !record.bonded();
    }

    /** {@code BlockIneligibleHolders} while the owner follows the item. */
    private static boolean blocksIneligibleHolders(ItemFeatureConfig resolved) {
        return TameworkRuntimeSettings.current().captureItemOwnership() == CaptureItemOwnershipMode.FOLLOWS_ITEM
                && resolved.isCaptureBlockIneligibleHolders();
    }

    private boolean refusedByCaps(Player player, UUID owner, String roleId, String world, boolean deployed) {
        CompanionAdmission.Refusal refusal = admissionGate.precheck(owner, roleId, world, deployed);
        if (refusal != null) {
            showPopulationLimit(player, refusal == CompanionAdmission.Refusal.OWNED);
            return true;
        }
        return false;
    }

    /**
     * The tame-and-link command gates of the old spawner flow: the item requires a command access
     * item and names its command config; that config ({@code command}, read once by the caller)
     * exists, is enabled with linking on, uses the owner command-family roster, requires an owner,
     * belongs to the item's family and its {@code AllowedRoles} admit the tamed role. Returns the
     * failed gate, or null.
     */
    @Nullable
    private static String commandAccessDenial(ItemFeatureConfig.CaptureItemMechanics mechanics,
                                              @Nullable TwCommandItemConfig command, String targetRole) {
        if (!mechanics.requireCommandAccessItem() || mechanics.requiredCommandConfigId() == null) {
            return "command-access-item-not-required";
        }
        if (command == null || !command.isEnabled()) {
            return "command-config-unavailable";
        }
        if (!command.usesOwnerCommandFamilyRoster()) {
            return "command-config-not-owner-family";
        }
        if (!Objects.equals(command.getCommandFamilyId(), mechanics.commandFamilyId())) {
            return "command-family-mismatch";
        }
        if (!command.isLinkEnabled()) {
            return "command-config-link-disabled";
        }
        if (!command.isRequireOwner()) {
            return "command-config-owner-not-required";
        }
        return new CommandLinkPolicyService().isRoleAllowed(targetRole, command) ? null : "tamed-role-not-allowed";
    }

    /**
     * Whether the player carries an item of {@code command} besides the capture source itself (an
     * access item that is also the source is spent by the capture).
     */
    private boolean holdsCommandItem(Player player, @Nullable TwCommandItemConfig command, ItemStack source) {
        ItemContainer items = command == null || player.getInventory() == null ? null
                : player.getInventory().getCombinedBackpackStorageHotbarFirst();
        if (items == null) {
            return false;
        }
        long quantity = commandItems.get(source.getItemId()) == command ? -1L : 0L;
        for (short slot = 0; slot < items.getCapacity(); slot++) {
            ItemStack stack = items.getItemStack(slot);
            if (!ItemStack.isEmpty(stack) && commandItems.get(stack.getItemId()) == command) {
                quantity += stack.getQuantity();
            }
        }
        return quantity > 0L;
    }

    @Nullable
    private static String firstItemId(@Nullable TwCommandItemConfig command) {
        if (command != null) {
            for (String itemId : command.getItemIds()) {
                if (itemId != null && !itemId.isBlank()) {
                    return itemId;
                }
            }
        }
        return null;
    }

    /** A tame-and-link item whose setup cannot work: the server log names the cause, the player sees a failure. */
    private void refuseMisconfigured(Player player, ItemStack source, String reason,
                                     ItemFeatureConfig.CaptureItemMechanics mechanics, @Nullable String targetRole) {
        logger.at(Level.WARNING).log("Tame-and-link capture with %s refused: %s (command config %s, family %s, role %s)",
                source.getItemId(), reason, mechanics.requiredCommandConfigId(), mechanics.commandFamilyId(),
                targetRole);
        warn(player, "captureFailed");
    }

    /**
     * A failed roll plays the failure effects. Under {@code RESOLVED_ATTEMPT} consumption it also
     * spends one source item at the recorded slot (best effort), starts the failure cooldown and
     * publishes the resolved attempt.
     */
    private boolean failedRoll(Player player, Ref<EntityStore> targetRef, ItemStack source,
                               ItemFeatureConfig resolved, CaptureAttemptHandle attempt,
                               SpawnerCaptureRollService.Resolution roll) {
        CaptureAttemptResolution terminal = roll.terminal();
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
        TameworkCompanionComponent stamp = targetRef.isValid()
                ? targetRef.getStore().getComponent(targetRef, TameworkCompanionComponent.getComponentType()) : null;
        publishResolved(player.getUuid(), source.getItemId(), roll, stamp == null ? null : stamp.getProfileId());
        return true;
    }

    /**
     * TAME_AND_COMMAND_LINK on the body's world thread: tames the wild body in place for the
     * capturing player, links it to the item's command family and registers it as a roster member.
     * {@link #preRoll} checked the command item, the tamed role and the caps before the roll; the
     * record is registered (caps checked again under the index lock) and stamped before the
     * ownership components are written, so the tame systems see a stamped body and skip it.
     */
    private boolean tameAndLink(Player player, Ref<EntityStore> targetRef, ItemFeatureConfig resolved,
                                CaptureAttemptHandle attempt, String targetRole,
                                @Nullable String particleSystemOverride, Consumer<UUID> resolvedAttempt) {
        World world = player.getWorld();
        Store<EntityStore> store = world == null || world.getEntityStore() == null
                ? null : world.getEntityStore().getStore();
        if (store == null || targetRef.getStore() != store) {
            logCaptureChannelDiagnostic("terminal-denied reason=target-in-other-world");
            return false;
        }
        if (store.getComponent(targetRef, TameworkCompanionComponent.getComponentType()) != null) {
            warn(player, "captureProfileConflict");
            return false;
        }
        CompanionTransitions.BodyFacts facts = CompanionBodyFacts.read(targetRef, store, summaries);
        if (facts == null || facts.ownerUuid() != null) {
            warn(player, "captureEvidenceFailed");
            return false;
        }
        String familyId = resolved.getCaptureMechanics().commandFamilyId();
        UUID owner = player.getUuid();
        ItemStack current = inventory.getHotbarItem(player, attempt.hotbarSlot());
        SpawnerSourceItemTransaction spend = current == null || current.isEmpty() ? null
                : new SpawnerSourceItemTransaction(inventory, player, attempt.hotbarSlot(), current, logger,
                "capture-tame-and-link");
        if (spend == null || !spend.consumeOne()) {
            logCaptureChannelDiagnostic("terminal-denied reason=source-changed");
            return false;
        }
        String ownerName = OwnerNameUtil.resolve(player);
        String link = rosterLinkId(owner, familyId);
        UUID profileId = UUID.randomUUID();
        CompanionRecord record = CompanionTransitions.newLive(profileId, 0,
                        new CompanionTransitions.BodyFacts(facts.npcUuid(), owner, ownerName, targetRole,
                                facts.displayName(), facts.world(), facts.x(), facts.y(), facts.z(), List.of(link),
                                facts.summary()))
                .toBuilder().rosterId(familyId).rosterSlot(-1).build();
        CompanionRegistration.Outcome outcome = CompanionRegistration.register(index, loaded, record, targetRef,
                candidate -> admissionGate.refuse(null, candidate));
        if (!outcome.registered()) {
            spend.compensate();
            if (outcome.refusal() != null) {
                showPopulationLimit(player, outcome.refusal() == CompanionAdmission.Refusal.OWNED);
            } else {
                warn(player, "captureProfileConflict");
            }
            return false;
        }
        spend.commit();
        tameBody(targetRef, store, owner, ownerName, link, profileId, targetRole);
        resolvedAttempt.accept(profileId);
        String particles = particleSystemOverride == null || particleSystemOverride.isBlank()
                ? resolved.getCaptureParticleSystem() : particleSystemOverride;
        effects.playPublishedEffect(world, new SpawnerPublishedEffect(
                facts.x(), facts.y(), facts.z(), particles, resolved.getCaptureSoundEvent()));
        return true;
    }

    /**
     * Writes the stamp first, then tamed, owner and the roster link, and requests the tamed role.
     * World thread.
     *
     * <p>No command buffer reaches this point. The interaction path runs inside the interaction's
     * {@code commandBuffer.run} consumer, which hands over the store once the buffer is applied;
     * the NPC-action path ({@code ActionTameworkCaptureWild}) gets only the store from the role's
     * action call. Both may write the store directly: the consumer runs outside any system
     * iteration, and the engine runs the role tick after the chunk pass, so a direct write there
     * (as {@code ActionTameworkSetOwner} does) does not disturb an iteration. The engine removes an
     * NPC whose role tick throws, so a failure here is logged and not rethrown: the record is
     * already registered and the body stays, possibly without all of its ownership components.
     */
    private void tameBody(Ref<EntityStore> ref, Store<EntityStore> store, UUID owner, String ownerName,
                          String link, UUID profileId, String targetRole) {
        try {
            store.putComponent(ref, TameworkCompanionComponent.getComponentType(),
                    new TameworkCompanionComponent(profileId, 0));
            TameworkEntityEffectService.removeEffect(ref, TRANQUILIZER_EFFECT_ID, store);
            store.putComponent(ref, TameworkTamedComponent.getComponentType(), new TameworkTamedComponent(true));
            store.putComponent(ref, TameworkOwnerComponent.getComponentType(),
                    new TameworkOwnerComponent(owner, ownerName));
            store.putComponent(ref, TameworkCommandLinksComponent.getComponentType(),
                    new TameworkCommandLinksComponent(owner, new String[]{link}));
            CompanionSpawnAuthorityService.detach(ref, store);
            CompanionProgressionBootstrapService.ensureProgressionComponents(ref, store, targetRole);
            NPCEntity npc = store.getComponent(ref, NPCEntity.getComponentType());
            String currentRole = npc == null || npc.getRole() == null ? null : npc.getRole().getRoleName();
            NPCPlugin plugin = NPCPlugin.get();
            int roleIndex = plugin == null ? -1 : plugin.getIndex(targetRole);
            if (npc != null && npc.getRole() != null && roleIndex >= 0 && !targetRole.equals(currentRole)) {
                RoleChangeSystem.requestRoleChange(ref, npc.getRole(), roleIndex, false, null, null, true, store);
            }
            CompanionSaves.markChanged(store, ref);
        } catch (RuntimeException failure) {
            logger.at(Level.WARNING).withCause(failure).log(
                    "Tame-and-link of companion %s registered, but its body could not be fully tamed", profileId);
        }
    }

    /** The command link a tame-and-link roster member carries for its owner's command family. */
    @Nonnull
    static String rosterLinkId(@Nonnull UUID owner, @Nonnull String familyId) {
        return "roster:" + owner + ":" + familyId;
    }


    /** Runs on the body's world thread: reads the body, snapshots it and starts the commit. */
    private boolean captureIntoItem(Player player, Ref<EntityStore> targetRef, ItemStack source,
                                    ItemFeatureConfig resolved, CaptureAttemptHandle attempt, String roleId,
                                    @Nullable String particleSystemOverride, Consumer<UUID> resolvedAttempt) {
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
        if (facts == null) {
            warn(player, "captureEvidenceFailed");
            return false;
        }
        // preRoll checked the caps for a new owner; CaptureFlow checks them again under the index lock.
        UUID owner = CaptureItemOwnership.captureOwner(facts.ownerUuid(),
                TamedStateResolver.isTamed(targetRef, store), resolved.isCaptureTamesTarget(), player.getUuid());
        UUID busyKey = stampedId != null ? stampedId : facts.npcUuid();
        // This runs on the body's world thread, so only a capture still committing can overlap.
        if (capturing.contains(busyKey)) {
            logCaptureChannelDiagnostic("terminal-denied reason=capture-in-flight");
            return false;
        }
        long capturedAtMs = System.currentTimeMillis();
        SnapshotEnvelope snapshot = snapshots.capture(targetRef, store, busyKey, stampedGeneration,
                world.getName(), CompanionWorldTime.gameTimeMs(store));
        if (snapshot == null) {
            warn(player, "captureEvidenceFailed");
            return false;
        }
        BsonDocument snapshotData = snapshot.data();
        if (resolved.isCaptureTamesTarget()) {
            // TamesTarget means the result is tamed and owned by the capturing player.
            snapshotData = new BsonDocument();
            snapshotData.putAll(snapshot.data());
            snapshotData.put("Entity", SnapshotPatch.withTamed(CompanionSnapshots.entity(snapshot)));
        }
        String ownerName = owner == null ? null
                : owner.equals(facts.ownerUuid()) ? facts.ownerName() : OwnerNameUtil.resolve(player);
        ItemStack item = capturedItems.build(player, targetRef, store, source, resolved, roleId, owner, ownerName);
        ItemStack expectedSource = inventory.getHotbarItem(player, attempt.hotbarSlot());
        String particles = particleSystemOverride == null || particleSystemOverride.isBlank()
                ? resolved.getCaptureParticleSystem() : particleSystemOverride;
        SpawnerPublishedEffect effect = new SpawnerPublishedEffect(
                facts.x(), facts.y(), facts.z(), particles, resolved.getCaptureSoundEvent());
        UUID playerUuid = player.getUuid();
        int slot = attempt.hotbarSlot();
        if (!capturing.add(busyKey)) {
            logCaptureChannelDiagnostic("terminal-denied reason=capture-in-flight");
            return false;
        }
        try {
            captureFlow.capture(new CaptureFlow.Capture<>(stampedId, stampedGeneration, targetRef, facts, owner,
                            ownerName, snapshotData))
                    .whenComplete((outcome, error) -> {
                        capturing.remove(busyKey);
                        if (error != null) {
                            logger.at(Level.WARNING).withCause(error).log("Capture commit failed unexpectedly");
                        }
                        finishCapture(outcome, targetRef, stampedId, stampedGeneration,
                                item, expectedSource, playerUuid, slot, effect, capturedAtMs, resolvedAttempt);
                    });
        } catch (RuntimeException failure) {
            capturing.remove(busyKey);
            logger.at(Level.WARNING).withCause(failure).log("Capture commit could not start");
            warn(player, "captureUnavailable");
            return false;
        }
        return true;
    }

    /**
     * Runs on whichever thread completed the commit; hands live work to world threads. The resolved
     * attempt is published from the hand-over, on the body's world thread, once the body is known
     * to be there.
     */
    private void finishCapture(@Nullable CaptureFlow.Outcome outcome, Ref<EntityStore> body,
                               @Nullable UUID stampedId, long stampedGeneration, ItemStack item,
                               @Nullable ItemStack expectedSource, UUID playerUuid, int slot,
                               SpawnerPublishedEffect effect, long capturedAtMs, Consumer<UUID> resolvedAttempt) {
        CaptureFlow.Result result = outcome == null ? CaptureFlow.Result.COMMIT_FAILED : outcome.result();
        switch (result) {
            case CAPTURED -> delivery.deliver(new HytaleCaptureDelivery.Handover(body, outcome.itemRef(), item,
                    playerUuid, slot, expectedSource == null ? ItemStack.EMPTY : expectedSource,
                    world -> {
                        resolvedAttempt.accept(outcome.itemRef().profileId());
                        effects.playPublishedEffect(world, effect);
                    }, capturedAtMs));
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
            case OWNED_LIMIT, GROUP_LIMIT -> HytaleCaptureDelivery.onPlayerWorld(playerUuid,
                    (world, store, ref, player) -> showPopulationLimit(player, result == CaptureFlow.Result.OWNED_LIMIT),
                    null);
        }
    }

    /**
     * Releases a 5.0 capture item through {@link RestoreFlow}. The ownership mode decides who may
     * release, read from the record, not the item. Returns true when the restore started.
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
        CaptureItemOwnership.Release ownership = CaptureItemOwnership.release(
                TameworkRuntimeSettings.current().captureItemOwnership(),
                record == null ? null : record.ownerUuid(), player.getUuid());
        if (CaptureItemOwnership.releaseRefused(ownership, record, ref.generation())) {
            // Bound to its owner: nothing changes, and the item stays filled.
            messages.showKey(player, NotificationStyle.Warning,
                    "tamework.ui.notifications.captureItem.ownerOnlyRelease",
                    CaptureItemHolderSystems.Transfers.ownerLabel(player, record));
            return false;
        }
        // The ownership mode alone decides who may release. A stale copy goes on to the restore,
        // which answers STALE and empties it.
        SpawnerReleaseIntentFactory.PreparedRelease prepared =
                releaseIntents.prepare(player, source, config, hotbarSlot, emptyItemIdOverride);
        if (prepared == null) {
            return false;
        }
        RestoreFlow.Request request = RestoreFlow.Request.of(ref.profileId(), RestoreRules.Reason.RELEASE,
                RestoreFlow.Destination.of(prepared.placement())).withGeneration(ref.generation());
        RestoreFlow.Owner owner = releaseOwner(ownership, player.getUuid(), OwnerNameUtil.resolve(player));
        if (owner != null) {
            request = request.withOwner(owner);
        }
        UUID playerUuid = player.getUuid();
        UUID profileId = ref.profileId();
        if (!releasing.add(profileId)) {
            // A release of this companion is still running; this one could only end stale.
            return false;
        }
        CompletableFuture<RestoreFlow.Result> restored;
        try {
            restored = restoreFlow.restore(request);
        } catch (RuntimeException failure) {
            releasing.remove(profileId);
            logger.at(Level.WARNING).withCause(failure).log("Release of companion %s could not start", profileId);
            warn(player, "releaseFailed");
            return false;
        }
        restored.whenComplete((result, error) -> {
            releasing.remove(profileId);
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
            case OWNED_LIMIT -> showPopulationLimit(player, true);
            case GROUP_LIMIT -> showPopulationLimit(player, false);
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
     * The owner a release asks the restore for (spec 8.14): none for the owner's own release (null:
     * no change), the releasing player when the mode gives the companion to them (the restore's
     * caps still apply), and no owner for an unowned wild capture.
     */
    @Nullable
    static RestoreFlow.Owner releaseOwner(@Nonnull CaptureItemOwnership.Release ownership,
                                          @Nonnull UUID releaser, @Nullable String releaserName) {
        return switch (ownership) {
            case UNOWNED -> new RestoreFlow.Owner(null, null);
            case ASSIGN_RELEASER -> new RestoreFlow.Owner(releaser, releaserName);
            case KEEP_OWNER, REFUSE_NOT_OWNER -> null;
        };
    }

    /**
     * The filled capture item as {@code owner} owns it: the owner id, the owner name and the
     * tooltip built from them, for the write that moves a companion to the player holding its
     * item. Call on a world thread.
     */
    @Nonnull
    public ItemStack withCaptureOwner(@Nonnull ItemStack stack, @Nonnull UUID owner, @Nullable String ownerName) {
        ItemStack owned = itemMetadata.applyOwnerMetadata(stack, owner, ownerName);
        ItemStack displayed = displayMetadata.applyCapturedDisplayMetadata(owned, resolveConfigForItem(owned));
        return displayed == null ? owned : displayed;
    }

    /**
     * After a Recall or Forget (spec 8.14): empties the copies of the companion's capture item
     * that {@code playerUuid} holds, so they do not keep their filled look until used. Safe from
     * any thread; the player is resolved on their current world thread and an offline player is
     * skipped. {@link #emptyLocatedCaptureItem} covers the copy the item locator last saw.
     */
    public void emptyHeldCaptureItems(@Nullable UUID playerUuid, @Nonnull UUID profileId) {
        if (playerUuid != null) {
            HytaleCaptureDelivery.onPlayerWorld(playerUuid,
                    (world, store, ref, player) -> emptyStaleCaptureItems(store, ref, profileId), null);
        }
    }

    /**
     * After a Recall or Forget: empties the item where the locator last saw it, when that is not
     * {@code owner}'s inventory, which {@link #emptyHeldCaptureItems} covers. One lookup of the
     * item made for {@code profileId} at {@code itemGeneration}: a block container or a dropped
     * item is edited in place on its world thread, and another online player's inventory on that
     * player's world thread. No chunk is loaded; an unknown, unloaded or offline holder is left
     * alone, and its item still turns empty on use. Safe from any thread.
     */
    public void emptyLocatedCaptureItem(@Nonnull CapturedItemTracker tracker, @Nullable UUID owner,
                                        @Nonnull UUID profileId, long itemGeneration) {
        CapturedItemLocationIndex.Sighting sighting = tracker.index()
                .find(CapturedItemMetadata.indexKey(profileId, itemGeneration)).orElse(null);
        if (sighting == null) {
            return;
        }
        CapturedItemLocationIndex.Holder holder = sighting.holder();
        if (holder.kind() != CapturedItemLocationIndex.Kind.PLAYER) {
            tracker.editStacks(holder, stack -> emptiedIfStale(stack, profileId));
            return;
        }
        try {
            UUID holderUuid = UUID.fromString(holder.id());
            if (!holderUuid.equals(owner)) {
                emptyHeldCaptureItems(holderUuid, profileId);
            }
        } catch (IllegalArgumentException notAPlayerUuid) {
            // A sighting that names no player has nothing to sweep.
        }
    }

    /**
     * The emptied form of a stale copy of {@code profileId}'s capture item, or null when the stack
     * is something else, has no empty form, or is still current for its record (captured again
     * since), in which case it keeps its companion.
     */
    @Nullable
    private ItemStack emptiedIfStale(@Nullable ItemStack stack, UUID profileId) {
        CaptureItemKeys.Ref item = CaptureItemKeys.readIndexItem(stack);
        if (item == null || !item.profileId().equals(profileId)
                || !CaptureItemOwnership.isStale(index.get(profileId), item.generation())) {
            return null;
        }
        String emptyItemId = itemMetadata.resolveEmptyItemId(stack.getItemId());
        return emptyItemId == null || emptyItemId.isBlank() ? null
                : itemMetadata.clearCapturedMetadata(itemMetadata.swapItemId(stack, emptyItemId));
    }

    /** World thread: Hotbar, Storage, Backpack and Tool, compare-then-replace per slot. */
    private void emptyStaleCaptureItems(Store<EntityStore> store, Ref<EntityStore> playerRef, UUID profileId) {
        for (var type : CaptureItemHolderSystems.Transfers.holderInventories()) {
            InventoryComponent inventory = store.getComponent(playerRef, type);
            ItemContainer container = inventory == null ? null : inventory.getInventory();
            if (container == null) {
                continue;
            }
            for (short slot = 0, capacity = container.getCapacity(); slot < capacity; slot++) {
                ItemStack stack = container.getItemStack(slot);
                ItemStack emptied = emptiedIfStale(stack, profileId);
                if (emptied != null) {
                    container.replaceItemStackInSlot(slot, stack, emptied);
                }
            }
        }
    }

    private void showPopulationLimit(Player player, boolean owned) {
        messages.showKey(player, NotificationStyle.Warning,
                owned ? "tamework.ui.population.ownedLimit" : "tamework.ui.population.groupLimit");
    }

    @Nullable
    private ItemFeatureConfig resolveConfigForItem(ItemStack source) {
        if (source == null || source.getItemId() == null) {
            return null;
        }
        return registry.getForFilledOrEmpty(source.getItemId());
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
