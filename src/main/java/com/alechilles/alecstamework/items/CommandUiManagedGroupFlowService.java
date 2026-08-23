package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandui.CommandUiActionResult;
import com.alechilles.alecstamework.api.commandui.CommandUiActionView;
import com.alechilles.alecstamework.api.commandui.CommandUiGroupFlowView;
import com.alechilles.alecstamework.api.commandui.CommandUiGroupView;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Builds and mutates the detached command-group flow for custom providers. */
final class CommandUiManagedGroupFlowService {
    static final int MAX_GROUP_NAME_LENGTH = 24;
    static final int MAX_GROUP_COLOR_LENGTH = 7;

    private static final String DEFAULT_GROUP_COLOR = "#4b657f";

    private final CommandGroupService groups;
    private final CommandGroupActivationService activation;
    private final CommandLinkedNpcRecordStore linkedRecords;

    CommandUiManagedGroupFlowService() {
        this(new CommandGroupService(),
                new CommandGroupActivationService(null, null),
                new CommandLinkedNpcRecordStore());
    }

    CommandUiManagedGroupFlowService(
            @Nullable CommandGroupService groups,
            @Nullable CommandGroupActivationService activation,
            @Nullable CommandLinkedNpcRecordStore linkedRecords
    ) {
        this.groups = groups == null ? new CommandGroupService() : groups;
        this.activation = activation == null
                ? new CommandGroupActivationService(null, this.groups)
                : activation;
        this.linkedRecords = linkedRecords == null
                ? new CommandLinkedNpcRecordStore() : linkedRecords;
    }

    /** Opens a new managed generation with handles bound to the current tool. */
    @Nonnull
    CompletionStage<CommandUiActionResult> open(
            @Nonnull CommandUiSessionImpl session,
            @Nonnull Context context
    ) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(context, "context");
        if (!context.allows()) {
            return completed(CommandUiActionResult.denied(
                    "current command tool authority denied group management"));
        }
        try {
            return completed(CommandUiActionResult.presented(
                    build(session, context)));
        } catch (RuntimeException | LinkageError failure) {
            return completed(CommandUiActionResult.failed(
                    "command group flow could not be opened"));
        }
    }

    @Nonnull
    private CommandUiGroupFlowView build(
            CommandUiSessionImpl session,
            Context context
    ) {
        session.beginManagedFlow();
        ItemStack stack = context.stack();
        if (stack == null || stack.isEmpty()) {
            throw new IllegalStateException("Command tool is unavailable.");
        }
        String activeSelection = activation.resolveSelectionValue(stack);
        List<CommandUiGroupView> views = new ArrayList<>();
        for (CommandGroupService.GroupRecord group : groups.readGroups(stack)) {
            if (group == null || group.groupId == null
                    || group.groupId.isBlank()) continue;
            boolean active = group.groupId.equalsIgnoreCase(activeSelection);
            views.add(new CommandUiGroupView(
                    group.groupId, group.name, group.colorHex, active,
                    action(session, context, "RENAME_GROUP", "Rename",
                            group.groupId,
                            CommandUiActionGateway.InputPolicy.REQUIRED_TEXT,
                            MAX_GROUP_NAME_LENGTH, false),
                    action(session, context, "RECOLOR_GROUP", "Recolor",
                            group.groupId,
                            CommandUiActionGateway.InputPolicy.REQUIRED_TEXT,
                            MAX_GROUP_COLOR_LENGTH, false),
                    action(session, context, "DELETE_GROUP", "Delete",
                            group.groupId, CommandUiActionGateway.InputPolicy.NONE,
                            0, true),
                    action(session, context, "SELECT_ACTIVE_GROUP", "Activate",
                            group.groupId, CommandUiActionGateway.InputPolicy.NONE,
                            0, false)));
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("toolId", context.toolId());
        metadata.put("activeSelection", activeSelection);
        return new CommandUiGroupFlowView(
                activeSelection, views,
                action(session, context, "CREATE_GROUP", "Create group", null,
                        CommandUiActionGateway.InputPolicy.REQUIRED_TEXT,
                        MAX_GROUP_NAME_LENGTH, false),
                action(session, context, "SELECT_ACTIVE_GROUP", "Use all", 
                        CommandGroupActivationService.ALL_VALUE,
                        CommandUiActionGateway.InputPolicy.NONE, 0, false),
                action(session, context, "SELECT_ACTIVE_GROUP", "Use none",
                        CommandGroupActivationService.NONE_VALUE,
                        CommandUiActionGateway.InputPolicy.NONE, 0, false),
                metadata);
    }

    @Nonnull
    private CommandUiActionView action(
            CommandUiSessionImpl session,
            Context context,
            String kind,
            String label,
            @Nullable String groupId,
            CommandUiActionGateway.InputPolicy inputPolicy,
            int maximumInputLength,
            boolean confirmation
    ) {
        CommandUiAction action = new CommandUiAction(
                kind, null, groupId, confirmation);
        var handle = session.issueManaged(
                CommandUiActionGateway.Route.GENERIC, action,
                ignored -> context.allows(),
                (bound, input) -> execute(
                        session, context, bound, input),
                inputPolicy, maximumInputLength, confirmation);
        return new CommandUiActionView(kind, label, true, null,
                confirmation, handle);
    }

    @Nonnull
    private CompletionStage<CommandUiActionResult> execute(
            CommandUiSessionImpl session,
            Context context,
            CommandUiAction action,
            @Nullable String input
    ) {
        UnaryOperator<ItemStack> mutation = switch (action.builtInKind()) {
            case CREATE_GROUP -> stack -> groups.createGroup(
                    stack, input, DEFAULT_GROUP_COLOR);
            case RENAME_GROUP -> stack -> groups.renameGroup(
                    stack, action.value(), input);
            case RECOLOR_GROUP -> validColor(input)
                    ? stack -> groups.recolorGroup(
                            stack, action.value(), input)
                    : null;
            case DELETE_GROUP -> stack -> deleteGroup(stack, action.value());
            case SELECT_ACTIVE_GROUP -> stack -> activation.applySelection(
                    stack, action.value());
            default -> null;
        };
        if (mutation == null) {
            return completed(CommandUiActionResult.denied(
                    "command group action input is invalid"));
        }
        boolean changed;
        try {
            changed = context.mutate(mutation);
        } catch (RuntimeException | LinkageError failure) {
            return completed(CommandUiActionResult.failed(
                    "command group action failed"));
        }
        if (!changed) {
            return completed(CommandUiActionResult.conflict(
                    "command group state did not change"));
        }
        if (!context.allows()) {
            return completed(CommandUiActionResult.denied(
                    "current command tool authority was lost"));
        }
        try {
            return completed(CommandUiActionResult.updated(
                    "command group updated", build(session, context)));
        } catch (RuntimeException | LinkageError failure) {
            return completed(CommandUiActionResult.failed(
                    "updated command group flow is unavailable"));
        }
    }

    private ItemStack deleteGroup(ItemStack stack, @Nullable String groupId) {
        ItemStack withoutGroup = groups.deleteGroup(stack, groupId);
        if (withoutGroup == stack) return stack;
        List<LinkedNpcRecord> records = linkedRecords.read(withoutGroup);
        if (records.isEmpty()) return withoutGroup;
        List<LinkedNpcRecord> updated = new ArrayList<>(records.size());
        boolean changed = false;
        for (LinkedNpcRecord record : records) {
            if (record == null) continue;
            if (record.groupId != null && groupId != null
                    && record.groupId.equalsIgnoreCase(groupId)) {
                updated.add(withoutGroup(record));
                changed = true;
            } else {
                updated.add(record);
            }
        }
        return changed ? linkedRecords.write(withoutGroup, updated)
                : withoutGroup;
    }

    private static LinkedNpcRecord withoutGroup(LinkedNpcRecord record) {
        return new LinkedNpcRecord(
                record.npcUuid, record.profileId, record.lastKnownPosition,
                record.lastKnownWorldName, record.homePosition,
                record.cachedDisplayName, record.cachedNameKey,
                record.cachedRoleId, record.cachedCommandState, record.active,
                record.breedingEnabled, null);
    }

    private static boolean validColor(@Nullable String input) {
        return input != null && input.matches("^#[0-9A-Fa-f]{6}$");
    }

    @Nonnull
    private static CompletionStage<CommandUiActionResult> completed(
            CommandUiActionResult result
    ) {
        return CompletableFuture.completedFuture(result);
    }

    /** Supplies current physical-tool authority without retaining a player. */
    record Context(
            String toolId,
            BooleanSupplier authority,
            Supplier<ItemStack> stackSupplier,
            Function<UnaryOperator<ItemStack>, Boolean> mutator
    ) {
        Context {
            if (toolId == null || toolId.isBlank()) {
                throw new IllegalArgumentException("toolId is required");
            }
            Objects.requireNonNull(authority, "authority");
            Objects.requireNonNull(stackSupplier, "stackSupplier");
            Objects.requireNonNull(mutator, "mutator");
        }

        boolean allows() {
            try {
                ItemStack stack = stackSupplier.get();
                return authority.getAsBoolean()
                        && stack != null && !stack.isEmpty();
            } catch (RuntimeException | LinkageError failure) {
                return false;
            }
        }

        @Nullable
        ItemStack stack() {
            return stackSupplier.get();
        }

        boolean mutate(UnaryOperator<ItemStack> mutation) {
            return Boolean.TRUE.equals(mutator.apply(mutation));
        }
    }
}
