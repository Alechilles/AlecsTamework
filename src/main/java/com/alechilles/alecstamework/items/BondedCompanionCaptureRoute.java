package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.bonded.BondedCompanionRosterRegistry;
import com.alechilles.alecstamework.items.capturepolicy.SpawnerCaptureChanceService;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nullable;

/** Owns the bonded-only admission, roll, intent, and terminal-feedback route. */
final class BondedCompanionCaptureRoute {
    @Nullable private final BondedCompanionCaptureAuthor author;
    @Nullable private final BondedCompanionRosterRegistry rosters;
    private final BondedCompanionCaptureAdmissionService admission;
    private final SpawnerCaptureRollService rolls;
    private final SpawnerCaptureIntentFactory intents;

    BondedCompanionCaptureRoute(
            BondedCompanionCaptureAuthor author,
            BondedCompanionRosterRegistry rosters,
            BondedCompanionCaptureAdmissionService admission,
            SpawnerCaptureRollService rolls,
            SpawnerCaptureIntentFactory intents
    ) {
        this.author = author;
        this.rosters = rosters;
        this.admission = admission;
        this.rolls = rolls;
        this.intents = intents;
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
        if (!sourceEligible || targetRef == null || !targetRef.isValid()) {
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
        if (assessed.denial() != null) {
            author.reject(assessed.denial(), completion);
            return true;
        }
        var roll = rolls.evaluate(player, targetRef, source, config, attempt);
        if (roll == null || roll.evaluation().outcome()
                == SpawnerCaptureChanceService.Outcome.DENIED) {
            author.reject(BondedCompanionCaptureAuthor.Status.ADMISSION_DENIED,
                    completion);
            return true;
        }
        return finish(player, targetRef, source, config, attempt, roll,
                assessed, particleOverride, completion);
    }

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
                rosters.snapshot().revision(),
                this.admission.hasToolAccess(player, config),
                this.admission.isTranquilized(player, targetRef),
                admission.ownerAllowed(), admission.roleAllowed(),
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
