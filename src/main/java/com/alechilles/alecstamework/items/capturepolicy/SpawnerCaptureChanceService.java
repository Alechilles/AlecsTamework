package com.alechilles.alecstamework.items.capturepolicy;

import com.alechilles.alecstamework.api.CaptureChanceMode;
import com.alechilles.alecstamework.api.CapturePolicyConfigView;
import com.alechilles.alecstamework.api.CaptureRequirementContext;
import com.alechilles.alecstamework.api.CaptureRequirementDecision;
import com.alechilles.alecstamework.api.CaptureRequirementSpec;
import com.alechilles.alecstamework.api.internal.CaptureRequirementRuntime;
import com.alechilles.alecstamework.config.ItemFeatureConfig;
import java.util.List;
import java.util.Objects;
import java.util.function.DoubleSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Side-effect-free chance/requirement evaluator. Durable exactly-once orchestration owns when this is called. */
public final class SpawnerCaptureChanceService {
    private final CaptureRequirementRuntime requirements;

    public SpawnerCaptureChanceService(@Nonnull CaptureRequirementRuntime requirements) {
        this.requirements = Objects.requireNonNull(requirements, "requirements");
    }

    public Evaluation evaluate(@Nonnull ItemFeatureConfig.CaptureItemMechanics item,
                               @Nullable CapturePolicyConfigView policy,
                               double currentHealth,
                               double maximumHealth,
                               @Nonnull CaptureRequirementContext context,
                               long expectedRequirementGeneration,
                               @Nonnull DoubleSupplier randomProvider) {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(randomProvider, "randomProvider");

        if (item.chanceMode() == CaptureChanceMode.GUARANTEED) {
            return Evaluation.success(1.0D, true, 0.0D, "capture-guaranteed-item");
        }
        if (!Double.isFinite(currentHealth) || !Double.isFinite(maximumHealth) || maximumHealth <= 0.0D) {
            return Evaluation.denied("capture-health-invalid");
        }

        int minimumPower = policy == null ? 0 : policy.minimumPower();
        if (item.power() < minimumPower) return Evaluation.denied("capture-power-below-minimum");

        List<CaptureRequirementSpec> configured = policy == null ? List.of() : policy.requirements();
        for (CaptureRequirementSpec requirement : configured) {
            CaptureRequirementDecision decision = requirements.evaluateCaptureRequirement(
                    requirement, context, expectedRequirementGeneration
            );
            if (!decision.allowed()) return Evaluation.denied(decision.reason());
        }

        double missingHealthFraction = clamp(1.0D - currentHealth / maximumHealth, 0.0D, 1.0D);
        if (policy != null && policy.guaranteedAtPower() != null
                && item.power() >= policy.guaranteedAtPower()) {
            return Evaluation.success(1.0D, true, missingHealthFraction, "capture-guaranteed-power");
        }

        int powerDelta = Math.max(0, item.power() - minimumPower);
        double resistance = policy == null ? 0.0D : policy.resistance();
        double multiplier = policy == null ? 1.0D : policy.chanceMultiplier();
        double healthBonus = policy == null ? 0.0D : policy.missingHealthBonus();
        double rawChance = (item.baseChance()
                + powerDelta * item.chancePerPower()
                + healthBonus * missingHealthFraction
                - resistance) * multiplier;
        double effectiveChance = clamp(rawChance, item.minimumChance(), item.maximumChance());

        if (effectiveChance <= 0.0D) {
            return Evaluation.failure(0.0D, false, missingHealthFraction, null, "capture-zero-chance");
        }
        if (effectiveChance >= 1.0D) {
            return Evaluation.success(1.0D, true, missingHealthFraction, "capture-certain-chance");
        }
        final double entropy;
        try {
            entropy = randomProvider.getAsDouble();
        } catch (Throwable failure) {
            return Evaluation.denied("capture-random-provider-failed");
        }
        if (!Double.isFinite(entropy) || entropy < 0.0D || entropy >= 1.0D) {
            return Evaluation.denied("capture-random-provider-invalid");
        }
        return entropy < effectiveChance
                ? Evaluation.success(effectiveChance, false, missingHealthFraction, entropy, "capture-probability-success")
                : Evaluation.failure(effectiveChance, false, missingHealthFraction, entropy, "capture-probability-failure");
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public record Evaluation(@Nonnull Outcome outcome,
                             @Nonnull String reason,
                             double effectiveChance,
                             boolean guaranteed,
                             double missingHealthFraction,
                             @Nullable Double entropy) {
        public Evaluation {
            Objects.requireNonNull(outcome, "outcome");
            reason = Objects.requireNonNull(reason, "reason").trim();
            if (reason.isEmpty()) throw new IllegalArgumentException("reason is required.");
        }

        private static Evaluation success(double chance, boolean guaranteed, double missing, String reason) {
            return new Evaluation(Outcome.SUCCESS, reason, chance, guaranteed, missing, null);
        }
        private static Evaluation success(double chance, boolean guaranteed, double missing, Double entropy, String reason) {
            return new Evaluation(Outcome.SUCCESS, reason, chance, guaranteed, missing, entropy);
        }
        private static Evaluation failure(double chance, boolean guaranteed, double missing, Double entropy, String reason) {
            return new Evaluation(Outcome.FAILED_ROLL, reason, chance, guaranteed, missing, entropy);
        }
        private static Evaluation denied(String reason) {
            return new Evaluation(Outcome.DENIED, reason, 0.0D, false, 0.0D, null);
        }
    }

    public enum Outcome {
        SUCCESS,
        FAILED_ROLL,
        DENIED
    }
}
