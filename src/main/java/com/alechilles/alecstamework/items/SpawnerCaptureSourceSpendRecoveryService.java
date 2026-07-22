package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.api.CommandFamilyRosterMemberState;
import com.alechilles.alecstamework.api.CommandFamilyRosterMutationRequest;
import com.alechilles.alecstamework.command.roster.CommandFamilyRosterService;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.items.capturepolicy.runtime.CaptureAttemptCoordinator;
import com.alechilles.alecstamework.persistence.sqlite.CaptureAttemptRecord;
import com.alechilles.alecstamework.persistence.sqlite.CaptureAttemptRepository;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationOperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.CommandTimedSummonSessionRecord;
import com.alechilles.alecstamework.persistence.sqlite.NpcProfileRepository;
import com.alechilles.alecstamework.persistence.sqlite.TameworkPersistenceRuntime;
import com.alechilles.alecstamework.runtime.dispatch.LeaseBoundWorldDispatcher;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.server.core.universe.world.World;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.bson.BsonDocument;

/** Player-join convergence for a resolved capture whose exact source spend crossed a crash. */
final class SpawnerCaptureSourceSpendRecoveryService {
    private final CaptureAttemptCoordinator attempts;
    private final HytaleLogger logger;
    private final Set<UUID> recovering = ConcurrentHashMap.newKeySet();

    SpawnerCaptureSourceSpendRecoveryService(
            @Nonnull CaptureAttemptCoordinator attempts, @Nonnull HytaleLogger logger) {
        this.attempts = attempts;
        this.logger = logger;
    }

    void recoverAfterWorldJoin(@Nullable World world, @Nullable UUID playerUuid) {
        Tamework plugin = Tamework.getInstance();
        TameworkPersistenceRuntime persistence = plugin == null
                ? null : plugin.getPersistenceRuntime();
        if (world == null || playerUuid == null || persistence == null
                || !persistence.getHealthService().isHealthy()
                || !recovering.add(playerUuid)) return;
        CompletableFuture.supplyAsync(() -> load(
                persistence.getCaptureAttemptRepository(), playerUuid))
                .whenComplete((batch, failure) -> {
                    if (failure != null || batch == null) {
                        recovering.remove(playerUuid);
                        return;
                    }
                    LeaseBoundWorldDispatcher.execute(
                            world,
                            () -> recoverOnWorld(world, playerUuid, batch,
                                    persistence.getCaptureAttemptRepository(), persistence),
                            () -> recovering.remove(playerUuid));
                });
    }

    private void recoverOnWorld(World world, UUID playerUuid, RecoveryBatch batch,
                                CaptureAttemptRepository repository,
                                TameworkPersistenceRuntime persistence) {
        WorldPlayerResolver.ResolvedPlayer resolved = WorldPlayerResolver.resolve(world, playerUuid);
        if (resolved == null) {
            recovering.remove(playerUuid);
            return;
        }
        CompletableFuture<Boolean> chain = CompletableFuture.completedFuture(true);
        for (CaptureAttemptRepository.SourceRefundClaim claim : batch.refunds()) {
            chain = chain.thenCompose(ignored -> deliverRefund(
                    world, resolved.player(), claim, repository));
        }
        for (CaptureAttemptRecord attempt : batch.pendingSpends()) {
            chain = chain.thenCompose(ignored -> recoverOne(world, resolved.player(), attempt));
        }
        for (CaptureAttemptRecord attempt : batch.tameLinkConvergence()) {
            chain = chain.thenCompose(ignored -> convergeTameLink(attempt, persistence));
        }
        chain.whenComplete((ignored, failure) -> {
            recovering.remove(playerUuid);
            if (failure != null) {
                logger.at(Level.WARNING).log(
                        "Capture source-spend recovery remains pending for " + playerUuid + ".");
            }
        });
    }

    private CompletableFuture<Boolean> recoverOne(
            World world, Player player, CaptureAttemptRecord attempt) {
        SourceContext context = SourceContext.parse(attempt.identity().sourceContextJson());
        ItemContainer hotbar = player.getInventory() == null ? null : player.getInventory().getHotbar();
        if (context == null || hotbar == null || !world.getName().equals(context.world())) {
            return CompletableFuture.completedFuture(false);
        }
        UUID attemptId = UUID.fromString(attempt.identity().attemptId());
        Integer receiptSlot = matchingSlot(hotbar, context.slot(), stack ->
                SpawnerCaptureSourceReceipt.belongsTo(stack, attemptId)
                        && attempt.sourceSpend().beforeFingerprint().equals(
                        SpawnerSourceFingerprint.of(SpawnerCaptureSourceReceipt.original(stack))));
        if (receiptSlot != null) {
            ItemStack receipt = hotbar.getItemStack(receiptSlot.shortValue());
            CompletableFuture<CaptureAttemptRecord> receiptFence =
                    attempt.sourceSpend().receiptedAtMs() > 0L
                            ? CompletableFuture.completedFuture(attempt)
                            : attempts.confirmSourceReceipted(attemptId);
            return receiptFence.thenCompose(ignored -> finishReceipt(
                    world, player, receiptSlot, receipt, attemptId,
                    attempt.state() == CaptureAttemptRecord.State.RESOLVED_SUCCESS));
        }
        if (attempt.state() == CaptureAttemptRecord.State.RESOLVED_SUCCESS
                && attempt.sourceSpend().receiptedAtMs() <= 0L) {
            return attempts.cancelUnreceiptedSuccess(
                    attemptId, "capture-restart-before-source-receipt");
        }
        if (attempt.sourceSpend().receiptedAtMs() <= 0L) {
            Integer beforeSlot = matchingSlot(hotbar, context.slot(), stack ->
                    attempt.sourceSpend().beforeFingerprint().equals(
                            SpawnerSourceFingerprint.of(stack)));
            if (beforeSlot == null) return CompletableFuture.completedFuture(false);
            ItemStack source = hotbar.getItemStack(beforeSlot.shortValue());
            ItemStack receipt = SpawnerCaptureSourceReceipt.mark(source, attemptId);
            SpawnerSourceItemTransaction transaction = new SpawnerSourceItemTransaction(
                    new SpawnerPlayerInventoryService(), player, beforeSlot, source, logger,
                    "Capture source receipt recovery");
            if (!transaction.prepare(receipt)) return CompletableFuture.completedFuture(false);
            return attempts.confirmSourceReceipted(attemptId).thenCompose(ignored -> {
                transaction.commit();
                return onWorld(world, () -> finishReceipt(
                        world, player, beforeSlot, receipt, attemptId, false));
            }).exceptionally(failure -> {
                world.execute(transaction::compensate);
                return false;
            });
        }
        boolean exactAfter = SpawnerSourceFingerprint.EMPTY_AFTER_CONSUMPTION.equals(
                attempt.sourceSpend().afterFingerprint())
                ? isEmpty(hotbar, context.slot())
                : matchingSlot(hotbar, context.slot(), stack ->
                attempt.sourceSpend().afterFingerprint().equals(
                        SpawnerSourceFingerprint.of(stack))) != null;
        return exactAfter ? confirmConsumed(
                attemptId, attempt.state() == CaptureAttemptRecord.State.RESOLVED_SUCCESS)
                : CompletableFuture.completedFuture(false);
    }

    private CompletableFuture<Boolean> finishReceipt(
            World world, Player player, int slot, ItemStack receipt, UUID attemptId,
            boolean successfulOutcome) {
        CompletableFuture<Boolean> completion = new CompletableFuture<>();
        world.execute(() -> {
            SpawnerSourceItemTransaction decrement = new SpawnerSourceItemTransaction(
                    new SpawnerPlayerInventoryService(), player, slot, receipt, logger,
                    "Capture source decrement recovery");
            if (!decrement.prepare(SpawnerCaptureSourceReceipt.after(receipt))) {
                completion.complete(false);
                return;
            }
            confirmConsumed(attemptId, successfulOutcome).whenComplete((confirmed, failure) -> {
                decrement.commit();
                completion.complete(failure == null && Boolean.TRUE.equals(confirmed));
            });
        });
        return completion;
    }

    private CompletableFuture<Boolean> confirmConsumed(
            UUID attemptId, boolean successfulOutcome) {
        return attempts.confirmSourceConsumed(attemptId).thenCompose(ignored ->
                attempts.recover(128).thenCompose(report -> successfulOutcome
                        ? attempts.requireSourceRefund(
                        attemptId, "capture-restart-live-continuation-unavailable")
                        : CompletableFuture.completedFuture(report.ready())));
    }

    private CompletableFuture<Boolean> convergeTameLink(
            CaptureAttemptRecord attempt, TameworkPersistenceRuntime persistence) {
        return CompletableFuture.supplyAsync(() -> loadTameLinkSnapshot(attempt, persistence))
                .thenCompose(snapshot -> {
                    if (snapshot == null) return CompletableFuture.completedFuture(false);
                    CompanionPopulationOperationRecord operation = snapshot.operation();
                    UUID attemptId = UUID.fromString(attempt.identity().attemptId());
                    if (operation.state() == CompanionPopulationOperationRecord.State.PREPARED
                            && attempt.state() == CaptureAttemptRecord.State.RESOLVED_SUCCESS) {
                        // The source crossed the crash, but beginApply/live mutation never did.
                        return attempts.requireSourceRefund(
                                attemptId, "capture-restart-before-begin-apply");
                    }
                    if (operation.state() == CompanionPopulationOperationRecord.State.RETRYABLE
                            || operation.state() == CompanionPopulationOperationRecord.State.FAILED) {
                        return attempts.requireSourceRefund(
                                attemptId, "capture-restart-population-compensated");
                    }
                    if (operation.state() != CompanionPopulationOperationRecord.State.COMMITTED
                            || !snapshot.profileValid()) {
                        return CompletableFuture.completedFuture(false);
                    }
                    return replayTameLink(attempt, operation, snapshot.profile());
                }).exceptionally(failure -> false);
    }

    private CompletableFuture<Boolean> replayTameLink(
            CaptureAttemptRecord attempt, CompanionPopulationOperationRecord operation,
            NpcProfileRepository.ProfileRecord profile) {
        Tamework plugin = Tamework.getInstance();
        CommandFamilyRosterService rosters = plugin == null
                ? null : plugin.getCommandFamilyRosterService();
        CommandTimedSummoningService timed = plugin == null
                ? null : plugin.getCommandTimedSummoningService();
        SpawnerTameLinkDurableContext.Evidence evidence =
                SpawnerTameLinkDurableContext.parse(attempt.identity().sourceContextJson());
        if (rosters == null || timed == null || evidence == null
                || !evidence.commandFamilyId().equals(attempt.config().commandFamilyId())
                || !evidence.targetRoleId().equals(profile.roleId())
                || profile.currentNpcUuid() == null) {
            return CompletableFuture.completedFuture(false);
        }
        long profileRevision = operation.expectedRevision() + 1L;
        UUID attemptId = UUID.fromString(attempt.identity().attemptId());
        return rosters.loadRevisionProof(attempt.identity().actorUuid(), evidence.commandFamilyId())
                .thenCompose(proof -> rosters.upsert(new CommandFamilyRosterMutationRequest(
                        "alecstamework.capture-recovery",
                        "tame-and-command-link-recovery:" + attemptId,
                        attemptId, attempt.identity().actorUuid(), evidence.commandFamilyId(),
                        profile.profileId(), evidence.requiredCommandConfigId(),
                        evidence.accessItemId(), CommandFamilyRosterMemberState.ACTIVE,
                        null, true, null, proof.revision(), profileRevision)))
                .thenCompose(rosterResult -> {
                    if (rosterResult == null || !rosterResult.accepted()) {
                        return CompletableFuture.completedFuture(false);
                    }
                    return timed.registerActiveProjection(
                            new CommandTimedSummoningService.ActiveRegistration(
                                    attempt.identity().actorUuid(), evidence.commandFamilyId(),
                                    profile.profileId(), profileRevision, profile.currentNpcUuid(),
                                    evidence.timedConfigId(), evidence.timedConfigRevision(),
                                    evidence.policy(), "tame-and-command-link:" + attemptId,
                                    System.currentTimeMillis())).thenCompose(lease -> {
                        if (lease == null
                                || lease.status() != CommandTimedSummoningService.Status.SUCCESS
                                || lease.session() == null
                                || lease.session().state()
                                != CommandTimedSummonSessionRecord.State.ACTIVE) {
                            return CompletableFuture.completedFuture(false);
                        }
                        return attempts.commitRecoveredTameLink(attemptId, attempt.state());
                    });
                }).toCompletableFuture();
    }

    @Nullable
    private static TameLinkSnapshot loadTameLinkSnapshot(
            CaptureAttemptRecord attempt, TameworkPersistenceRuntime persistence) {
        try {
            CompanionPopulationOperationRecord operation = persistence
                    .getCompanionPopulationRepository().findOperation(attempt.populationOperationId());
            NpcProfileRepository.ProfileRecord profile = persistence.getNpcProfileRepository()
                    .loadProfileById(attempt.identity().profileId());
            boolean valid = operation != null && profile != null
                    && java.util.Objects.equals(
                    attempt.identity().profileId(), operation.profileId())
                    && attempt.identity().actorUuid().equals(profile.ownerUuid())
                    && Boolean.TRUE.equals(profile.tamed())
                    && profile.currentNpcUuid() != null
                    && profile.currentNpcUuid().equals(attempt.identity().targetNpcUuid());
            return operation == null ? null : new TameLinkSnapshot(operation, profile, valid);
        } catch (Exception failure) {
            throw new CompletionException(failure);
        }
    }

    private CompletableFuture<Boolean> deliverRefund(
            World world, Player player, CaptureAttemptRepository.SourceRefundClaim claim,
            CaptureAttemptRepository repository) {
        CombinedItemContainer combined = player.getInventory() == null ? null
                : player.getInventory().getCombinedBackpackStorageHotbar();
        if (combined == null) return CompletableFuture.completedFuture(false);
        UUID attemptId = UUID.fromString(claim.attemptId());
        ItemStack receipt = findRefundReceipt(combined, attemptId);
        if (claim.delivered()) {
            if (receipt != null) clearRefundReceipt(combined, attemptId);
            return CompletableFuture.completedFuture(true);
        }
        if (receipt == null) {
            receipt = new ItemStack(claim.itemId(), claim.quantity()).withMetadata(
                    TameworkMetadataKeys.CAPTURE_SOURCE_REFUND_ATTEMPT_ID,
                    Codec.UUID_STRING, attemptId);
            ItemStackTransaction added = combined.addItemStack(receipt, true, false, true);
            if (added == null || !added.succeeded()
                    || (added.getRemainder() != null && !added.getRemainder().isEmpty())) {
                return CompletableFuture.completedFuture(false);
            }
        }
        var submission = repository.completeSourceRefundAsync(
                claim.attemptId(), System.currentTimeMillis());
        if (!submission.accepted()) return CompletableFuture.completedFuture(false);
        return submission.completion().thenCompose(outcome -> {
            if (outcome == null || !outcome.isCommitted()
                    || !Boolean.TRUE.equals(outcome.value())) {
                return CompletableFuture.completedFuture(false);
            }
            return onWorld(world, () -> {
                clearRefundReceipt(combined, attemptId);
                return CompletableFuture.completedFuture(true);
            });
        });
    }

    @Nullable
    private static ItemStack findRefundReceipt(
            CombinedItemContainer combined, UUID attemptId) {
        for (short slot = 0; slot < combined.getCapacity(); slot++) {
            ItemStack stack = combined.getItemStack(slot);
            if (stack == null || stack.isEmpty()) continue;
            UUID stored = stack.getFromMetadataOrNull(
                    TameworkMetadataKeys.CAPTURE_SOURCE_REFUND_ATTEMPT_ID,
                    Codec.UUID_STRING);
            if (attemptId.equals(stored)) return stack;
        }
        return null;
    }

    private static void clearRefundReceipt(
            CombinedItemContainer combined, UUID attemptId) {
        for (short slot = 0; slot < combined.getCapacity(); slot++) {
            ItemStack current = combined.getItemStack(slot);
            if (current == null || current.isEmpty()) continue;
            UUID stored = current.getFromMetadataOrNull(
                    TameworkMetadataKeys.CAPTURE_SOURCE_REFUND_ATTEMPT_ID,
                    Codec.UUID_STRING);
            if (!attemptId.equals(stored)) continue;
            BsonDocument metadata = current.getMetadata() == null
                    ? null : current.getMetadata().clone();
            if (metadata != null) {
                metadata.remove(TameworkMetadataKeys.CAPTURE_SOURCE_REFUND_ATTEMPT_ID);
            }
            ItemStack clean = current.withMetadata(
                    metadata == null || metadata.isEmpty() ? null : metadata);
            combined.replaceItemStackInSlot(slot, current, clean);
            return;
        }
    }

    private static CompletableFuture<Boolean> onWorld(
            World world, java.util.function.Supplier<CompletableFuture<Boolean>> action) {
        CompletableFuture<Boolean> completion = new CompletableFuture<>();
        world.execute(() -> action.get().whenComplete((result, failure) -> {
            if (failure == null) completion.complete(result);
            else completion.completeExceptionally(failure);
        }));
        return completion;
    }

    @Nullable
    private static Integer matchingSlot(ItemContainer hotbar, int preferred,
                                        java.util.function.Predicate<ItemStack> matches) {
        return SpawnerSourceSlotResolver.resolveMatching(
                hotbar.getCapacity(), slot -> hotbar.getItemStack((short) slot),
                stack -> stack != null && !stack.isEmpty() && matches.test(stack), preferred);
    }

    private static boolean isEmpty(ItemContainer hotbar, int slot) {
        if (slot < 0 || slot >= hotbar.getCapacity()) return false;
        ItemStack stack = hotbar.getItemStack((short) slot);
        return stack == null || stack.isEmpty();
    }

    private static RecoveryBatch load(
            CaptureAttemptRepository repository, UUID playerUuid) {
        try {
            return new RecoveryBatch(
                    repository.loadPendingSourceSpends(playerUuid),
                    repository.loadPendingSourceRefunds(playerUuid),
                    repository.loadPendingTameLinkConvergence(playerUuid));
        } catch (Exception failure) {
            throw new CompletionException(failure);
        }
    }

    private record RecoveryBatch(
            @Nonnull List<CaptureAttemptRecord> pendingSpends,
            @Nonnull List<CaptureAttemptRepository.SourceRefundClaim> refunds,
            @Nonnull List<CaptureAttemptRecord> tameLinkConvergence) {
        private RecoveryBatch {
            pendingSpends = List.copyOf(pendingSpends);
            refunds = List.copyOf(refunds);
            tameLinkConvergence = List.copyOf(tameLinkConvergence);
        }
    }

    private record TameLinkSnapshot(
            @Nonnull CompanionPopulationOperationRecord operation,
            @Nullable NpcProfileRepository.ProfileRecord profile,
            boolean profileValid) {
    }

    private record SourceContext(@Nonnull String world, int slot) {
        @Nullable
        static SourceContext parse(String json) {
            try {
                JsonElement parsed = JsonParser.parseString(json);
                JsonObject object = parsed != null && parsed.isJsonObject()
                        ? parsed.getAsJsonObject() : null;
                if (object == null || object.get("version").getAsInt() != 1
                        || !"hotbar".equals(object.get("inventory").getAsString())) return null;
                return new SourceContext(object.get("world").getAsString(),
                        object.get("slot").getAsInt());
            } catch (RuntimeException invalid) {
                return null;
            }
        }
    }
}
