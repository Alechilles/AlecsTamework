package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.ui.TameworkUiMessageService;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Emits exactly one terminal player response for a bonded capture attempt. */
public final class BondedCompanionCaptureFeedbackDispatcher {
    private final Sink sink;
    private final Diagnostics diagnostics;

    BondedCompanionCaptureFeedbackDispatcher(@Nonnull Sink sink) {
        this(sink, (intent, message, failure) -> {});
    }

    BondedCompanionCaptureFeedbackDispatcher(
            @Nonnull Sink sink,
            @Nonnull Diagnostics diagnostics
    ) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    /** Creates world-thread item, effect, and player-message feedback. */
    @Nonnull
    public static BondedCompanionCaptureFeedbackDispatcher production() {
        return production(null);
    }

    /** Creates synchronous world-thread completion over the current actor. */
    @Nonnull
    public static BondedCompanionCaptureFeedbackDispatcher production(
            @Nullable HytaleLogger logger
    ) {
        SpawnerPlayerInventoryService inventory =
                new SpawnerPlayerInventoryService();
        SpawnerEffectService effects = new SpawnerEffectService();
        TameworkUiMessageService messages = new TameworkUiMessageService();
        return new BondedCompanionCaptureFeedbackDispatcher(new Sink() {
            @Override
            public boolean spend(
                    BondedCompanionCaptureIntent intent,
                    CompletionContext context
            ) {
                if (!matches(intent, context)) return false;
                var source = inventory.getHotbarItem(
                        context.player(), intent.hotbarSlot());
                if (source == null || source.isEmpty()
                        || !intent.sourceFingerprint().equals(
                        SpawnerSourceFingerprint.of(source))) return false;
                var transaction = new SpawnerSourceItemTransaction(
                        inventory, context.player(), intent.hotbarSlot(), source,
                        logger, "bonded-capture");
                if (!transaction.consumeOne()) return false;
                transaction.commit();
                return true;
            }

            @Override public boolean effect(
                    BondedCompanionCaptureIntent intent,
                    CompletionContext context
            ) {
                if (!worldThread(context) || intent == null) {
                    return false;
                }
                return effects.playPublishedEffect(
                        context.world(), intent.completionEffect());
            }

            @Override
            public boolean message(
                    BondedCompanionCaptureIntent intent,
                    CompletionContext context,
                    String message
            ) {
                if (worldThread(context)) {
                    return messages.show(
                            context.player(), message,
                            NotificationStyle.Warning);
                }
                return false;
            }

            @Override
            public boolean successNotification(
                    BondedCompanionCaptureIntent intent,
                    CompletionContext context
            ) {
                if (!worldThread(context) || intent == null) return false;
                String companion = intent.species() == null || intent.species().isBlank()
                        ? "Companion" : intent.species();
                String roster = context.rosterCommandItemName();
                return messages.show(context.player(), companion + " captured",
                        companion + " has been added to your " + roster,
                        NotificationStyle.Success);
            }
        }, new ThrottledDiagnostics(logger));
    }

    /** Emits the durable-success effect once, then finalizes the exact item. */
    SuccessResult success(@Nullable BondedCompanionCaptureIntent intent) {
        return success(intent, null);
    }

    SuccessResult success(
            @Nullable BondedCompanionCaptureIntent intent,
            @Nullable CompletionContext context
    ) {
        if (intent != null && !hasCompletionOutput(intent)) {
            boolean delivered = safeMessage(intent, context,
                    "Your companion was stored, but no completion effect "
                    + "is configured. The capture item was not spent; "
                    + "contact an admin.");
            return new SuccessResult(SuccessStatus.EFFECT_FAILED, delivered);
        }
        boolean effected;
        try {
            effected = sink.effect(intent, context);
        } catch (RuntimeException | LinkageError failure) {
            effected = false;
        }
        if (!effected) {
            boolean delivered = safeMessage(intent, context,
                    "Your companion was stored, but its completion effect "
                    + "could not be played. The capture item was not spent; "
                    + "contact an admin.");
            return new SuccessResult(SuccessStatus.EFFECT_FAILED, delivered);
        }
        boolean spent;
        try {
            spent = sink.spend(intent, context);
        } catch (RuntimeException | LinkageError failure) {
            spent = false;
        }
        if (!spent) {
            boolean delivered = safeMessage(intent, context,
                    "Your companion was stored, but the capture item "
                    + "could not be finalized. Keep the item unchanged and contact an admin.");
            return new SuccessResult(
                    SuccessStatus.FINALIZATION_FAILED, delivered);
        }
        boolean notified;
        try {
            notified = sink.successNotification(intent, context);
        } catch (RuntimeException | LinkageError failure) {
            notified = false;
        }
        return new SuccessResult(SuccessStatus.APPLIED, notified);
    }

    private boolean hasCompletionOutput(BondedCompanionCaptureIntent intent) {
        var effect = intent.completionEffect();
        return effect != null && (effect.particleSystem() != null
                || effect.soundEvent() != null);
    }

    /** Sends one actionable message and no success presentation. */
    boolean failure(@Nullable BondedCompanionCaptureIntent intent,
                    @Nonnull BondedCompanionCaptureAuthor.Status status) {
        return failure(intent, null, status);
    }

    boolean failure(
            @Nullable BondedCompanionCaptureIntent intent,
            @Nullable CompletionContext context,
            @Nonnull BondedCompanionCaptureAuthor.Status status
    ) {
        return safeMessage(intent, context, message(status, context));
    }

    /** Finalizes a failed roll only when its frozen policy spends resolved attempts. */
    FailedRollResult failedRoll(
            @Nullable BondedCompanionCaptureIntent intent,
            @Nullable CompletionContext context
    ) {
        if (intent == null || intent.attemptEvidence().sourceConsumption()
                != com.alechilles.alecstamework.api.CaptureSourceConsumption
                .RESOLVED_ATTEMPT) {
            return new FailedRollResult(false, failure(intent, context,
                    BondedCompanionCaptureAuthor.Status.CHANCE_FAILED));
        }
        boolean spent;
        try {
            spent = sink.spend(intent, context);
        } catch (RuntimeException | LinkageError failure) {
            spent = false;
        }
        if (!spent) {
            return new FailedRollResult(false, safeMessage(intent, context,
                    "The capture roll failed, but the capture item could not "
                    + "be finalized. Keep it unchanged and contact an admin."));
        }
        return new FailedRollResult(true, failure(intent, context,
                BondedCompanionCaptureAuthor.Status.CHANCE_FAILED));
    }

    private boolean safeMessage(
            BondedCompanionCaptureIntent intent,
            CompletionContext context,
            String message
    ) {
        try {
            if (sink.message(intent, context, message)) return true;
            diagnostics.feedbackUnavailable(intent, message, null);
        } catch (RuntimeException | LinkageError failure) {
            diagnostics.feedbackUnavailable(intent, message, failure);
        }
        return false;
    }

    private String message(BondedCompanionCaptureAuthor.Status status,
                           CompletionContext context) {
        if (status == BondedCompanionCaptureAuthor.Status.POWER_TOO_LOW) {
            return LocalizedText.format(context == null ? null : context.player(),
                    "tamework.ui.notifications.capture.powerTooLow",
                    context == null ? "capture item" : context.captureSourceName(),
                    context == null ? "target" : context.captureTargetName(),
                    context == null ? 0 : context.requiredPower());
        }
        return switch (status) {
            case TARGET_INVALID -> "That companion is no longer available to capture.";
            case ADMISSION_DENIED -> "That companion cannot be captured right now. Check range, health, cooldown, and capture requirements.";
            case POWER_TOO_LOW -> throw new IllegalStateException("handled above");
            case COOLDOWN_ACTIVE -> "Your capture item is still recharging.";
            case TARGET_NOT_TAMED -> "That companion must be tamed before it can be captured.";
            case TARGET_ALREADY_TAMED -> "That capture item can only capture a wild companion.";
            case HEALTH_TOO_HIGH -> "That companion has too much health to capture.";
            case OUT_OF_RANGE -> "Move closer to that companion before capturing it.";
            case CHANCE_FAILED -> "The capture failed. Tranquilize the companion and try again.";
            case TRANQUILIZED_REQUIRED -> "The companion must be tranquilized before capture.";
            case TOOL_ACCESS_REQUIRED -> "Keep the configured bonded roster tool in your inventory.";
            case OWNER_DENIED -> "You do not have permission to capture that companion.";
            case ROLE_DENIED -> "That companion type is not allowed in this bonded roster.";
            case CAPACITY_REJECTED -> "Your bonded roster is full. Store or remove a companion first.";
            case POLICY_UNAVAILABLE -> "The bonded roster policy is unavailable. Reload configuration and try again.";
            case SNAPSHOT_FAILED -> "The companion state could not be read. Try again without moving it.";
            case DATABASE_FAILED -> "The bonded roster could not be saved. Your item and companion were unchanged.";
            case REPLAYED -> "That companion is already stored in your bonded roster; no item was spent.";
            case EFFECT_FAILED -> "Your companion was stored, but its completion effect could not be played. The capture item was not spent; contact an admin.";
            case FINALIZATION_FAILED -> "Your companion was stored, but the capture item could not be finalized. Contact an admin.";
            default -> "The bonded capture could not be completed. Your item and companion were unchanged.";
        };
    }

    interface Sink {
        boolean spend(@Nullable BondedCompanionCaptureIntent intent,
                      @Nullable CompletionContext context);
        boolean effect(@Nullable BondedCompanionCaptureIntent intent,
                       @Nullable CompletionContext context);
        boolean message(@Nullable BondedCompanionCaptureIntent intent,
                        @Nullable CompletionContext context,
                        @Nonnull String message);

        default boolean successNotification(@Nullable BondedCompanionCaptureIntent intent,
                                            @Nullable CompletionContext context) {
            return true;
        }
    }

    enum SuccessStatus { APPLIED, EFFECT_FAILED, FINALIZATION_FAILED }

    record FailedRollResult(boolean spent, boolean feedbackDelivered) {}

    record SuccessResult(
            @Nonnull SuccessStatus status,
            boolean feedbackDelivered
    ) {
        SuccessResult { Objects.requireNonNull(status, "status"); }
    }

    @FunctionalInterface
    interface Diagnostics {
        void feedbackUnavailable(
                @Nullable BondedCompanionCaptureIntent intent,
                @Nonnull String message,
                @Nullable Throwable failure
        );
    }

    /** Live context retained only for this synchronous world-thread call. */
    public record CompletionContext(
            @Nonnull World world,
            @Nonnull Player player,
            @Nonnull String rosterCommandItemName,
            @Nonnull String captureSourceName,
            @Nonnull String captureTargetName,
            int capturePower,
            int requiredPower
    ) {
        public CompletionContext {
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(player, "player");
            rosterCommandItemName = rosterCommandItemName == null
                    || rosterCommandItemName.isBlank() ? "command roster"
                    : rosterCommandItemName;
            captureSourceName = captureSourceName == null || captureSourceName.isBlank()
                    ? "capture item" : captureSourceName;
            captureTargetName = captureTargetName == null || captureTargetName.isBlank()
                    ? "target" : captureTargetName;
        }

        public CompletionContext(@Nonnull World world, @Nonnull Player player) {
            this(world, player, "command roster", "capture item", "target", 0, 0);
        }

        CompletionContext withPowerRequirement(
                @Nullable SpawnerCaptureRollService.Resolution roll, int power) {
            return roll == null ? this : new CompletionContext(world, player,
                    rosterCommandItemName, captureSourceName, captureTargetName,
                    power, roll.minimumPower());
        }
    }

    private static boolean matches(
            BondedCompanionCaptureIntent intent,
            CompletionContext context
    ) {
        return worldThread(context) && intent != null
                && intent.worldKey().equals(context.world().getName())
                && intent.actorUuid().equals(context.player().getUuid());
    }

    private static boolean worldThread(CompletionContext context) {
        return context != null && context.player().getWorld() == context.world()
                && context.world().isAlive() && context.world().isInThread();
    }

    /** Throttles admin-facing delivery warnings across repeated unavailable UI. */
    static final class ThrottledDiagnostics implements Diagnostics {
        private static final long INTERVAL_MS = TimeUnit.SECONDS.toMillis(10L);
        private final LongSupplier clock;
        private final WarningSink warnings;
        private final AtomicLong nextWarningAtMs = new AtomicLong();

        private ThrottledDiagnostics(@Nullable HytaleLogger logger) {
            this(System::currentTimeMillis, (message, failure) -> {
                if (logger == null) return;
                var entry = logger.at(Level.WARNING);
                if (failure != null) entry = entry.withCause(failure);
                entry.log(message);
            });
        }

        ThrottledDiagnostics(
                @Nonnull LongSupplier clock,
                @Nonnull WarningSink warnings
        ) {
            this.clock = Objects.requireNonNull(clock, "clock");
            this.warnings = Objects.requireNonNull(warnings, "warnings");
        }

        @Override
        public void feedbackUnavailable(
                BondedCompanionCaptureIntent intent,
                String message,
                Throwable failure
        ) {
            if (!claim(clock.getAsLong())) return;
            try {
                warnings.warn(
                        "Bonded capture player feedback unavailable (actor="
                        + (intent == null ? null : intent.actorUuid())
                        + ", roster="
                        + (intent == null ? null : intent.rosterId())
                        + ", world="
                        + (intent == null ? null : intent.worldKey())
                        + "): " + message,
                        failure);
            } catch (RuntimeException | LinkageError ignored) {
                // Diagnostics must not change the durable operation outcome.
            }
        }

        private boolean claim(long nowMs) {
            long next = nextWarningAtMs.get();
            while (nowMs >= next) {
                if (nextWarningAtMs.compareAndSet(
                        next, nowMs + INTERVAL_MS)) return true;
                next = nextWarningAtMs.get();
            }
            return false;
        }
    }

    @FunctionalInterface
    interface WarningSink {
        void warn(@Nonnull String message, @Nullable Throwable failure);
    }
}
