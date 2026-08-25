package com.alechilles.alecstamework.api;

import com.alechilles.alecstamework.api.commandhud.CommandHudApi;
import com.alechilles.alecstamework.api.commandhud.CommandHudRegistrationResult;
import com.alechilles.alecstamework.api.internal.BondedOnlyTameworkApi;
import com.alechilles.alecstamework.api.internal.InteractionExtensionRegistry;
import com.alechilles.alecstamework.api.internal.TameworkApiImpl;
import com.alechilles.alecstamework.api.internal.TameworkEventBus;
import com.alechilles.alecstamework.api.internal.TraitEffectRegistry;
import com.alechilles.alecstamework.damage.SimpleClaimsTamedDamagePolicy;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Public behavior checks for the experimental Tamework 0.12 API surface. */
class TameworkApiV012ContractTest {
    @Test
    void oldImplementationsReceiveAnUnavailableCommandHudFacade() {
        TameworkApi legacy = new LegacyApiImplementation();

        assertFalse(legacy.commandHud().available());
        assertEquals(CommandHudRegistrationResult.Status.UNAVAILABLE,
                legacy.commandHud().registerTargetRenderer("example:target", ignored -> null)
                        .status());
        assertEquals(CommandHudRegistrationResult.Status.UNAVAILABLE,
                legacy.commandHud().registerHotswapRenderer("example:hotswap", ignored -> null)
                        .status());
        assertEquals(CommandHudRegistrationResult.Status.UNAVAILABLE,
                legacy.commandHud().registerTargetContributor(
                                "example:target-data", ignored -> null).status());
        assertEquals(CommandHudRegistrationResult.Status.UNAVAILABLE,
                legacy.commandHud().registerHotswapContributor(
                                "example:hotswap-data", ignored -> null).status());
    }

    @Test
    void fullRuntimeAdvertisesCommandHudCapabilitiesUntilClosed() {
        TameworkEventBus events = new TameworkEventBus(null);
        try (TameworkApiImpl api = newBaseApi(events)) {
            assertEquals("0.12.0", api.getApiVersion());
            assertTrue(api.getCapabilities().containsAll(EnumSet.of(
                    TameworkApiCapability.COMMAND_HUD_RENDERERS,
                    TameworkApiCapability.COMMAND_HUD_CONTRIBUTORS)));
            assertTrue(api.commandHud().available());
        } finally {
            events.close();
        }
    }

    @Test
    void bondedOnlyRuntimeReportsVersionButFailsClosedForCommandHud() {
        TameworkApi api = new BondedOnlyTameworkApi(BondedCompanionApi.unavailable());

        assertEquals("0.12.0", api.getApiVersion());
        assertFalse(api.getCapabilities().contains(
                TameworkApiCapability.COMMAND_HUD_RENDERERS));
        assertFalse(api.getCapabilities().contains(
                TameworkApiCapability.COMMAND_HUD_CONTRIBUTORS));
        assertFalse(api.commandHud().available());
    }

    private static TameworkApiImpl newBaseApi(TameworkEventBus events) {
        return new TameworkApiImpl(
                new EmptyProfiles(),
                new EmptyProfileData(),
                new EmptyDiagnostics(),
                events,
                null,
                new InteractionExtensionRegistry(null),
                new TraitEffectRegistry(null, null),
                new SimpleClaimsTamedDamagePolicy()
        );
    }

    private static final class LegacyApiImplementation implements TameworkApi {
        @Override public String getApiVersion() { return "0.11.0"; }
        @Override public EnumSet<TameworkApiCapability> getCapabilities() {
            return EnumSet.noneOf(TameworkApiCapability.class);
        }
        @Override public NpcProfilesApi profiles() { return null; }
        @Override public CommandLinksApi commandLinks() { return null; }
        @Override public ProgressionApi progression() { return null; }
        @Override public PolicyApi policies() { return null; }
        @Override public InteractionExtensionApi interactionExtensions() { return null; }
        @Override public TraitEffectApi traitEffects() { return null; }
        @Override public ProfileDataApi profileData() { return null; }
        @Override public TameworkEventsApi events() { return null; }
        @Override public TameworkConfigReadApi configs() { return null; }
        @Override public DiagnosticsApi diagnostics() { return null; }
    }

    private static final class EmptyProfiles implements NpcProfilesApi {
        @Override public Optional<String> resolveProfileId(UUID npcUuid) {
            return Optional.empty();
        }
        @Override public Optional<NpcProfileView> getByProfileId(String profileId) {
            return Optional.empty();
        }
        @Override public Optional<NpcProfileView> getByNpcUuid(UUID npcUuid) {
            return Optional.empty();
        }
        @Override public Optional<String> getActiveSnapshot(
                String profileId, String snapshotType) { return Optional.empty(); }
        @Override public Set<String> listActiveSnapshotTypes(String profileId) {
            return Set.of();
        }
    }

    private static final class EmptyProfileData implements ProfileDataApi {
        @Override public Optional<String> get(
                String profileId, String namespace, String key) {
            return Optional.empty();
        }
        @Override public Map<String, String> list(
                String profileId, String namespace) { return Map.of(); }
        @Override public boolean put(
                String profileId, String namespace, String key, String jsonPayload) {
            return false;
        }
        @Override public boolean delete(
                String profileId, String namespace, String key) { return false; }
    }

    private static final class EmptyDiagnostics implements DiagnosticsApi {
        @Override public PersistenceDiagnosticsView getPersistenceDiagnostics() {
            return new PersistenceDiagnosticsView(
                    "test", 0L, 0L, 0L, 0L,
                    new PersistenceDiagnosticsView.QueueMetricsView(
                            0, 0, 0, 0L, 0L, 0L, 0L,
                            0.0, 0.0, 0.0, null, 0L),
                    new PersistenceDiagnosticsView.HealthView(
                            "UNAVAILABLE", null, 0L));
        }
    }
}
