package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandui.CommandUiActionRequest;
import com.alechilles.alecstamework.api.commandui.CommandUiActionResult;
import com.alechilles.alecstamework.api.commandui.CommandUiActionStatus;
import com.alechilles.alecstamework.api.commandui.CommandUiGroupFlowView;
import com.alechilles.alecstamework.api.commandui.CommandUiPanelState;
import com.alechilles.alecstamework.api.commandui.CommandUiSnapshot;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Observable mutations exposed by the managed command-group flow. */
class CommandUiManagedGroupFlowServiceTest {
    @Test
    void groupFlowCreatesRenamesRecolorsAndDeletesStoredGroups() {
        AtomicReference<ItemStack> stack = new AtomicReference<>(
                new MetadataItemStack("test:flute", null));
        AtomicBoolean authority = new AtomicBoolean(true);
        CommandUiManagedGroupFlowService service =
                new CommandUiManagedGroupFlowService();
        CommandUiSessionImpl session = session();
        var context = context(stack, authority);

        CommandUiGroupFlowView empty = flow(service.open(session, context));
        assertTrue(empty.groups().isEmpty());
        assertNotNull(empty.createAction());
        assertNotNull(empty.selectAllAction());
        assertNotNull(empty.selectNoneAction());

        CommandUiGroupFlowView created = flow(session.invoke(
                new CommandUiActionRequest(
                        empty.createAction().handle(), "Barn")));
        assertEquals(1, created.groups().size());
        assertEquals("Barn", created.groups().getFirst().name());
        assertEquals("#4B657F", created.groups().getFirst().colorHex());
        String groupId = created.groups().getFirst().groupId();
        assertEquals(Map.of(groupId, "Barn"),
                CommandUiSnapshotAssembler.groups(stack.get()));
        stack.set(new CommandLinkedNpcRecordStore().write(stack.get(), List.of(
                new LinkedNpcRecord(UUID.randomUUID(), null, null,
                        "Alpaca", null, "livestock", null,
                        false, false, groupId),
                new LinkedNpcRecord(UUID.randomUUID(), null, null,
                        "Cow", null, "livestock", null,
                        false, false, null))));
        created = flow(service.open(session, context));
        CommandUiGroupFlowView activated = flow(session.invoke(
                created.groups().getFirst().selectAction().handle()));
        assertEquals(groupId, activated.activeGroupId());
        assertTrue(activated.groups().getFirst().active());

        CommandUiGroupFlowView renamed = flow(session.invoke(
                new CommandUiActionRequest(
                        activated.groups().getFirst().renameAction().handle(),
                        "Livestock")));
        assertEquals("Livestock", renamed.groups().getFirst().name());

        CommandUiActionResult invalidColor = session.invoke(
                new CommandUiActionRequest(
                        renamed.groups().getFirst().recolorAction().handle(),
                        "not-red"))
                .toCompletableFuture().join();
        assertEquals(CommandUiActionStatus.DENIED, invalidColor.status());
        assertEquals("#4B657F", new CommandGroupService()
                .readGroups(stack.get()).getFirst().colorHex);
        renamed = flow(service.open(session, context));

        CommandUiGroupFlowView recolored = flow(session.invoke(
                new CommandUiActionRequest(
                        renamed.groups().getFirst().recolorAction().handle(),
                        "#1a2b3c")));
        assertEquals("#1A2B3C", recolored.groups().getFirst().colorHex());

        CommandUiActionResult confirmation = session.invoke(
                recolored.groups().getFirst().deleteAction().handle())
                .toCompletableFuture().join();
        assertEquals(CommandUiActionStatus.CONFIRMATION_REQUIRED,
                confirmation.status());
        CommandUiGroupFlowView deleted = flow(session.invoke(
                confirmation.confirmationHandle()));
        assertTrue(deleted.groups().isEmpty());
        assertNull(new CommandLinkedNpcRecordStore()
                .read(stack.get()).getFirst().groupId);
        session.close();
    }

    @Test
    void groupFlowRejectsExcessInputAndLostToolAuthorityBeforeMutation() {
        AtomicReference<ItemStack> stack = new AtomicReference<>(
                new MetadataItemStack("test:flute", null));
        AtomicBoolean authority = new AtomicBoolean(true);
        CommandUiManagedGroupFlowService service =
                new CommandUiManagedGroupFlowService();
        CommandUiSessionImpl session = session();
        var context = context(stack, authority);
        CommandUiGroupFlowView flow = flow(service.open(session, context));

        CommandUiActionResult tooLong = session.invoke(
                new CommandUiActionRequest(flow.createAction().handle(),
                        "1234567890123456789012345"))
                .toCompletableFuture().join();
        assertEquals(CommandUiActionStatus.DENIED, tooLong.status());
        assertTrue(new CommandGroupService().readGroups(stack.get()).isEmpty());

        authority.set(false);
        CommandUiActionResult denied = session.invoke(
                new CommandUiActionRequest(flow.createAction().handle(),
                        "Pasture"))
                .toCompletableFuture().join();
        assertEquals(CommandUiActionStatus.DENIED, denied.status());
        assertTrue(new CommandGroupService().readGroups(stack.get()).isEmpty());
        session.close();
    }

    private static CommandUiManagedGroupFlowService.Context context(
            AtomicReference<ItemStack> stack,
            AtomicBoolean authority
    ) {
        return new CommandUiManagedGroupFlowService.Context(
                "tool-1", () -> authority.get() && stack.get() != null,
                stack::get, mutator -> mutate(stack, mutator));
    }

    private static boolean mutate(
            AtomicReference<ItemStack> stack,
            UnaryOperator<ItemStack> mutator
    ) {
        ItemStack current = stack.get();
        ItemStack updated = mutator.apply(current);
        if (updated == null || updated == current) return false;
        stack.set(updated);
        return true;
    }

    private static CommandUiGroupFlowView flow(
            java.util.concurrent.CompletionStage<CommandUiActionResult> stage
    ) {
        CommandUiActionResult result = stage.toCompletableFuture().join();
        assertTrue(result.status() == CommandUiActionStatus.ACCEPTED
                || result.status() == CommandUiActionStatus.APPLIED);
        return (CommandUiGroupFlowView) result.flowView();
    }

    private static CommandUiSessionImpl session() {
        UUID id = UUID.randomUUID();
        return new CommandUiSessionImpl(id,
                new CommandUiSnapshot(id, 1L, 1L, null,
                        List.of(), List.of(),
                        new CommandUiPanelState("linked")),
                new CommandUiActionGateway(),
                CommandUiWorldDispatcher.direct(),
                CommandUiSessionImpl.Mode.GENERIC);
    }

    /** Asset-store-free stack that keeps production BSON metadata behavior. */
    private static final class MetadataItemStack extends ItemStack {
        private MetadataItemStack(String itemId, BsonDocument metadata) {
            super();
            this.itemId = itemId;
            this.quantity = 1;
            this.metadata = metadata;
        }

        @Override
        public <T> ItemStack withMetadata(
                String key, Codec<T> codec, T value
        ) {
            BsonDocument next = metadata == null
                    ? new BsonDocument() : metadata.clone();
            if (value == null) next.remove(key);
            else next.put(key, codec.encode(value));
            return new MetadataItemStack(itemId,
                    next.isEmpty() ? null : next);
        }
    }
}
