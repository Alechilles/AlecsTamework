package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.CapturePolicyConfigView;
import com.alechilles.alecstamework.api.CaptureRequirementSpec;
import com.alechilles.alecstamework.api.SpawnerCaptureMechanicsView;
import com.alechilles.alecstamework.companion.capture.CaptureAttemptFormula;
import com.alechilles.alecstamework.companion.capture.CaptureAttemptResolution;
import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.items.capturepolicy.SpawnerCaptureChanceService;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;
import javax.annotation.Nullable;

/** Freezes one evaluated roll into the canonical durable capture evidence. */
final class SpawnerCaptureResolutionFactory {
    private final ItemFeatureRegistry items;
    private final LongSupplier clock;

    SpawnerCaptureResolutionFactory(
            ItemFeatureRegistry items,
            LongSupplier clock
    ) {
        this.items = Objects.requireNonNull(items, "items");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    CaptureAttemptResolution create(
            CaptureAttemptHandle attempt,
            String sourceItemId,
            String targetRoleId,
            ItemFeatureConfig.CaptureItemMechanics mechanics,
            @Nullable CapturePolicyConfigView policy,
            SpawnerCaptureChanceService.Evaluation evaluation,
            long requirementGeneration
    ) {
        if (attempt == null || mechanics == null || evaluation == null) {
            throw new IllegalArgumentException(
                    "Complete capture resolution inputs are required"
            );
        }
        SpawnerCaptureMechanicsView item = items
                .resolveCaptureForItemId(sourceItemId)
                .orElse(null);
        String configId = item == null
                ? sourceItemId
                : item.configId();
        long configRevision = item == null
                ? items.revision()
                : item.configRevision();
        boolean successful = evaluation.outcome()
                == SpawnerCaptureChanceService.Outcome.SUCCESS;
        return new CaptureAttemptResolution(
                attempt.attemptId(),
                targetRoleId,
                formula(
                        configId,
                        configRevision,
                        mechanics,
                        policy,
                        requirementGeneration
                ),
                mechanics.sourceConsumption(),
                mechanics.successDisposition(),
                successful
                        ? CaptureAttemptResolution.Outcome.SUCCESS
                        : CaptureAttemptResolution.Outcome.FAILED_ROLL,
                evaluation.reason(),
                evaluation.effectiveChance(),
                evaluation.guaranteed(),
                evaluation.missingHealthFraction(),
                evaluation.entropy(),
                successful
                        ? null
                        : failureCooldown(mechanics.failureCooldownMs())
        );
    }

    String itemConfigId(String sourceItemId) {
        return items.resolveCaptureForItemId(sourceItemId)
                .map(SpawnerCaptureMechanicsView::configId)
                .orElse(sourceItemId);
    }

    long nowMs() {
        return clock.getAsLong();
    }

    private CaptureAttemptFormula formula(
            String configId,
            long configRevision,
            ItemFeatureConfig.CaptureItemMechanics mechanics,
            @Nullable CapturePolicyConfigView policy,
            long requirementGeneration
    ) {
        return new CaptureAttemptFormula(
                configId,
                configRevision,
                mechanics.chanceMode(),
                mechanics.power(),
                mechanics.baseChance(),
                mechanics.chancePerPower(),
                mechanics.minimumChance(),
                mechanics.maximumChance(),
                policy == null ? null : policy.configId(),
                policy == null ? 0L : policy.configRevision(),
                policy == null ? 0 : policy.minimumPower(),
                policy == null ? 0.0D : policy.resistance(),
                policy == null ? 1.0D : policy.chanceMultiplier(),
                policy == null ? 0.0D : policy.missingHealthBonus(),
                policy == null ? null : policy.guaranteedAtPower(),
                requirementsHash(
                        policy == null ? List.of() : policy.requirements()
                ),
                requirementGeneration
        );
    }

    private Sha256Hash requirementsHash(
            List<CaptureRequirementSpec> requirements
    ) {
        JsonArray encoded = new JsonArray();
        for (CaptureRequirementSpec requirement : requirements) {
            JsonObject item = new JsonObject();
            item.addProperty("id", requirement.id());
            if (requirement.param() != null) {
                item.addProperty("param", requirement.param());
            }
            JsonArray values = new JsonArray();
            requirement.values().forEach(values::add);
            item.add("values", values);
            if (requirement.jsonPayload() != null) {
                item.addProperty("jsonPayload", requirement.jsonPayload());
            }
            encoded.add(item);
        }
        return Sha256Hash.ofUtf8(encoded.toString());
    }

    @Nullable
    private Long failureCooldown(int durationMs) {
        if (durationMs == 0) {
            return null;
        }
        long now = clock.getAsLong();
        try {
            return Math.addExact(now, durationMs);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }
}
