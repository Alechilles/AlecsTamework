package com.alechilles.alecstamework.integration.claims;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Resolves one lifecycle-aware, immutable claim policy context per top-level operation.
 * Provider probes are invoked outside locks and are never eagerly run for unselected providers.
 */
public final class ClaimProviderRegistry implements AutoCloseable {
    private static final String QUESTLINES_ID = "questlines-claims";
    private static final String SIMPLE_CLAIMS_ID = "simpleclaims";

    private final AtomicReference<ClaimProviderProbe> questLinesProbe;
    private final AtomicReference<ClaimProviderProbe> simpleClaimsProbe;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public ClaimProviderRegistry(@Nonnull ClaimProviderProbe questLinesProbe,
                                 @Nonnull ClaimProviderProbe simpleClaimsProbe) {
        requireProvider(questLinesProbe, ClaimIntegrationProvider.QUESTLINES_CLAIMS);
        requireProvider(simpleClaimsProbe, ClaimIntegrationProvider.SIMPLE_CLAIMS);
        this.questLinesProbe = new AtomicReference<>(questLinesProbe);
        this.simpleClaimsProbe = new AtomicReference<>(simpleClaimsProbe);
    }

    @Nonnull
    public ClaimPolicyContext resolve(@Nullable String configuredProvider, long settingsRevision) {
        return resolveRequest(ClaimProviderRequest.fromConfigValue(configuredProvider), settingsRevision);
    }

    @Nonnull
    public ClaimPolicyContext resolveProvider(@Nullable ClaimIntegrationProvider provider, long settingsRevision) {
        return resolveRequest(ClaimProviderRequest.forProvider(provider), settingsRevision);
    }

    @Nonnull
    public ClaimPolicyContext resolveRequest(@Nonnull ClaimProviderRequest request, long settingsRevision) {
        if (request == null) {
            return invalidContext(null, settingsRevision, "Claim provider request is missing.");
        }
        if (closed.get()) {
            return closedContext(request, settingsRevision);
        }
        if (!request.valid()) {
            return invalidContext(
                    request.configuredValue(),
                    settingsRevision,
                    "Unknown claim provider '" + request.displayValue() + "'."
            );
        }

        ClaimIntegrationProvider requested = request.provider();
        if (requested == ClaimIntegrationProvider.OFF) {
            return new ClaimPolicyContext(
                    request.configuredValue(),
                    requested,
                    ClaimIntegrationProvider.OFF,
                    "off",
                    ClaimProviderState.OFF,
                    Set.of(),
                    null,
                    "Claim integration is off.",
                    ClaimProviderGeneration.NONE,
                    settingsRevision,
                    null
            );
        }
        if (requested == ClaimIntegrationProvider.QUESTLINES_CLAIMS) {
            return resolveConcrete(
                    request,
                    questLinesProbe.get(),
                    ClaimIntegrationProvider.QUESTLINES_CLAIMS,
                    QUESTLINES_ID,
                    settingsRevision
            );
        }
        if (requested == ClaimIntegrationProvider.SIMPLE_CLAIMS) {
            return resolveConcrete(
                    request,
                    simpleClaimsProbe.get(),
                    ClaimIntegrationProvider.SIMPLE_CLAIMS,
                    SIMPLE_CLAIMS_ID,
                    settingsRevision
            );
        }

        ClaimProviderProbeResult questLines = probe(
                questLinesProbe.get(),
                ClaimIntegrationProvider.QUESTLINES_CLAIMS,
                QUESTLINES_ID
        );
        if (closed.get()) {
            return closedContext(request, settingsRevision);
        }
        if (questLines.state() != ClaimProviderState.ABSENT
                && questLines.state() != ClaimProviderState.DISABLED) {
            return contextFrom(request, questLines, settingsRevision);
        }

        ClaimProviderProbeResult simpleClaims = probe(
                simpleClaimsProbe.get(),
                ClaimIntegrationProvider.SIMPLE_CLAIMS,
                SIMPLE_CLAIMS_ID
        );
        if (closed.get()) {
            return closedContext(request, settingsRevision);
        }
        return contextFrom(request, simpleClaims, settingsRevision);
    }

    @Nonnull
    private ClaimPolicyContext resolveConcrete(@Nonnull ClaimProviderRequest request,
                                               @Nullable ClaimProviderProbe providerProbe,
                                               @Nonnull ClaimIntegrationProvider provider,
                                               @Nonnull String providerId,
                                               long settingsRevision) {
        ClaimProviderProbeResult result = probe(providerProbe, provider, providerId);
        return closed.get()
                ? closedContext(request, settingsRevision)
                : contextFrom(request, result, settingsRevision);
    }

    /**
     * Releases cached reflected contracts after a settings save. This does not probe providers.
     */
    public void onSettingsChanged() {
        invalidate(questLinesProbe.get());
        invalidate(simpleClaimsProbe.get());
    }

    /**
     * Releases one provider's cached contract after start, stop, unload, or reload.
     */
    public void onPluginLifecycleChanged(@Nullable ClaimIntegrationProvider provider) {
        if (provider == ClaimIntegrationProvider.QUESTLINES_CLAIMS) {
            invalidate(questLinesProbe.get());
        } else if (provider == ClaimIntegrationProvider.SIMPLE_CLAIMS) {
            invalidate(simpleClaimsProbe.get());
        } else {
            onSettingsChanged();
        }
    }

    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        ClaimProviderProbe questLines = questLinesProbe.getAndSet(null);
        ClaimProviderProbe simpleClaims = simpleClaimsProbe.getAndSet(null);
        closeQuietly(questLines);
        closeQuietly(simpleClaims);
    }

    private static void requireProvider(ClaimProviderProbe probe, ClaimIntegrationProvider expected) {
        if (probe == null) {
            throw new IllegalArgumentException(expected + " probe is required.");
        }
        if (probe.provider() != expected) {
            throw new IllegalArgumentException("Expected " + expected + " probe but got " + probe.provider() + ".");
        }
    }

    @Nonnull
    private ClaimProviderProbeResult probe(@Nullable ClaimProviderProbe probe,
                                           @Nonnull ClaimIntegrationProvider expected,
                                           @Nonnull String expectedId) {
        if (probe == null || closed.get()) {
            return errorResult(expected, expectedId, "Claim provider registry is shut down.");
        }
        try {
            ClaimProviderProbeResult result = probe.probe();
            if (result == null) {
                return errorResult(expected, expectedId, "Claim provider probe returned no result.");
            }
            if (result.provider() != expected) {
                return errorResult(expected, expectedId, "Claim provider probe returned the wrong provider.");
            }
            return result;
        } catch (RuntimeException | LinkageError exception) {
            return errorResult(
                    expected,
                    expectedId,
                    "Claim provider probe failed: " + exception.getClass().getSimpleName()
            );
        }
    }

    @Nonnull
    private static ClaimProviderProbeResult errorResult(@Nonnull ClaimIntegrationProvider provider,
                                                        @Nonnull String providerId,
                                                        @Nonnull String reason) {
        return ClaimProviderProbeResult.unavailable(
                provider,
                providerId,
                ClaimProviderState.ERROR,
                null,
                reason,
                ClaimProviderGeneration.NONE
        );
    }

    @Nonnull
    private static ClaimPolicyContext contextFrom(@Nonnull ClaimProviderRequest request,
                                                  @Nonnull ClaimProviderProbeResult result,
                                                  long settingsRevision) {
        return new ClaimPolicyContext(
                request.configuredValue(),
                request.provider(),
                result.provider(),
                result.providerId(),
                result.state(),
                result.capabilities(),
                result.pluginVersion(),
                result.reason(),
                result.generation(),
                settingsRevision,
                result.bridge()
        );
    }

    @Nonnull
    private static ClaimPolicyContext invalidContext(@Nullable String requestedValue,
                                                     long settingsRevision,
                                                     @Nonnull String reason) {
        return new ClaimPolicyContext(
                requestedValue,
                null,
                null,
                "invalid",
                ClaimProviderState.INVALID,
                Set.of(),
                null,
                reason,
                ClaimProviderGeneration.NONE,
                settingsRevision,
                null
        );
    }

    @Nonnull
    private static ClaimPolicyContext closedContext(@Nonnull ClaimProviderRequest request,
                                                    long settingsRevision) {
        return new ClaimPolicyContext(
                request.configuredValue(),
                request.provider(),
                null,
                "registry",
                ClaimProviderState.ERROR,
                Set.of(),
                null,
                "Claim provider registry is shut down.",
                ClaimProviderGeneration.NONE,
                settingsRevision,
                null
        );
    }

    private static void invalidate(@Nullable ClaimProviderProbe probe) {
        if (probe == null) {
            return;
        }
        try {
            probe.invalidate();
        } catch (RuntimeException | LinkageError ignored) {
            // A stale optional provider must not make settings or lifecycle handling fail.
        }
    }

    private static void closeQuietly(@Nullable ClaimProviderProbe probe) {
        if (probe == null) {
            return;
        }
        try {
            probe.close();
        } catch (RuntimeException | LinkageError ignored) {
            // Shutdown must continue so the other provider can release its references too.
        }
    }
}
