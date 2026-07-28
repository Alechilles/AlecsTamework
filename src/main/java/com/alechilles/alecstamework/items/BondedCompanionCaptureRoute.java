package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.bonded.BondedCompanionRosterRegistry;
import com.alechilles.alecstamework.items.capturepolicy.SpawnerCaptureChanceService;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/** Owns the bonded-only admission, roll, intent, and terminal-feedback route. */
final class BondedCompanionCaptureRoute {
    @Nullable private final BondedCompanionCaptureAuthor author;
    @Nullable private final BondedCompanionRosterRegistry rosters;
    private final BondedCompanionCaptureAdmissionService admission;
    private final SpawnerCaptureRollService rolls;
    private final SpawnerCaptureIntentFactory intents;
    private final BondedCompanionCaptureReplayRoute replays;
    private final BondedCompanionCaptureReplayIntentFactory replayIntents;

    BondedCompanionCaptureRoute(
            BondedCompanionCaptureAuthor author,
            BondedCompanionRosterRegistry rosters,
            BondedCompanionCaptureAdmissionService admission,
            SpawnerCaptureRollService rolls,
            SpawnerCaptureIntentFactory intents,
            BondedCompanionCaptureReplayIntentFactory replayIntents
    ) {
        this.author = author;
        this.rosters = rosters;
        this.admission = admission;
        this.rolls = rolls;
        this.intents = intents;
        this.replays = author == null
                ? null : new BondedCompanionCaptureReplayRoute(author);
        this.replayIntents = replayIntents;
    }

    boolean capture(
            Player player, Ref<EntityStore> targetRef, ItemStack source,
            ItemFeatureConfig config, CaptureAttemptHandle attempt,
            boolean sourceEligible, @Nullable String particleOverride
    ) {
        if (author == null || player == null || player.getWorld() == null) {
            return false;
        }
        var completion = completion(player);
        if (targetRef == null || !targetRef.isValid()) {
            author.reject(BondedCompanionCaptureAuthor.Status.ADMISSION_DENIED,
                    completion);
            return true;
        }
        var replayRequest = replayIntents == null ? null
                : replayIntents.request(player, targetRef, source, config);
        if (replayRequest != null && replays != null) {
            var resumed = replays.resume(
                    replayRequest,
                    evidence -> replayIntents.intent(
                            replayRequest, attempt, evidence),
                    completion);
            if (resumed.handled()) return true;
        }
        if (!sourceEligible) {
            author.reject(BondedCompanionCaptureAuthor.Status.ADMISSION_DENIED,
                    completion);
            return true;
        }
        var assessed = admission.assess(player, targetRef, source, config);
        if (assessed == null) {
            author.reject(BondedCompanionCaptureAuthor.Status.POLICY_UNAVAILABLE,
                    completion);
            return true;
        }
        RouteDecision decision = resolveAdmission(
                assessed,
                () -> rolls.evaluate(
                        player, targetRef, source, config, attempt));
        if (decision.denial() != null) {
            author.reject(decision.denial(), completion);
            return true;
        }
        var roll = decision.roll();
        return finish(player, targetRef, source, config, attempt, roll,
                assessed, particleOverride, completion);
    }

    /** Resolves deterministic denials before sampling the chance boundary. */
    static RouteDecision resolveAdmission(
            SpawnerCapturePolicyService.BondedAdmissionEvidence admission,
            Supplier<SpawnerCaptureRollService.Resolution> roll
    ) {
        if (admission.denial() != null) {
            return new RouteDecision(admission.denial(), null);
        }
        if (!admission.ownerAllowed()) {
            return new RouteDecision(
                    BondedCompanionCaptureAuthor.Status.OWNER_DENIED, null);
        }
        if (!admission.roleAllowed()) {
            return new RouteDecision(
                    BondedCompanionCaptureAuthor.Status.ROLE_DENIED, null);
        }
        var resolved = roll.get();
        if (resolved == null || resolved.evaluation().outcome()
                == SpawnerCaptureChanceService.Outcome.DENIED) {
            return new RouteDecision(
                    BondedCompanionCaptureAuthor.Status.ADMISSION_DENIED, null);
        }
        return new RouteDecision(null, resolved);
    }

    record RouteDecision(
            @Nullable BondedCompanionCaptureAuthor.Status denial,
            @Nullable SpawnerCaptureRollService.Resolution roll
    ) {}

    private boolean finish(
            Player player, Ref<EntityStore> targetRef, ItemStack source,
            ItemFeatureConfig config, CaptureAttemptHandle attempt,
            SpawnerCaptureRollService.Resolution roll,
            SpawnerCapturePolicyService.BondedAdmissionEvidence admission,
            @Nullable String particleOverride,
            BondedCompanionCaptureFeedbackDispatcher.CompletionContext completion
    ) {
        if (rosters == null) return false;
        BondedCompanionCaptureIntent intent = intents.createBonded(
                player, targetRef, source, config, attempt, roll,
                admission.rosterRevision(),
                this.admission.hasToolAccess(player, config),
                this.admission.isTranquilized(player, targetRef),
                admission.ownerAllowed(), admission.roleAllowed(),
                admission.roleId(), admission.familyId(),
                particleOverride);
        if (intent == null) {
            author.reject(BondedCompanionCaptureAuthor.Status.ADMISSION_DENIED,
                    completion);
            return true;
        }
        author.capture(intent, completion);
        return true;
    }

    private BondedCompanionCaptureFeedbackDispatcher.CompletionContext completion(
            Player player
    ) {
        return new BondedCompanionCaptureFeedbackDispatcher.CompletionContext(
                player.getWorld(), player);
    }
}
