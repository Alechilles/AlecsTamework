package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudController;
import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudSessionContributor;
import com.alechilles.alecstamework.api.commandhud.CommandHudContribution;
import com.alechilles.alecstamework.api.commandhud.CommandHudContributorId;
import com.alechilles.alecstamework.api.commandhud.CommandHudContributorDescriptor;
import com.alechilles.alecstamework.api.commandhud.CommandHudContributorRequirement;
import com.alechilles.alecstamework.api.commandhud.CommandHudRegistration;
import com.alechilles.alecstamework.api.commandhud.CommandHudRegistrationResult;
import com.alechilles.alecstamework.api.commandhud.CommandHudRendererDescriptor;
import com.alechilles.alecstamework.api.commandhud.CommandHudOpenContext;
import com.alechilles.alecstamework.api.commandhud.CommandHudSurface;
import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudSnapshot;
import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudUpdate;
import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudView;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudController;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudSessionContributor;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudSnapshot;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudUpdate;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudView;
import com.alechilles.alecstamework.api.internal.CommandHudRegistry;
import com.alechilles.alecstamework.api.commandui.CommandUiValue;
import com.alechilles.alecstamework.damage.SimpleClaimsDamageHytaleFixture.HytaleModuleScope;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Ref;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
            CommandHudSelfTestRuntime.CommandHudSelfTestResult result =
                    CommandHudSelfTestRuntime.run(
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

    @Test
    void playerLifecycleClosesBothCompositionsBeforeRegistryClose() throws Exception {
        CommandHudRegistry registry = new CommandHudRegistry();
        AtomicBoolean registryClosed = new AtomicBoolean();
        try (HytaleModuleScope ignored = HytaleModuleScope.install();
             TestEntityComponentStore store = new TestEntityComponentStore(null)) {
            SessionFixture fixture = openSessions(registry, registryClosed);
            fixture.assertFocusedRefreshes();
            SessionCleanupSink sink = new SessionCleanupSink(store, PLAYER_UUID, fixture);
            Ref<EntityStore> reference = store.createReference();
            Player player = allocate(Player.class);
            player.setLegacyUUID(PLAYER_UUID);
            store.put(reference, Player.getComponentType(), player);

            new CommandHudPlayerLifecycleSystem(sink).onEntityRemove(
                    reference, RemoveReason.REMOVE, store, null);

            fixture.assertClosedOnce();
            assertTrue(registry.diagnostics().sessions().isEmpty());
            registryClosed.set(true);
            registry.close();
            fixture.assertClosedOnce();
        } finally {
            registryClosed.set(true);
            registry.close();
        }
    }

    @Test
    void storeLifecycleClosesBothCompositionsBeforeRegistryClose() throws Exception {
        CommandHudRegistry registry = new CommandHudRegistry();
        AtomicBoolean registryClosed = new AtomicBoolean();
        try (HytaleModuleScope ignored = HytaleModuleScope.install();
             TestEntityComponentStore store = new TestEntityComponentStore(null)) {
            SessionFixture fixture = openSessions(registry, registryClosed);
            fixture.assertFocusedRefreshes();
            SessionCleanupSink sink = new SessionCleanupSink(store, PLAYER_UUID, fixture);
            new CommandHudStoreLifecycleSystem(sink).onSystemRemovedFromStore(store);

            fixture.assertClosedOnce();
            assertTrue(registry.diagnostics().sessions().isEmpty());
            registryClosed.set(true);
            registry.close();
            fixture.assertClosedOnce();
        } finally {
            registryClosed.set(true);
            registry.close();
        }
    }

    private static SessionFixture openSessions(
            CommandHudRegistry registry,
            AtomicBoolean registryClosed
    ) {
        LifecycleCounters counters = new LifecycleCounters();
        registerTestComponents(registry, registryClosed, counters);

        CommandHudCompositionResolver resolver = new CommandHudCompositionResolver(registry);
        CommandHudCompositionSession<CommandTargetHudSnapshot, CommandTargetHudView,
                CommandTargetHudUpdate> targetSession = openTargetSession(resolver);
        CommandHudCompositionSession<CommandHotswapHudSnapshot, CommandHotswapHudView,
                CommandHotswapHudUpdate> hotswapSession = openHotswapSession(resolver);
        targetSession.compose(targetSnapshot());
        hotswapSession.compose(hotswapSnapshot());
        assertEquals(2, registry.diagnostics().activeSessionCount());
        return new SessionFixture(targetSession, hotswapSession,
                counters.targetRendererClosed, counters.targetContributorClosed,
                counters.hotswapRendererClosed, counters.hotswapContributorClosed,
                counters.closeOrder);
    }

    private static void registerTestComponents(
            CommandHudRegistry registry,
            AtomicBoolean registryClosed,
            LifecycleCounters counters
    ) {
        if (registerTargetRendererWithCounters(registry, registryClosed, counters) == null
                || registerTargetContributorWithCounters(registry, registryClosed, counters) == null
                || registerHotswapRendererWithCounters(registry, registryClosed, counters) == null
                || registerHotswapContributorWithCounters(registry, registryClosed, counters) == null) {
            throw new AssertionError("HUD test registrations did not open");
        }
    }

    private static CommandHudRegistration registerTargetRendererWithCounters(
            CommandHudRegistry registry,
            AtomicBoolean registryClosed,
            LifecycleCounters counters
    ) {
        return registry.registerTargetRenderer(
                "test:target-renderer",
                new CommandHudRendererDescriptor(Set.of("test:target-contributor")),
                ignored -> new CommandTargetHudController() {
                    @Override
                    public void buildInitial(CommandHudOpenContext context,
                                             CommandTargetHudView view,
                                             UICommandBuilder commands) {
                    }

                    @Override
                    public void close() {
                        assertNotClosed(registryClosed, "target renderer");
                        counters.targetRendererClosed.incrementAndGet();
                        counters.closeOrder.add("target-renderer");
                    }
                }).registration();
    }

    private static CommandHudRegistration registerTargetContributorWithCounters(
            CommandHudRegistry registry,
            AtomicBoolean registryClosed,
            LifecycleCounters counters
    ) {
        return registry.registerTargetContributor(
                "test:target-contributor",
                new CommandHudContributorDescriptor(Set.of("test:target/value")),
                context -> new CommandTargetHudSessionContributor() {
                    @Override
                    public CommandHudContribution compose(CommandTargetHudSnapshot base,
                                                           CommandHudContribution previous,
                                                           com.alechilles.alecstamework.api.commandhud.CommandHudDirtyScope scope) {
                        return CommandHudContribution.available(context.contributorId(),
                                Map.of("test:target/value", CommandUiValue.of("ready")));
                    }

                    @Override
                    public void close() {
                        assertNotClosed(registryClosed, "target contributor");
                        counters.targetContributorClosed.incrementAndGet();
                        counters.closeOrder.add("target-contributor");
                    }
                }).registration();
    }

    private static CommandHudRegistration registerHotswapRendererWithCounters(
            CommandHudRegistry registry,
            AtomicBoolean registryClosed,
            LifecycleCounters counters
    ) {
        return registry.registerHotswapRenderer(
                "test:hotswap-renderer",
                new CommandHudRendererDescriptor(Set.of("test:hotswap-contributor")),
                ignored -> new CommandHotswapHudController() {
                    @Override
                    public void buildInitial(CommandHudOpenContext context,
                                             CommandHotswapHudView view,
                                             UICommandBuilder commands) {
                    }

                    @Override
                    public void close() {
                        assertNotClosed(registryClosed, "hotswap renderer");
                        counters.hotswapRendererClosed.incrementAndGet();
                        counters.closeOrder.add("hotswap-renderer");
                    }
                }).registration();
    }

    private static CommandHudRegistration registerHotswapContributorWithCounters(
            CommandHudRegistry registry,
            AtomicBoolean registryClosed,
            LifecycleCounters counters
    ) {
        return registry.registerHotswapContributor(
                "test:hotswap-contributor",
                new CommandHudContributorDescriptor(Set.of("test:hotswap/value")),
                context -> new CommandHotswapHudSessionContributor() {
                    @Override
                    public CommandHudContribution compose(CommandHotswapHudSnapshot base,
                                                           CommandHudContribution previous,
                                                           com.alechilles.alecstamework.api.commandhud.CommandHudDirtyScope scope) {
                        return CommandHudContribution.available(context.contributorId(),
                                Map.of("test:hotswap/value", CommandUiValue.of("ready")));
                    }

                    @Override
                    public void close() {
                        assertNotClosed(registryClosed, "hotswap contributor");
                        counters.hotswapContributorClosed.incrementAndGet();
                        counters.closeOrder.add("hotswap-contributor");
                    }
                }).registration();
    }

    private static void assertNotClosed(AtomicBoolean registryClosed, String resource) {
        if (registryClosed.get()) {
            throw new AssertionError(resource + " closed after registry");
        }
    }

    private static CommandHudCompositionSession<CommandTargetHudSnapshot,
            CommandTargetHudView, CommandTargetHudUpdate> openTargetSession(
            CommandHudCompositionResolver resolver
    ) {
        CommandHudTargetResolution resolution = resolver.resolveTarget(
                "test:target-renderer",
                List.of(new CommandHudContributorRequirement("test:target-contributor", true)));
        return CommandHudCompositionSession.target(
                new CommandHudOpenContext(PLAYER_UUID, "en-US", "tool", "item",
                        "config", CommandHudSurface.TARGET, "test:target-renderer",
                        PLAYER_UUID, "target", 1L), resolution,
                resolver.diagnostics, resolver.timingWarnings);
    }

    private static CommandHudCompositionSession<CommandHotswapHudSnapshot,
            CommandHotswapHudView, CommandHotswapHudUpdate> openHotswapSession(
            CommandHudCompositionResolver resolver
    ) {
        CommandHudHotswapResolution resolution = resolver.resolveHotswap(
                "test:hotswap-renderer",
                List.of(new CommandHudContributorRequirement("test:hotswap-contributor", true)));
        return CommandHudCompositionSession.hotswap(
                new CommandHudOpenContext(PLAYER_UUID, "en-US", "tool", "item",
                        "config", CommandHudSurface.HOTSWAP, "test:hotswap-renderer",
                        null, null, 2L), resolution,
                resolver.diagnostics, resolver.timingWarnings);
    }

    private static CommandTargetHudSnapshot targetSnapshot() {
        return new CommandTargetHudSnapshot(
                PLAYER_UUID, "target", "test:species", "READY",
                CommandTargetHudSnapshot.Vitals.empty(), CommandTargetHudSnapshot.Cooldowns.empty(),
                null, List.of(), List.of(), null, CommandTargetHudSnapshot.Progression.empty(),
                List.of(), "owner");
    }

    private static CommandHotswapHudSnapshot hotswapSnapshot() {
        CommandHotswapHudSnapshot.Slot slot = new CommandHotswapHudSnapshot.Slot(
                true, "Q", "test/icon.png", "Q");
        return new CommandHotswapHudSnapshot(slot, slot, slot, slot, slot,
                CommandHotswapHudSnapshot.GroupStatus.hidden());
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

    private static final class LifecycleCounters {
        private final AtomicInteger targetRendererClosed = new AtomicInteger();
        private final AtomicInteger targetContributorClosed = new AtomicInteger();
        private final AtomicInteger hotswapRendererClosed = new AtomicInteger();
        private final AtomicInteger hotswapContributorClosed = new AtomicInteger();
        private final List<String> closeOrder = new ArrayList<>();
    }

    private static final class SessionFixture {
        private final CommandHudCompositionSession<CommandTargetHudSnapshot,
                CommandTargetHudView, CommandTargetHudUpdate> targetSession;
        private final CommandHudCompositionSession<CommandHotswapHudSnapshot,
                CommandHotswapHudView, CommandHotswapHudUpdate> hotswapSession;
        private final AtomicInteger targetRendererClosed;
        private final AtomicInteger targetContributorClosed;
        private final AtomicInteger hotswapRendererClosed;
        private final AtomicInteger hotswapContributorClosed;
        private final List<String> closeOrder;

        private SessionFixture(
                CommandHudCompositionSession<CommandTargetHudSnapshot,
                        CommandTargetHudView, CommandTargetHudUpdate> targetSession,
                CommandHudCompositionSession<CommandHotswapHudSnapshot,
                        CommandHotswapHudView, CommandHotswapHudUpdate> hotswapSession,
                AtomicInteger targetRendererClosed,
                AtomicInteger targetContributorClosed,
                AtomicInteger hotswapRendererClosed,
                AtomicInteger hotswapContributorClosed,
                List<String> closeOrder
        ) {
            this.targetSession = targetSession;
            this.hotswapSession = hotswapSession;
            this.targetRendererClosed = targetRendererClosed;
            this.targetContributorClosed = targetContributorClosed;
            this.hotswapRendererClosed = hotswapRendererClosed;
            this.hotswapContributorClosed = hotswapContributorClosed;
            this.closeOrder = closeOrder;
        }

        private void assertFocusedRefreshes() {
            CommandHudContributorId targetId = CommandHudContributorId.of(
                    "test:target-contributor");
            targetSession.markPathsDirty(targetId, Set.of("test:target/value"));
            CommandTargetHudUpdate targetUpdate = targetSession.refresh(targetSnapshot());
            assertNotNull(targetUpdate);
            assertFalse(targetUpdate.fullRefresh());
            assertTrue(targetUpdate.changeSet().pathsFor(targetId)
                    .contains("test:target/value"));

            CommandHudContributorId hotswapId = CommandHudContributorId.of(
                    "test:hotswap-contributor");
            hotswapSession.markPathsDirty(hotswapId, Set.of("test:hotswap/value"));
            CommandHotswapHudUpdate hotswapUpdate = hotswapSession.refresh(hotswapSnapshot());
            assertNotNull(hotswapUpdate);
            assertFalse(hotswapUpdate.fullRefresh());
            assertTrue(hotswapUpdate.changeSet().pathsFor(hotswapId)
                    .contains("test:hotswap/value"));
        }

        private void closeSessions() {
            targetSession.close();
            hotswapSession.close();
        }

        private void assertClosedOnce() {
            assertFalse(targetSession.isOpen());
            assertFalse(hotswapSession.isOpen());
            assertEquals(1, targetRendererClosed.get());
            assertEquals(1, targetContributorClosed.get());
            assertEquals(1, hotswapRendererClosed.get());
            assertEquals(1, hotswapContributorClosed.get());
            assertEquals(List.of(
                    "target-contributor", "target-renderer",
                    "hotswap-contributor", "hotswap-renderer"), closeOrder);
        }
    }

    private static final class SessionCleanupSink implements CommandHudDirtySink {
        private final Store<EntityStore> store;
        private final UUID playerUuid;
        private final SessionFixture fixture;

        private SessionCleanupSink(
                Store<EntityStore> store,
                UUID playerUuid,
                SessionFixture fixture
        ) {
            this.store = store;
            this.playerUuid = playerUuid;
            this.fixture = fixture;
        }

        @Override
        public void markDirty(UUID ignoredPlayerUuid) {
        }

        @Override
        public void remove(Store<EntityStore> store, UUID playerUuid) {
            if (this.store == store && this.playerUuid.equals(playerUuid)) {
                fixture.closeSessions();
            }
        }

        @Override
        public void removeStore(Store<EntityStore> store) {
            if (this.store == store) {
                fixture.closeSessions();
            }
        }
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
