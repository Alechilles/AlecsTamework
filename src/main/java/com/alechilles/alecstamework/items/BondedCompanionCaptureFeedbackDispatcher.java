package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.ui.TameworkUiMessageService;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import java.util.Objects;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Emits exactly one terminal player response for a bonded capture attempt. */
public final class BondedCompanionCaptureFeedbackDispatcher {
    private final Sink sink;

    BondedCompanionCaptureFeedbackDispatcher(@Nonnull Sink sink) {
        this.sink = Objects.requireNonNull(sink, "sink");
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

            @Override public void effect(
                    BondedCompanionCaptureIntent intent,
                    CompletionContext context
            ) {
                if (!worldThread(context) || intent == null) {
                    return;
                }
                effects.playPublishedEffect(
                        context.world(), intent.completionEffect());
            }

            @Override
            public void message(
                    BondedCompanionCaptureIntent intent,
                    CompletionContext context,
                    String message
            ) {
                if (worldThread(context)) {
                    messages.show(
                            context.player(), message,
                            NotificationStyle.Warning);
                } else {
                    log(logger, message);
                }
            }
        });
    }

    /** Emits the durable-success effect once, then finalizes the exact item. */
    boolean success(@Nullable BondedCompanionCaptureIntent intent) {
        return success(intent, null);
    }

    boolean success(
            @Nullable BondedCompanionCaptureIntent intent,
            @Nullable CompletionContext context
    ) {
        try {
            sink.effect(intent, context);
        } catch (RuntimeException | LinkageError ignored) {
            // Item finalization and terminal feedback must still resolve.
        }
        boolean spent;
        try {
            spent = sink.spend(intent, context);
        } catch (RuntimeException | LinkageError failure) {
            spent = false;
        }
        if (!spent) {
            safeMessage(intent, context,
                    "Your companion was stored, but the capture item "
                    + "could not be finalized. Keep the item unchanged and contact an admin.");
            return false;
        }
        return true;
    }

    /** Sends one actionable message and no success presentation. */
    void failure(@Nullable BondedCompanionCaptureIntent intent,
                 @Nonnull BondedCompanionCaptureAuthor.Status status) {
        failure(intent, null, status);
    }

    void failure(
            @Nullable BondedCompanionCaptureIntent intent,
            @Nullable CompletionContext context,
            @Nonnull BondedCompanionCaptureAuthor.Status status
    ) {
        safeMessage(intent, context, message(status));
    }

    private void safeMessage(
            BondedCompanionCaptureIntent intent,
            CompletionContext context,
            String message
    ) {
        try {
            sink.message(intent, context, message);
        } catch (RuntimeException | LinkageError ignored) {
            // The production sink logs when a live UI recipient is unavailable.
        }
    }

    private String message(BondedCompanionCaptureAuthor.Status status) {
        return switch (status) {
            case TARGET_INVALID -> "That companion is no longer available to capture.";
            case ADMISSION_DENIED -> "That companion cannot be captured right now. Check range, health, cooldown, and capture requirements.";
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
            case FINALIZATION_FAILED -> "Your companion was stored, but the capture item could not be finalized. Contact an admin.";
            default -> "The bonded capture could not be completed. Your item and companion were unchanged.";
        };
    }

    interface Sink {
        boolean spend(@Nullable BondedCompanionCaptureIntent intent,
                      @Nullable CompletionContext context);
        void effect(@Nullable BondedCompanionCaptureIntent intent,
                    @Nullable CompletionContext context);
        void message(@Nullable BondedCompanionCaptureIntent intent,
                     @Nullable CompletionContext context,
                     @Nonnull String message);
    }

    /** Live context retained only for this synchronous world-thread call. */
    public record CompletionContext(
            @Nonnull World world,
            @Nonnull Player player
    ) {
        public CompletionContext {
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(player, "player");
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

    private static void log(HytaleLogger logger, String message) {
        if (logger != null) logger.at(Level.WARNING).log(message);
    }
}
