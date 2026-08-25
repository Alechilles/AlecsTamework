package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandui.CommandUiActionHandle;
import com.alechilles.alecstamework.api.commandui.CommandUiActionRequest;
import com.alechilles.alecstamework.api.commandui.CommandUiActionResult;
import com.alechilles.alecstamework.api.commandui.CommandUiActionStatus;
import com.alechilles.alecstamework.api.commandui.CommandUiActionView;
import com.alechilles.alecstamework.api.commandui.CommandUiCloseReason;
import com.alechilles.alecstamework.api.commandui.CommandUiContribution;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorAction;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorId;
import com.alechilles.alecstamework.api.commandui.CommandUiCustomFlowView;
import com.alechilles.alecstamework.api.commandui.CommandUiDirtyScope;
import com.alechilles.alecstamework.api.commandui.CommandUiGroupFlowView;
import com.alechilles.alecstamework.api.commandui.CommandUiPanelState;
import com.alechilles.alecstamework.api.commandui.CommandUiSnapshot;
import com.alechilles.alecstamework.api.commandui.CommandUiSessionContributor;
import com.alechilles.alecstamework.api.commandui.CommandUiValue;
import com.alechilles.alecstamework.api.internal.CommandUiContributorRegistry;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Production behavior tests for contributor custom-flow lifecycle state. */
class CommandUiContributorFlowManagerTest {
    private static final CommandUiContributorId CONTRIBUTOR =
            CommandUiContributorId.of("runeteria:flow_test");
    private static final CommandUiContributorId OTHER =
            CommandUiContributorId.of("runeteria:other_flow");

    @Test
    void openBindsCurrentFlowActionsAndDoesNotTrustSuppliedHandles() {
        AtomicReference<CommandUiCustomFlowView> next = new AtomicReference<>();
        Fixture fixture = fixture(next);

        assertNull(fixture.initialFlowHandle);

        CommandUiActionResult result = fixture.session.invoke(fixture.openHandle)
                .toCompletableFuture().join();

        assertEquals(CommandUiActionStatus.ACCEPTED, result.status());
        CommandUiCustomFlowView opened = custom(result);
        CommandUiActionView action = opened.actions().get(
                CONTRIBUTOR.value() + "/next");
        assertNotNull(action);
        assertNotEquals("contributor-supplied", action.handle().token());
        assertEquals(CommandUiActionStatus.APPLIED,
                fixture.session.invoke(action.handle())
                        .toCompletableFuture().join().status());
        assertEquals(1, fixture.nextCalls.get());
        fixture.session.close(CommandUiCloseReason.DISMISSED);
        assertFalse(fixture.session.contributorFlowActive());
    }

    @Test
    void replaceRetiresOldHandlesAndRejectsOlderRevisions() {
        AtomicReference<CommandUiCustomFlowView> next = new AtomicReference<>();
        Fixture fixture = fixture(next);

        CommandUiCustomFlowView first = custom(fixture.session.invoke(
                fixture.openHandle).toCompletableFuture().join());
        CommandUiActionHandle oldHandle = first.actions().get(
                CONTRIBUTOR.value() + "/next").handle();
        next.set(flow(2L, "replacement"));
        CommandUiCustomFlowView replacement = custom(fixture.session.invoke(
                oldHandle).toCompletableFuture().join());

        assertEquals(2L, replacement.revision());
        assertNotEquals(oldHandle, replacement.actions().get(
                CONTRIBUTOR.value() + "/next").handle());
        assertEquals(CommandUiActionStatus.STALE,
                fixture.session.invoke(oldHandle).toCompletableFuture().join().status());

        next.set(flow(1L, "older"));
        CommandUiActionResult stale = fixture.session.invoke(replacement.actions()
                        .get(CONTRIBUTOR.value() + "/next").handle())
                .toCompletableFuture().join();
        assertEquals(CommandUiActionStatus.STALE, stale.status());
        fixture.session.close();
    }

    @Test
    void openRejectsAnUnknownFlowActionId() {
        AtomicReference<CommandUiCustomFlowView> next = new AtomicReference<>();
        CommandUiCustomFlowView unknown = new CommandUiCustomFlowView(
                UUID.randomUUID(), "runeteria:flow_test/checklist",
                CONTRIBUTOR, 1L, 1L, 1L, Map.of(),
                Map.of(CONTRIBUTOR.value() + "/unknown",
                        supplied("unknown")));
        Fixture fixture = fixture(next, unknown);

        CommandUiActionResult result = fixture.session.invoke(
                fixture.openHandle).toCompletableFuture().join();

        assertEquals(CommandUiActionStatus.FAILED, result.status());
        assertFalse(fixture.session.contributorFlowActive());
        fixture.session.close();
    }

    @Test
    void updateWithSameRevisionKeepsStableManagedHandle() {
        AtomicReference<CommandUiCustomFlowView> next = new AtomicReference<>();
        Fixture fixture = fixture(next);
        CommandUiCustomFlowView first = custom(fixture.session.invoke(
                fixture.openHandle).toCompletableFuture().join());
        CommandUiActionHandle stable = first.actions().get(
                CONTRIBUTOR.value() + "/next").handle();

        next.set(flow(1L, "updated"));
        CommandUiCustomFlowView updated = custom(fixture.session.invoke(stable)
                .toCompletableFuture().join());

        assertEquals(stable, updated.actions().get(
                CONTRIBUTOR.value() + "/next").handle());
        assertEquals("updated", updated.data().get("label").stringValue());
        fixture.session.close();
    }

    @Test
    void actionSurfaceChangeRequiresARevisionOrActionGenerationChange() {
        AtomicReference<CommandUiCustomFlowView> next = new AtomicReference<>();
        Fixture fixture = fixture(next);
        CommandUiCustomFlowView opened = custom(fixture.session.invoke(
                fixture.openHandle).toCompletableFuture().join());
        CommandUiActionHandle stable = opened.actions().get(
                CONTRIBUTOR.value() + "/next").handle();
        next.set(new CommandUiCustomFlowView(opened.flowInstanceId(),
                opened.flowType(), CONTRIBUTOR, 1L, 1L, 1L,
                Map.of(), Map.of(CONTRIBUTOR.value() + "/next",
                supplied("next"))));

        CommandUiActionResult result = fixture.session.invoke(stable)
                .toCompletableFuture().join();

        assertEquals(CommandUiActionStatus.CONFLICT, result.status());
        assertEquals(CommandUiActionStatus.CONFLICT,
                fixture.session.invoke(stable).toCompletableFuture().join()
                        .status());
        assertEquals(2, fixture.nextCalls.get());
        fixture.session.close();
    }

    @Test
    void closeRetiresFlowHandlesWithoutRefreshingTheMainSnapshot() {
        AtomicReference<CommandUiCustomFlowView> next = new AtomicReference<>();
        Fixture fixture = fixture(next);
        CommandUiCustomFlowView opened = custom(fixture.session.invoke(
                fixture.openHandle).toCompletableFuture().join());
        CommandUiActionHandle flowHandle = opened.actions().get(
                CONTRIBUTOR.value() + "/close").handle();

        CommandUiActionResult closed = fixture.session.invoke(flowHandle)
                .toCompletableFuture().join();

        assertEquals(CommandUiActionStatus.APPLIED, closed.status());
        assertNull(closed.flowView());
        assertEquals(0, fixture.refreshes.get());
        assertEquals(CommandUiActionStatus.STALE,
                fixture.session.invoke(flowHandle).toCompletableFuture().join().status());
        fixture.session.close();
    }

    @Test
    void managedSnapshotRefreshWithoutFlowViewReturnsToMain() {
        AtomicReference<CommandUiCustomFlowView> next = new AtomicReference<>();
        Fixture fixture = fixture(next);
        CommandUiCustomFlowView opened = custom(fixture.session.invoke(
                fixture.openHandle).toCompletableFuture().join());
        CommandUiActionHandle refreshHandle = opened.actions().get(
                CONTRIBUTOR.value() + "/refresh").handle();

        CommandUiActionResult result = fixture.session.invoke(refreshHandle)
                .toCompletableFuture().join();

        assertEquals(CommandUiActionStatus.APPLIED, result.status());
        assertFalse(fixture.session.contributorFlowActive());
        assertEquals(1, fixture.refreshes.get());
        assertEquals(CommandUiActionStatus.STALE,
                fixture.session.invoke(refreshHandle)
                        .toCompletableFuture().join().status());
        fixture.session.close();
    }

    @Test
    void contributorUnregisterRetiresActiveFlowState() {
        AtomicReference<CommandUiCustomFlowView> next = new AtomicReference<>();
        Fixture fixture = fixture(next);
        CommandUiCustomFlowView opened = custom(fixture.session.invoke(
                fixture.openHandle).toCompletableFuture().join());
        CommandUiActionHandle flowHandle = opened.actions().get(
                CONTRIBUTOR.value() + "/next").handle();

        fixture.registration.close();

        assertFalse(fixture.session.contributorFlowActive());
        assertEquals(CommandUiActionStatus.STALE,
                fixture.session.invoke(flowHandle).toCompletableFuture().join().status());
        fixture.session.close();
    }

    @Test
    void confirmedUpdateReturnsFreshManagedHandles() {
        AtomicReference<CommandUiCustomFlowView> next = new AtomicReference<>();
        Fixture fixture = fixture(next);
        CommandUiCustomFlowView opened = custom(fixture.session.invoke(
                fixture.openHandle).toCompletableFuture().join());
        CommandUiActionHandle initiating = opened.actions().get(
                CONTRIBUTOR.value() + "/confirm").handle();
        CommandUiActionResult prompt = fixture.session.invoke(initiating)
                .toCompletableFuture().join();

        CommandUiCustomFlowView updated = custom(fixture.session.invoke(
                prompt.confirmationHandle()).toCompletableFuture().join());
        CommandUiActionHandle fresh = updated.actions().get(
                CONTRIBUTOR.value() + "/confirm").handle();

        assertNotEquals(initiating, fresh);
        assertEquals(CommandUiActionStatus.STALE,
                fixture.session.invoke(initiating)
                        .toCompletableFuture().join().status());
        assertEquals(CommandUiActionStatus.CONFIRMATION_REQUIRED,
                fixture.session.invoke(fresh)
                        .toCompletableFuture().join().status());
        fixture.session.close();
    }

    @Test
    void builtInFlowReplacementReleasesCustomOwnership() {
        AtomicReference<CommandUiCustomFlowView> next = new AtomicReference<>();
        Fixture fixture = fixture(next);
        CommandUiCustomFlowView opened = custom(fixture.session.invoke(
                fixture.openHandle).toCompletableFuture().join());
        CommandUiActionHandle oldCustom = opened.actions().get(
                CONTRIBUTOR.value() + "/next").handle();

        fixture.session.beginManagedFlow();
        CommandUiActionHandle builtIn = fixture.session.issueManaged(
                CommandUiActionGateway.Route.GENERIC,
                new CommandUiAction("BUILT_IN", null, null, false),
                ignored -> true,
                (action, input) -> completed(CommandUiActionResult.applied()),
                CommandUiActionGateway.InputPolicy.NONE, 0, false);
        fixture.registration.close();

        assertFalse(fixture.session.contributorFlowActive());
        assertEquals(CommandUiActionStatus.STALE,
                fixture.session.invoke(oldCustom)
                        .toCompletableFuture().join().status());
        assertEquals(CommandUiActionStatus.APPLIED,
                fixture.session.invoke(builtIn)
                        .toCompletableFuture().join().status());
        fixture.session.close();
    }

    @Test
    void contributorCannotClaimAnotherContributorsFlow() {
        CommandUiContributorRegistry registry =
                new CommandUiContributorRegistry();
        var firstRegistration = registry.register(CONTRIBUTOR.value(),
                ignored -> emptyContributor(CONTRIBUTOR));
        var otherRegistration = registry.register(OTHER.value(),
                ignored -> emptyContributor(OTHER));
        long firstGeneration = firstRegistration.registration().generation();
        long otherGeneration = otherRegistration.registration().generation();
        CommandUiContributorAction otherNext = action("next", context ->
                completed(CommandUiActionResult.applied()));
        CommandUiCustomFlowView claimed = new CommandUiCustomFlowView(
                UUID.randomUUID(), "runeteria:other_flow/checklist", OTHER,
                otherGeneration, 1L, 1L, Map.of(),
                Map.of(OTHER.value() + "/next", supplied("next")));
        CommandUiContributorAction maliciousOpen = action("open", context ->
                completed(CommandUiActionResult.openFlow(claimed)));
        List<CommandUiContributorActionBinding> bindings = List.of(
                new CommandUiContributorActionBinding(CONTRIBUTOR,
                        firstGeneration, maliciousOpen,
                        CommandUiContributorAction.Scope.PAGE, null),
                new CommandUiContributorActionBinding(OTHER,
                        otherGeneration, otherNext,
                        CommandUiContributorAction.Scope.FLOW, null));
        UUID sessionId = UUID.randomUUID();
        CommandUiSnapshot base = new CommandUiSnapshot(sessionId, 1L, 1L,
                null, List.of(), List.of(), new CommandUiPanelState("linked"))
                .withContributions(Map.of(
                        CONTRIBUTOR, CommandUiContribution.withActions(
                                CONTRIBUTOR, Map.of(), Map.of(),
                                Map.of("open", maliciousOpen), Map.of(),
                                Map.of(), Map.of()),
                        OTHER, CommandUiContribution.withActions(
                                OTHER, Map.of(), Map.of(), Map.of(), Map.of(),
                                Map.of(), Map.of("next", otherNext))));
        CommandUiSessionFactory.CreatedSession created =
                new CommandUiSessionFactory(new CommandUiActionGateway(),
                        new CommandSelectionPageService(
                                null, null, null, null, null))
                        .createMixed(sessionId, base, 1L,
                                CommandUiWorldDispatcher.direct(), () -> { },
                                ignored -> { }, null, List.of(), List.of(),
                                UUID.randomUUID(), "config.test", bindings,
                                registry, () -> true);
        CommandUiSnapshot exposed = created.contributorState()
                .attachInitial(base);
        CommandUiActionHandle maliciousHandle = exposed
                .contribution(CONTRIBUTOR).pageActions()
                .get(CONTRIBUTOR.value() + "/open").handle();

        CommandUiActionResult result = created.session()
                .invoke(maliciousHandle).toCompletableFuture().join();

        assertEquals(CommandUiActionStatus.CONFLICT, result.status());
        assertFalse(created.session().contributorFlowActive());
        created.session().close();
    }

    @Test
    void contributorCannotPresentABuiltInManagedFlow() {
        AtomicReference<CommandUiCustomFlowView> next = new AtomicReference<>();
        CommandUiGroupFlowView fabricated = new CommandUiGroupFlowView(
                null, List.of(), supplied("fake"), Map.of());
        Fixture fixture = fixture(next,
                CommandUiActionResult.presented(fabricated));

        CommandUiActionResult result = fixture.session.invoke(
                fixture.openHandle).toCompletableFuture().join();

        assertEquals(CommandUiActionStatus.CONFLICT, result.status());
        assertFalse(fixture.session.contributorFlowActive());
        fixture.session.close();
    }

    private static Fixture fixture(AtomicReference<CommandUiCustomFlowView> next) {
        return fixture(next, flow(1L, "opened"));
    }

    private static Fixture fixture(
            AtomicReference<CommandUiCustomFlowView> next,
            CommandUiCustomFlowView openingFlow
    ) {
        return fixture(next, CommandUiActionResult.openFlow(openingFlow));
    }

    private static Fixture fixture(
            AtomicReference<CommandUiCustomFlowView> next,
            CommandUiActionResult openingResult
    ) {
        CommandUiContributorRegistry registry = new CommandUiContributorRegistry();
        var registrationResult = registry.register(CONTRIBUTOR.value(),
                ignored -> new CommandUiSessionContributor() {
                    @Override
                    public CommandUiContribution compose(
                            CommandUiSnapshot base,
                            CommandUiContribution previous,
                            CommandUiDirtyScope scope) {
                        return CommandUiContribution.ready(CONTRIBUTOR,
                                Map.of(), Map.of());
                    }
                });
        long contributorGeneration = registrationResult.registration().generation();
        UUID sessionId = UUID.randomUUID();
        java.util.concurrent.atomic.AtomicInteger nextCalls =
                new java.util.concurrent.atomic.AtomicInteger();
        CommandUiContributorAction open = action("open", context ->
                completed(openingResult));
        CommandUiContributorAction nextAction = action("next", context -> {
            nextCalls.updateAndGet(value -> value + 1);
            CommandUiCustomFlowView flow = next.get();
            return completed(flow.revision() == 1L
                    ? CommandUiActionResult.updateFlow(flow)
                    : CommandUiActionResult.replaceFlow(flow));
        });
        CommandUiContributorAction close = action("close", context ->
                completed(CommandUiActionResult.closeFlow()));
        CommandUiContributorAction refresh = action("refresh", context ->
                completed(CommandUiActionResult.applied()));
        CommandUiContributorAction confirm = action("confirm", true, context ->
                completed(CommandUiActionResult.updateFlow(
                        flow(1L, "confirmed"))));
        List<CommandUiContributorActionBinding> bindings = List.of(
                binding(open, contributorGeneration,
                        CommandUiContributorAction.Scope.PAGE),
                binding(nextAction, contributorGeneration),
                binding(close, contributorGeneration),
                binding(refresh, contributorGeneration),
                binding(confirm, contributorGeneration));
        CommandUiSnapshot base = new CommandUiSnapshot(sessionId, 1L, 1L,
                null, List.of(), List.of(), new CommandUiPanelState("linked"))
                .withContributions(Map.of(CONTRIBUTOR,
                        CommandUiContribution.withActions(CONTRIBUTOR,
                                Map.of(), Map.of(), Map.of("open", open),
                                Map.of(), Map.of(),
                                Map.of("next", nextAction, "close", close,
                                        "refresh", refresh,
                                        "confirm", confirm))));
        java.util.concurrent.atomic.AtomicInteger refreshes =
                new java.util.concurrent.atomic.AtomicInteger();
        CommandUiSessionFactory.CreatedSession created =
                new CommandUiSessionFactory(new CommandUiActionGateway(),
                        new CommandSelectionPageService(null, null, null, null, null))
                        .createMixed(sessionId, base, 3L,
                                CommandUiWorldDispatcher.direct(),
                                refreshes::incrementAndGet, ignored -> { }, null,
                                List.of(), List.of(),
                                UUID.randomUUID(), "config.test", bindings,
                                registry, () -> true);
        CommandUiSnapshot exposed = created.contributorState().attachInitial(base);
        next.compareAndSet(null, flow(2L, "replacement"));
        CommandUiActionHandle openHandle = exposed.contribution(CONTRIBUTOR)
                .pageActions().get(CONTRIBUTOR.value() + "/open").handle();
        CommandUiActionHandle initialFlowHandle = exposed
                .contribution(CONTRIBUTOR).flowActions()
                .get(CONTRIBUTOR.value() + "/next").handle();
        return new Fixture(created.session(), openHandle, registrationResult.registration(),
                nextCalls, refreshes, initialFlowHandle);
    }

    private static CommandUiContributorAction action(
            String id,
            com.alechilles.alecstamework.api.commandui.CommandUiContributorActionHandler handler
    ) {
        return action(id, false, handler);
    }

    private static CommandUiContributorAction action(
            String id,
            boolean confirmation,
            com.alechilles.alecstamework.api.commandui.CommandUiContributorActionHandler handler
    ) {
        return new CommandUiContributorAction(id, id.toUpperCase(), id,
                CommandUiContributorAction.InputPolicy.NONE, confirmation,
                handler);
    }

    private static CommandUiContributorActionBinding binding(
            CommandUiContributorAction action,
            long generation
    ) {
        return binding(action, generation,
                CommandUiContributorAction.Scope.FLOW);
    }

    private static CommandUiContributorActionBinding binding(
            CommandUiContributorAction action,
            long generation,
            CommandUiContributorAction.Scope scope
    ) {
        return new CommandUiContributorActionBinding(CONTRIBUTOR, generation,
                action, scope, null);
    }

    private static CommandUiCustomFlowView flow(long revision, String label) {
        return new CommandUiCustomFlowView(UUID.fromString(
                "733d4f63-5d3a-40d5-8d2d-bd6f2c5c0c8a"),
                "runeteria:flow_test/checklist", CONTRIBUTOR, 1L,
                revision, 1L,
                Map.of("label", CommandUiValue.string(label)),
                Map.of(CONTRIBUTOR.value() + "/next", supplied("next"),
                        CONTRIBUTOR.value() + "/close", supplied("close"),
                        CONTRIBUTOR.value() + "/refresh",
                        supplied("refresh"),
                        CONTRIBUTOR.value() + "/confirm",
                        supplied("confirm")));
    }

    private static CommandUiActionView supplied(String label) {
        return new CommandUiActionView(label.toUpperCase(), label, true, null,
                false, new CommandUiActionHandle("contributor-supplied"));
    }

    private static CommandUiCustomFlowView custom(CommandUiActionResult result) {
        assertNotNull(result.flowView());
        return (CommandUiCustomFlowView) result.flowView();
    }

    private static CompletableFuture<CommandUiActionResult> completed(
            CommandUiActionResult result) {
        return CompletableFuture.completedFuture(result);
    }

    private static CommandUiSessionContributor emptyContributor(
            CommandUiContributorId id
    ) {
        return new CommandUiSessionContributor() {
            @Override
            public CommandUiContribution compose(
                    CommandUiSnapshot base,
                    CommandUiContribution previous,
                    CommandUiDirtyScope scope
            ) {
                return CommandUiContribution.ready(id, Map.of(), Map.of());
            }
        };
    }

    private record Fixture(
            CommandUiSessionImpl session,
            CommandUiActionHandle openHandle,
            com.alechilles.alecstamework.api.commandui.CommandUiRegistration registration,
            java.util.concurrent.atomic.AtomicInteger nextCalls,
            java.util.concurrent.atomic.AtomicInteger refreshes,
            CommandUiActionHandle initialFlowHandle
    ) {
    }
}
