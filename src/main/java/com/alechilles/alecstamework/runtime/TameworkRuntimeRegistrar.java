package com.alechilles.alecstamework.runtime;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Preflights and installs active Tamework runtime participants.
 *
 * <p>All active preflight actions run before the first target mutation. A
 * failed preflight therefore leaves the host registry unchanged. The returned
 * handle owns every installed resource.</p>
 */
public final class TameworkRuntimeRegistrar {
    /** Registers the active participants from one frozen context. */
    public TameworkRuntimeHandle register(TameworkRuntimeRegistrationContext context) {
        return register(context, ignored -> { });
    }

    /** Registers active participants and reports each completed installation. */
    public TameworkRuntimeHandle register(
            TameworkRuntimeRegistrationContext context,
            Consumer<TameworkRuntimeRegistrationContext.Participant> installed
    ) {
        TameworkRuntimeRegistrationContext checked = Objects.requireNonNull(
                context,
                "Runtime registration context is required"
        );
        Consumer<TameworkRuntimeRegistrationContext.Participant> observer =
                Objects.requireNonNull(installed, "Registration observer is required");
        for (TameworkRuntimeRegistrationContext.Participant participant : checked.activeParticipants()) {
            try {
                participant.preflight();
            } catch (Exception exception) {
                throw new IllegalStateException(
                        "Runtime participant preflight failed: " + participant.id(),
                        exception
                );
            }
        }

        TameworkRuntimeHandle handle = new TameworkRuntimeHandle();
        try {
            for (TameworkRuntimeRegistrationContext.Participant participant : checked.activeParticipants()) {
                AutoCloseable resource = participant.register(checked.target());
                handle.add(resource);
                observer.accept(participant);
            }
            return handle;
        } catch (Exception exception) {
            try {
                handle.close();
            } catch (RuntimeException closeFailure) {
                exception.addSuppressed(closeFailure);
            }
            throw new IllegalStateException("Runtime participant registration failed", exception);
        }
    }
}
