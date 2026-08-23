package com.alechilles.alecstamework.api.commandui;

import java.util.Optional;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Capability-gated registration surface for command-item menu providers.
 *
 * <p>The facade is unavailable on API implementations that do not host the
 * command UI provider lifecycle. Callers can therefore probe the capability
 * without checking a concrete Tamework implementation.</p>
 */
public interface CommandUiApi {
    /**
     * Returns whether provider registration is active for this API instance.
     */
    boolean available();

    /**
     * Registers one provider under a normalized namespaced identifier.
     *
     * <p>A successful result owns an idempotent registration handle. A later
     * registration with the same identifier returns {@code CONFLICT} and does
     * not replace the live provider.</p>
     */
    @Nonnull
    CommandUiProviderRegistrationResult register(
            @Nullable String providerId,
            @Nullable CommandUiProvider provider
    );

    /** Convenience overload for callers that already parsed an identifier. */
    @Nonnull
    default CommandUiProviderRegistrationResult register(
            @Nullable CommandUiProviderId providerId,
            @Nullable CommandUiProvider provider
    ) {
        return register(providerId == null ? null : providerId.value(), provider);
    }

    /**
     * Finds the currently registered provider for one identifier.
     *
     * <p>Malformed or absent identifiers resolve to an empty result. Lookup
     * does not expose a provider after its registration has closed.</p>
     */
    @Nonnull
    Optional<CommandUiProvider> find(@Nullable String providerId);

    /** Convenience overload for callers that already parsed an identifier. */
    @Nonnull
    default Optional<CommandUiProvider> find(@Nullable CommandUiProviderId providerId) {
        return find(providerId == null ? null : providerId.value());
    }

    /** Lists the normalized identifiers that are live at the time of the call. */
    @Nonnull
    default Set<CommandUiProviderId> listProviderIds() {
        return Set.of();
    }

    /** Returns the stable fail-closed adapter for legacy and degraded APIs. */
    @Nonnull
    static CommandUiApi unavailable() {
        return UnavailableHolder.INSTANCE;
    }

    /** Holder avoids allocating an unavailable adapter for every API call. */
    final class UnavailableHolder {
        private static final CommandUiApi INSTANCE = new CommandUiApi() {
            @Override
            public boolean available() {
                return false;
            }

            @Override
            public CommandUiProviderRegistrationResult register(
                    String providerId,
                    CommandUiProvider provider
            ) {
                return CommandUiProviderRegistrationResult.unavailable(providerId);
            }

            @Override
            public Optional<CommandUiProvider> find(String providerId) {
                return Optional.empty();
            }
        };

        private UnavailableHolder() {
        }
    }
}
