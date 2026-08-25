package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandui.CommandUiActionHandle;
import java.util.Objects;
import javax.annotation.Nullable;

/** Issues generic and bonded handles from detached command UI bindings. */
final class CommandUiActionBindingService {
    private final BondedCompanionPanelActionRouter bondedActions;

    CommandUiActionBindingService(
            @Nullable BondedCompanionPanelActionRouter bondedActions
    ) {
        this.bondedActions = bondedActions;
    }

    CommandUiActionHandle bindGeneric(
            CommandUiSessionImpl session,
            CommandSelectionPageService.GenericUiActionBinding binding
    ) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(binding, "binding");
        if (binding.sessionOperation() != null) {
            return session.issueGeneric(binding.action(), binding.authority(),
                    () -> binding.sessionOperation().execute(session),
                    binding.confirmationRequired());
        }
        if (binding.requestOperation() != null) {
            return session.issueRequest(
                    CommandUiActionGateway.Route.GENERIC, binding.action(),
                    ignored -> binding.authority().getAsBoolean(),
                    binding.requestOperation(), binding.inputPolicy(),
                    binding.maximumInputLength(),
                    binding.confirmationRequired());
        }
        return session.issueGeneric(binding.action(), binding.authority(),
                binding.operation(), binding.confirmationRequired());
    }

    @Nullable
    CommandUiActionHandle bindBonded(
            CommandUiSessionImpl session,
            CommandSelectionPageService.BondedUiActionBinding binding
    ) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(binding, "binding");
        if (binding.sessionOperation() != null) {
            return session.issueBonded(binding.action(), () -> true,
                    () -> binding.sessionOperation().execute(session),
                    binding.confirmationRequired());
        }
        BondedCompanionPanelActionService.Action action = bondedAction(binding);
        if (bondedActions == null || action == null) return null;
        CommandUiAction boundAction = new CommandUiAction(
                binding.action().kind(), null, binding.action().value(),
                binding.action().confirmationRequired());
        return session.issueBonded(boundAction, () -> true, () ->
                bondedActions.routeForUi(binding.ownerUuid(),
                        binding.rosterId(), binding.profileId(), action,
                        binding.contextResolver()),
                binding.confirmationRequired());
    }

    @Nullable
    private static BondedCompanionPanelActionService.Action bondedAction(
            CommandSelectionPageService.BondedUiActionBinding binding
    ) {
        return switch (binding.action().builtInKind()) {
            case SUMMON -> BondedCompanionPanelActionService.Action.SUMMON;
            case DISMISS -> BondedCompanionPanelActionService.Action.STORE;
            case REVIVE -> BondedCompanionPanelActionService.Action.REVIVE;
            case ABANDON -> BondedCompanionPanelActionService.Action.ABANDON;
            default -> null;
        };
    }
}
