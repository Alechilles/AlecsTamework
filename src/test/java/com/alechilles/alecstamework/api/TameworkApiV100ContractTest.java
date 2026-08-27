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

/** Public behavior checks for the stable Tamework 1.0 API surface. */
class TameworkApiV100ContractTest {
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
    void oldImplementationsReceiveAnUnavailableHusbandryOutcomeFacade() {
        TameworkApi legacy = new LegacyApiImplementation();

        assertFalse(legacy.husbandryOutcomes().available());
        assertEquals(
                HusbandryOutcomeModifiers.identity(),
                legacy.husbandryOutcomes().resolve(husbandryContext())
        );
    }

    @Test
    void fullRuntimeAdvertisesCommandHudCapabilitiesUntilClosed() {
        TameworkEventBus events = new TameworkEventBus(null);
        TameworkApiImpl api = newBaseApi(events);
        try {
            assertEquals("1.0.0", api.getApiVersion());
            assertTrue(api.getCapabilities().containsAll(EnumSet.of(
                    TameworkApiCapability.COMMAND_HUD_RENDERERS,
                    TameworkApiCapability.COMMAND_HUD_CONTRIBUTORS)));
            assertTrue(api.commandHud().available());
            assertTrue(api.getCapabilities().contains(
                    TameworkApiCapability.HUSBANDRY_OUTCOMES));
            assertTrue(api.husbandryOutcomes().available());
            api.husbandryOutcomes().register(ignored -> new HusbandryOutcomeModifiers(
                    1.25, 0.2, 0.05, 0.75
            ));
            assertEquals(
                    new HusbandryOutcomeModifiers(1.25, 0.2, 0.05, 0.75),
                    api.husbandryOutcomes().resolve(husbandryContext())
            );
            api.close();
            assertFalse(api.getCapabilities().contains(
                    TameworkApiCapability.COMMAND_HUD_RENDERERS));
            assertFalse(api.getCapabilities().contains(
                    TameworkApiCapability.COMMAND_HUD_CONTRIBUTORS));
            assertFalse(api.commandHud().available());
            assertFalse(api.getCapabilities().contains(
                    TameworkApiCapability.HUSBANDRY_OUTCOMES));
            assertFalse(api.husbandryOutcomes().available());
            assertEquals(
                    HusbandryOutcomeModifiers.identity(),
                    api.husbandryOutcomes().resolve(husbandryContext())
            );
        } finally {
            api.close();
            events.close();
        }
    }

    @Test
    void bondedOnlyRuntimeReportsVersionButFailsClosedForCommandHud() {
        TameworkApi api = new BondedOnlyTameworkApi(BondedCompanionApi.unavailable());

        assertEquals("1.0.0", api.getApiVersion());
        assertFalse(api.getCapabilities().contains(
                TameworkApiCapability.COMMAND_HUD_RENDERERS));
        assertFalse(api.getCapabilities().contains(
                TameworkApiCapability.COMMAND_HUD_CONTRIBUTORS));
        assertFalse(api.commandHud().available());
        assertFalse(api.getCapabilities().contains(
                TameworkApiCapability.HUSBANDRY_OUTCOMES));
        assertFalse(api.husbandryOutcomes().available());
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

    private static HusbandryOutcomeContext husbandryContext() {
        return new HusbandryOutcomeContext(
                HusbandryOutcomeKind.CARE_RESTORATION,
                UUID.fromString("10000000-0000-0000-0000-000000000001"),
                UUID.fromString("20000000-0000-0000-0000-000000000001"),
                "Tamed_Cow",
                "runeteria:husbandry",
                Set.of("runeteria:husbandry"),
                null
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
