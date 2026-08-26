package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudController;
import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudSessionContributor;
import com.alechilles.alecstamework.api.commandhud.CommandHudContribution;
import com.alechilles.alecstamework.api.commandhud.CommandHudContributorDescriptor;
import com.alechilles.alecstamework.api.commandhud.CommandHudRegistration;
import com.alechilles.alecstamework.api.commandhud.CommandHudRegistrationResult;
import com.alechilles.alecstamework.api.commandhud.CommandHudRendererDescriptor;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudController;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudSessionContributor;
import com.alechilles.alecstamework.api.internal.CommandHudRegistry;
import com.alechilles.alecstamework.api.commandui.CommandUiValue;
import com.alechilles.alecstamework.damage.SimpleClaimsDamageHytaleFixture.HytaleModuleScope;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.TestEntityComponentStore;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies detached command HUD self-tests and both lifecycle cleanup sinks. */
class CommandHudRegistrationLifecycleTest {
    private static final UUID PLAYER_UUID = UUID.fromString(
            "e839f9f4-cb53-4e9d-a8c4-2ea8d9f8a3a8");

    @Test
    void selfTestUsesRegistryCompositionForBothSurfacesAndCleansUp() throws Exception {
        CommandHudRegistry registry = new CommandHudRegistry();
        String targetRendererId = "selftest:target-renderer";
        String targetContributorId = "selftest:target-contributor";
        String hotswapRendererId = "selftest:hotswap-renderer";
        String hotswapContributorId = "selftest:hotswap-contributor";
        CommandHudRegistration targetRenderer = registerTargetRenderer(
                registry, targetRendererId, targetContributorId);
        CommandHudRegistration targetContributor = registerTargetContributor(
                registry, targetContributorId);
        CommandHudRegistration hotswapRenderer = registerHotswapRenderer(
                registry, hotswapRendererId, hotswapContributorId);
        CommandHudRegistration hotswapContributor = registerHotswapContributor(
                registry, hotswapContributorId);
        try {
            CommandItemFeatureHandler handler = allocate(CommandItemFeatureHandler.class);
            CommandItemFeatureHandler.CommandHudSelfTestResult result =
                    handler.runCommandHudSelfTest(
                            registry, PLAYER_UUID, targetRendererId, targetContributorId,
                            hotswapRendererId, hotswapContributorId);

            assertTrue(result.targetRendererCreated());
            assertTrue(result.targetContributionReady());
            assertTrue(result.targetFocusedRefresh());
            assertTrue(result.targetSessionClosed());
            assertTrue(result.hotswapRendererCreated());
            assertTrue(result.hotswapContributionReady());
            assertTrue(result.hotswapFocusedRefresh());
            assertTrue(result.hotswapSessionClosed());
            assertTrue(registry.diagnostics().sessions().isEmpty());
        } finally {
            close(targetContributor);
            close(targetRenderer);
            close(hotswapContributor);
            close(hotswapRenderer);
            assertFalse(registry.diagnostics().targetRenderers().stream()
                    .anyMatch(value -> targetRendererId.equals(value.rendererId())));
            assertFalse(registry.diagnostics().targetContributors().stream()
                    .anyMatch(value -> targetContributorId.equals(value.contributorId())));
            assertFalse(registry.diagnostics().hotswapRenderers().stream()
                    .anyMatch(value -> hotswapRendererId.equals(value.rendererId())));
            assertFalse(registry.diagnostics().hotswapContributors().stream()
                    .anyMatch(value -> hotswapContributorId.equals(value.contributorId())));
            registry.close();
        }
    }

    @Test
    void playerAndStoreLifecycleSignalsReachBothSurfaceSinksOnce() throws Exception {
        RecordingSink target = new RecordingSink();
        RecordingSink hotswap = new RecordingSink();
        CommandHudDirtySink sink = CommandHudDirtySink.fanOut(target, hotswap);

        try (HytaleModuleScope ignored = HytaleModuleScope.install();
             TestEntityComponentStore store = new TestEntityComponentStore(null)) {
            var reference = store.createReference();
            Player player = allocate(Player.class);
            player.setLegacyUUID(PLAYER_UUID);
            store.put(reference, Player.getComponentType(), player);

            CommandHudPlayerLifecycleSystem playerSystem =
                    new CommandHudPlayerLifecycleSystem(sink);
            playerSystem.onEntityAdded(reference, AddReason.SPAWN, store, null);
            playerSystem.onEntityRemove(reference, RemoveReason.REMOVE, store, null);

            CommandHudStoreLifecycleSystem storeSystem =
                    new CommandHudStoreLifecycleSystem(sink);
            storeSystem.onSystemRemovedFromStore(store);

            assertEquals(List.of(PLAYER_UUID), target.dirtyPlayers);
            assertEquals(List.of(PLAYER_UUID), hotswap.dirtyPlayers);
            assertEquals(List.of(PLAYER_UUID), target.removedPlayers);
            assertEquals(List.of(PLAYER_UUID), hotswap.removedPlayers);
            assertEquals(List.of(store), target.removedStores);
            assertEquals(List.of(store), hotswap.removedStores);
        }
    }

    private static CommandHudRegistration registerTargetRenderer(
            CommandHudRegistry registry, String rendererId, String contributorId) {
        CommandHudRegistrationResult result = registry.registerTargetRenderer(
                rendererId, new CommandHudRendererDescriptor(Set.of(contributorId)), ignored ->
                        new CommandTargetHudController() {
                            @Override
                            public void buildInitial(com.alechilles.alecstamework.api.commandhud.CommandHudOpenContext context,
                                                     com.alechilles.alecstamework.api.commandhud.CommandTargetHudView view,
                                                     UICommandBuilder commands) {
                            }
                        });
        return result.registration();
    }

    private static CommandHudRegistration registerHotswapRenderer(
            CommandHudRegistry registry, String rendererId, String contributorId) {
        CommandHudRegistrationResult result = registry.registerHotswapRenderer(
                rendererId, new CommandHudRendererDescriptor(Set.of(contributorId)), ignored ->
                        new CommandHotswapHudController() {
                            @Override
                            public void buildInitial(com.alechilles.alecstamework.api.commandhud.CommandHudOpenContext context,
                                                     com.alechilles.alecstamework.api.commandhud.CommandHotswapHudView view,
                                                     UICommandBuilder commands) {
                            }
                        });
        return result.registration();
    }

    private static CommandHudRegistration registerTargetContributor(
            CommandHudRegistry registry, String contributorId) {
        CommandHudRegistrationResult result = registry.registerTargetContributor(
                contributorId, new CommandHudContributorDescriptor(Set.of("selftest")), context ->
                        (CommandTargetHudSessionContributor) (base, previous, scope) ->
                                CommandHudContribution.available(context.contributorId(),
                                        Map.of("probe/value", CommandUiValue.of("ready"))));
        return result.registration();
    }

    private static CommandHudRegistration registerHotswapContributor(
            CommandHudRegistry registry, String contributorId) {
        CommandHudRegistrationResult result = registry.registerHotswapContributor(
                contributorId, new CommandHudContributorDescriptor(Set.of("selftest")), context ->
                        (CommandHotswapHudSessionContributor) (base, previous, scope) ->
                                CommandHudContribution.available(context.contributorId(),
                                        Map.of("probe/value", CommandUiValue.of("ready"))));
        return result.registration();
    }

    private static void close(CommandHudRegistration registration) {
        if (registration != null) registration.close();
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(((Unsafe) field.get(null)).allocateInstance(type));
    }

    private static final class RecordingSink implements CommandHudDirtySink {
        private final List<UUID> dirtyPlayers = new ArrayList<>();
        private final List<UUID> removedPlayers = new ArrayList<>();
        private final List<Store<EntityStore>> removedStores = new ArrayList<>();

        @Override
        public void markDirty(UUID playerUuid) {
            dirtyPlayers.add(playerUuid);
        }

        @Override
        public void remove(Store<EntityStore> store, UUID playerUuid) {
            removedPlayers.add(playerUuid);
        }

        @Override
        public void removeStore(Store<EntityStore> store) {
            removedStores.add(store);
        }
    }
}
