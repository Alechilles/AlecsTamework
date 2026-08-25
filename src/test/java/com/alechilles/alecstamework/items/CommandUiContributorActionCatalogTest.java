package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandui.CommandUiActionHandle;
import com.alechilles.alecstamework.api.commandui.CommandUiActionResult;
import com.alechilles.alecstamework.api.commandui.CommandUiActionStatus;
import com.alechilles.alecstamework.api.commandui.CommandUiActionView;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorAction;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorActionContext;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorActionHandler;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorId;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Behavior contract for contributor-owned action catalog bindings. */
class CommandUiContributorActionCatalogTest {
    @Test
    void bindsDetachedContributorActionAndRejectsDuplicateInOneScope() {
        CommandUiContributorId contributorId =
                CommandUiContributorId.of("runeteria:test");
        CommandUiContributorActionHandler handler = context ->
                CompletableFuture.completedFuture(CommandUiActionResult.applied());
        CommandUiContributorAction action = new CommandUiContributorAction(
                "toggle", "TOGGLE_READY", "Toggle ready", "Icons/Toggle.png",
                true, true, null,
                CommandUiContributorAction.InputPolicy.NONE, true,
                Map.of("source", "test"), handler);
        CommandUiActionCatalog catalog = new CommandUiActionCatalog();

        CommandUiActionResult first = catalog.addContributorCommand(
                contributorId, 7L, action);
        assertEquals(CommandUiActionStatus.APPLIED, first.status());

        CommandUiContributorActionBinding binding = catalog.contributorBindings()
                .getFirst();
        assertEquals("runeteria:test/toggle", binding.effectiveId());
        assertEquals(7L, binding.contributorGeneration());
        assertSame(handler, binding.handler());

        CommandUiActionView view = binding.view(
                new CommandUiActionHandle("opaque-handle"));
        assertEquals("TOGGLE_READY", view.kind());
        assertEquals("Toggle ready", view.label());
        assertEquals("Icons/Toggle.png", view.iconAssetId());
        assertEquals("test", view.metadata().get("source"));
        assertEquals("opaque-handle", view.handle().token());

        CommandUiActionResult duplicate = catalog.addContributorCommand(
                contributorId, 8L, action);
        assertEquals(CommandUiActionStatus.CONFLICT, duplicate.status());
        assertEquals(1, catalog.contributorBindings().size());
    }

    @SuppressWarnings("unused")
    private static CompletionStage<CommandUiActionResult> invokeOnlyThroughBinding(
            CommandUiContributorActionHandler handler,
            CommandUiContributorActionContext context
    ) {
        return handler.handle(context);
    }
}
