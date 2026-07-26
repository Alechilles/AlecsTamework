package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.persistence.HytaleUuidCompletionDispatcher;
import com.alechilles.alecstamework.ui.TameworkUiMessageService;
import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import java.util.Objects;
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
        HytaleUuidCompletionDispatcher completions =
                new HytaleUuidCompletionDispatcher();
        SpawnerPlayerInventoryService inventory =
                new SpawnerPlayerInventoryService();
        SpawnerEffectService effects = new SpawnerEffectService();
        TameworkUiMessageService messages = new TameworkUiMessageService();
        return new BondedCompanionCaptureFeedbackDispatcher(new Sink() {
            @Override
            public boolean spend(BondedCompanionCaptureIntent intent) {
                if (intent == null) return false;
                return completions.dispatch(
                        intent.worldKey(), intent.actorUuid(),
                        (world, store, actorRef, player) -> {
                            var source = inventory.getHotbarItem(
                                    player, intent.hotbarSlot());
                            if (source == null || source.isEmpty()
                                    || !intent.sourceFingerprint().equals(
                                    SpawnerSourceFingerprint.of(source))) {
                                messages.show(player,
                                        "Your companion was stored, but the capture item changed. Contact an admin.",
                                        NotificationStyle.Warning);
                                return;
                            }
                            var transaction = new SpawnerSourceItemTransaction(
                                    inventory, player, intent.hotbarSlot(), source,
                                    null, "bonded-capture");
                            if (!transaction.consumeOne()) {
                                messages.show(player,
                                        "Your companion was stored, but the capture item could not be finalized. Contact an admin.",
                                        NotificationStyle.Warning);
                                return;
                            }
                            transaction.commit();
                            effects.playPublishedEffect(
                                    world, intent.completionEffect());
                        });
            }

            @Override public void effect(BondedCompanionCaptureIntent intent) {
                // The scheduled exact-item completion emits this once after spend.
            }

            @Override
            public void message(BondedCompanionCaptureIntent intent,
                                String message) {
                if (intent == null) return;
                completions.dispatch(intent.worldKey(), intent.actorUuid(),
                        (world, store, actorRef, player) -> messages.show(
                                player, message, NotificationStyle.Warning));
            }
        });
    }

    /** Finalizes the exact item before emitting the single completion effect. */
    boolean success(@Nullable BondedCompanionCaptureIntent intent) {
        if (!sink.spend(intent)) {
            sink.message(intent, "Your companion was stored, but the capture item "
                    + "could not be finalized. Keep the item unchanged and contact an admin.");
            return false;
        }
        sink.effect(intent);
        return true;
    }

    /** Sends one actionable message and no success presentation. */
    void failure(@Nullable BondedCompanionCaptureIntent intent,
                 @Nonnull BondedCompanionCaptureAuthor.Status status) {
        sink.message(intent, message(status));
    }

    private String message(BondedCompanionCaptureAuthor.Status status) {
        return switch (status) {
            case TARGET_INVALID -> "That companion is no longer available to capture.";
            case CHANCE_FAILED -> "The capture failed. Tranquilize the companion and try again.";
            case TRANQUILIZED_REQUIRED -> "The companion must be tranquilized before capture.";
            case TOOL_ACCESS_REQUIRED -> "Keep the configured bonded roster tool in your inventory.";
            case OWNER_DENIED -> "You do not have permission to capture that companion.";
            case ROLE_DENIED -> "That companion type is not allowed in this bonded roster.";
            case CAPACITY_REJECTED -> "Your bonded roster is full. Store or remove a companion first.";
            case SNAPSHOT_FAILED -> "The companion state could not be read. Try again without moving it.";
            case DATABASE_FAILED -> "The bonded roster could not be saved. Your item and companion were unchanged.";
            default -> "The bonded capture could not be completed. Your item and companion were unchanged.";
        };
    }

    interface Sink {
        boolean spend(@Nullable BondedCompanionCaptureIntent intent);
        void effect(@Nullable BondedCompanionCaptureIntent intent);
        void message(@Nullable BondedCompanionCaptureIntent intent,
                     @Nonnull String message);
    }
}
