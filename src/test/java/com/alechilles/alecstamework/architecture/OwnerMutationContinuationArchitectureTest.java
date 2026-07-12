package com.alechilles.alecstamework.architecture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the asynchronous ownership continuation paths against raw-write regressions. */
class OwnerMutationContinuationArchitectureTest {
    private static final Path MAIN = Paths.get("src", "main", "java", "com", "alechilles", "alecstamework");

    @Test
    void assignedOwnerMutationPathsResolveTheSchedulerAndDoNotWriteOwnerComponentsDirectly()
            throws IOException {
        List<Path> paths = List.of(
                MAIN.resolve(Paths.get("commands", "TameworkSetOwnerCommand.java")),
                MAIN.resolve(Paths.get("npc", "actions", "ActionTameworkSetOwner.java")),
                MAIN.resolve(Paths.get("npc", "actions", "InteractionOwnerAdmissionService.java")),
                MAIN.resolve(Paths.get("ownership", "LegacyTamedOwnershipBridge.java"))
        );
        for (Path path : paths) {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            assertTrue(source.contains("getOwnerMutationScheduler()"), path + " must resolve the scheduler");
            assertFalse(
                    source.matches("(?s).*putComponent\\s*\\([^;]*new\\s+TameworkOwnerComponent\\s*\\(.*"),
                    path + " must not write owner components directly"
            );
        }
    }

    @Test
    void tameAndLegacyDependentWorkLivesInAppliedContinuations() throws IOException {
        String executor = read("npc", "actions", "InteractionExecutor.java");
        String effects = read("npc", "actions", "InteractionStateEffects.java");
        String bridge = read("ownership", "LegacyTamedOwnershipBridge.java");
        String linkService = read("items", "CommandLinkMutationService.java");

        assertTrue(executor.contains("(liveNpcRef, liveStore, livePlayer) ->"));
        assertTrue(executor.contains("feedHelper.consumeHeldItem(livePlayer, 1)"));
        assertTrue(effects.contains("applyTameBundleAfterOwnership(targetRef, targetStore)"));
        assertTrue(bridge.contains("public boolean isScheduled()"));
        assertTrue(bridge.contains("callbacks.complete(new ClaimContext("));
        assertTrue(linkService.contains("return LinkToggleResult.pending()"));
        assertTrue(linkService.contains("deferredHandler.onOwnershipApplied("));
    }

    @Test
    void liveOwnerCompensationIsJournaledBeforeRollbackAndCapacityRelease() throws IOException {
        String mutation = read("ownership", "OwnerComponentMutationService.java");
        String scheduler = read("ownership", "OwnerMutationScheduler.java");
        String compensation = read("ownership", "OwnerMutationCompensationService.java");

        assertTrue(mutation.contains("return restoreImmediate(store, npcRef, ownerType,"));
        assertTrue(mutation.contains("WriteResult.compensationRequired("));
        assertFalse(mutation.contains("MutationResult applyBuffered("));
        assertTrue(scheduler.contains("compensationService.handleFailedWrite("));
        assertTrue(compensation.contains("if (result.safeToCancel())"));
        int begin = compensation.indexOf("beginCompensationAsync(prepared, result.reason())");
        int derived = compensation.indexOf("mutationService.compensateDerivedImmediate(", begin);
        int source = compensation.indexOf("notifyCompensated(callbacks, profileId, reason", derived);
        int owner = compensation.indexOf("mutationService.compensateOwnerImmediate(", source);
        int close = compensation.indexOf("completeCompensationAsync(prepared, reason)", owner);
        assertTrue(begin >= 0 && derived > begin && source > derived && owner > source && close > owner);
    }

    /** Regression: only pre-durable denial releases; allowed preparation promotes its DB alias. */
    @Test
    void provisionalIdentityTracksDurablePreparationBoundary() throws IOException {
        String snapshot = read("ownership", "OwnerMutationSnapshotResolver.java");
        String scheduler = read("ownership", "OwnerMutationScheduler.java");
        String lifecycle = read("ownership", "OwnerMutationIdentityLifecycle.java");

        assertTrue(snapshot.contains("boolean provisionalIdentity"));
        assertTrue(snapshot.contains("identityResolver.releaseProvisional("));
        assertTrue(scheduler.contains("owner-mutation-identity-unavailable"));
        assertTrue(scheduler.contains("releaseAndDenyBeforePreparation("));
        assertTrue(scheduler.contains("identityLifecycle.promotePrepared(snapshot)"));
        assertTrue(lifecycle.contains("markDurable(snapshot.profileId(), snapshot.npcUuid())"));
        assertFalse(lifecycle.contains("cancelPrepared(")
                && lifecycle.substring(lifecycle.indexOf("cancelPrepared(")).contains(
                "releaseProvisional"
        ));
        assertTrue(scheduler.lines().count() <= 500L);
    }

    /** Regression: a failed destructive continuation leaves APPLYING recovery evidence intact. */
    @Test
    void appliedCallbackFailureQuarantinesInsteadOfCommitting() throws IOException {
        String scheduler = read("ownership", "OwnerMutationScheduler.java");
        int callback = scheduler.indexOf("callbacks.onApplied(");
        int quarantine = scheduler.indexOf(
                "owner_mutation_applied_continuation_failed", callback
        );
        int commit = scheduler.indexOf("companionCoordinator.commitAsync(prepared)", callback);

        assertTrue(callback >= 0);
        assertTrue(quarantine > callback && quarantine < commit);
        assertTrue(scheduler.substring(callback, commit).contains("return;"));
    }

    @Test
    void permanentReleaseOverloadPreservesTrustedRecoveryContext() throws IOException {
        String scheduler = read("ownership", "OwnerMutationScheduler.java");

        assertTrue(scheduler.contains("@Nonnull String durableContextJson"));
        assertTrue(scheduler.contains(
                "Objects.requireNonNull(durableContextJson, \"durableContextJson\"), true"
        ));
    }

    @Test
    void deferredMutationCallbacksReceiveFreshlyResolvedWorldState() throws IOException {
        String scheduler = read("ownership", "OwnerMutationScheduler.java");
        String capture = read("items", "SpawnerCaptureFinalizerService.java");
        String coop = read("items", "ManagedCoopCaptureRuntimeAdapter.java");
        String release = read("items", "CommandOwnerReleaseService.java");

        assertTrue(scheduler.contains("Ref<EntityStore> liveRef = world.getEntityRef(npcUuid)"));
        assertTrue(scheduler.contains("OwnerMutationContext mutationContext = new OwnerMutationContext("));
        assertTrue(scheduler.contains("callbacks.beforeApply(profileId, mutationContext)"));
        assertTrue(scheduler.contains("callbacks.onApplied(")
                && scheduler.contains("profileId, mutationContext"));
        assertTrue(capture.contains("context.store().getComponent("));
        assertTrue(capture.contains("context.npcRef(), NPCEntity.getComponentType()"));
        int cancellation = coop.indexOf("cancelForCapturedParentDurably(");
        int continuation = coop.indexOf(
                "private CompletableFuture<CaptureOutcome> continueCapture(");
        int snapshot = coop.indexOf(
                "captureSnapshotForManagedCoopPersistence(", continuation);
        int attempt = coop.indexOf("CaptureAttempt attempt = buildAttempt(", snapshot);
        int submit = coop.indexOf("captureGateway.coordinate(attempt)", attempt);
        assertTrue(cancellation >= 0 && continuation > cancellation);
        assertTrue(snapshot > continuation && attempt > snapshot && submit > attempt);
        assertTrue(coop.contains("CompletableFuture<CaptureOutcome> coordinate("));
        assertTrue(release.contains("clearTamedAndLinks(context.npcRef(), context.store())"));
    }

    private static String read(String... segments) throws IOException {
        Path path = MAIN;
        for (String segment : segments) {
            path = path.resolve(segment);
        }
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
