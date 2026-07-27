package com.alechilles.alecstamework.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BondedCompanionApiContractTest {
    private static final List<Class<?>> PUBLIC_BONDED_TYPES = List.of(
            BondedCompanionActionContext.class,
            BondedCompanionActionContext.Inventory.class,
            BondedCompanionActionContext.ChargeReceipt.class,
            BondedCompanionActionRequest.class,
            BondedCompanionApi.class,
            BondedCompanionAvailability.class,
            BondedCompanionChangedEvent.class,
            BondedCompanionExtensionData.class,
            BondedCompanionExtensionDataKey.class,
            BondedCompanionExtensionDataUpdate.class,
            BondedCompanionLeaseView.class,
            BondedCompanionPlacement.class,
            BondedCompanionProfileView.class,
            BondedCompanionProvisionRequest.class,
            BondedCompanionResult.class,
            BondedCompanionResultCode.class,
            BondedCompanionStateView.class,
            BondedCompanionReviveQuote.class,
            BondedCompanionReviveRequest.class
    );

    @Test
    void tameworkFallbackReturnsExplicitUnavailableResult() {
        BondedCompanionApi bonded = new FallbackOnlyApi()
                .bondedCompanions();

        BondedCompanionResult<?> result = bonded.list(
                UUID.randomUUID(),
                "hydragon:dragons"
        ).join();

        assertNotNull(bonded);
        assertNotNull(bonded.availability());
        assertFalse(bonded.availability().available());
        assertEquals(BondedCompanionResultCode.UNAVAILABLE, result.code());
        assertFalse(result.successful());
    }

    @Test
    void profileViewDefensivelyCopiesSnapshotPresentationData() {
        Map<String, String> source = new HashMap<>();
        source.put("variant", "ember");
        BondedCompanionProfileView view = new BondedCompanionProfileView(
                "profile-1",
                UUID.randomUUID(),
                "hydragon:dragons",
                "hydragon:dragon",
                "Tamed_Dragon_Fire",
                "Ember",
                "Dragon",
                "Female",
                4L,
                BondedCompanionStateView.STORED,
                true,
                false,
                false,
                source,
                null,
                0L,
                null
        );

        source.put("variant", "ice");

        assertEquals("ember", view.snapshotPresentationData().get("variant"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> view.snapshotPresentationData().put("variant", "storm")
        );
    }

    @Test
    void extensionDataIsProfileKeyedNamespacedAndRevisionFenced() {
        UUID ownerUuid = UUID.randomUUID();
        BondedCompanionExtensionDataKey key =
                new BondedCompanionExtensionDataKey(
                        ownerUuid,
                        "profile-1",
                        "hydragon.combat"
                );
        BondedCompanionExtensionDataUpdate update =
                new BondedCompanionExtensionDataUpdate(
                        "hydragon",
                        "extension-7",
                        key,
                        "{\"stance\":\"guard\"}",
                        7L
                );

        assertEquals(ownerUuid, update.key().ownerUuid());
        assertEquals("profile-1", update.key().profileId());
        assertEquals("hydragon.combat", update.key().namespace());
        assertEquals(7L, update.expectedRevision());
        assertThrows(
                IllegalArgumentException.class,
                () -> new BondedCompanionExtensionDataUpdate(
                        "hydragon",
                        "extension-invalid",
                        key,
                        "{}",
                        -2L
                )
        );
    }

    @Test
    void extensionUpdateDeclaresCallerScopedIdentityAndMissingSentinel() {
        List<String> components = Arrays.stream(
                        BondedCompanionExtensionDataUpdate.class
                                .getRecordComponents()
                )
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();
        boolean missingSentinelDeclared = Arrays.stream(
                        BondedCompanionExtensionDataUpdate.class
                                .getDeclaredFields()
                )
                .anyMatch(field -> field.getName().equals(
                        "MISSING_REVISION"
                ));

        assertEquals(
                List.of(
                        "callerNamespace",
                        "idempotencyKey",
                        "key",
                        "jsonPayload",
                        "expectedRevision"
                ),
                components
        );
        assertEquals(true, missingSentinelDeclared);
    }

    @Test
    void provisionRequestDoesNotRequireAnUnobservableRosterRevision() {
        assertFalse(Arrays.stream(
                        BondedCompanionProvisionRequest.class
                                .getRecordComponents())
                .anyMatch(component -> component.getName().equals(
                        "expectedRosterRevision")));
    }

    @Test
    void publicBondedSignaturesExposeOnlyApiAndJdkTypes() {
        for (Class<?> type : PUBLIC_BONDED_TYPES) {
            Arrays.stream(type.getDeclaredConstructors())
                    .filter(constructor -> java.lang.reflect.Modifier.isPublic(
                            constructor.getModifiers()))
                    .forEach(constructor -> Arrays.stream(
                                    constructor.getGenericParameterTypes())
                            .forEach(parameter -> assertPublicBoundary(
                                    type, parameter)));
            Arrays.stream(type.getDeclaredMethods())
                    .filter(method -> java.lang.reflect.Modifier.isPublic(
                            method.getModifiers()))
                    .forEach(method -> {
                        assertPublicBoundary(type, method.getGenericReturnType());
                        Arrays.stream(method.getGenericParameterTypes())
                                .forEach(parameter -> assertPublicBoundary(
                                        type, parameter));
                    });
        }
    }

    private static void assertPublicBoundary(Class<?> declaring, Type type) {
        if (type instanceof Class<?> value) {
            if (value.isArray()) {
                assertPublicBoundary(declaring, value.getComponentType());
                return;
            }
            String packageName = value.getPackageName();
            boolean allowed = value.isPrimitive()
                    || packageName.startsWith("java.")
                    || packageName.startsWith("javax.")
                    || packageName.equals("com.alechilles.alecstamework.api");
            assertEquals(true, allowed, () -> declaring.getName()
                    + " leaks " + value.getName());
            return;
        }
        if (type instanceof ParameterizedType parameterized) {
            assertPublicBoundary(declaring, parameterized.getRawType());
            Arrays.stream(parameterized.getActualTypeArguments())
                    .forEach(argument -> assertPublicBoundary(
                            declaring, argument));
            return;
        }
        if (type instanceof GenericArrayType array) {
            assertPublicBoundary(declaring, array.getGenericComponentType());
            return;
        }
        if (type instanceof WildcardType wildcard) {
            Arrays.stream(wildcard.getUpperBounds())
                    .forEach(bound -> assertPublicBoundary(declaring, bound));
            Arrays.stream(wildcard.getLowerBounds())
                    .forEach(bound -> assertPublicBoundary(declaring, bound));
            return;
        }
        if (type instanceof TypeVariable<?>) {
            return;
        }
        throw new AssertionError("Unsupported public signature type: " + type);
    }

    private static final class FallbackOnlyApi implements TameworkApi {
        @Override
        public String getApiVersion() {
            return "test";
        }

        @Override
        public EnumSet<TameworkApiCapability> getCapabilities() {
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
}
