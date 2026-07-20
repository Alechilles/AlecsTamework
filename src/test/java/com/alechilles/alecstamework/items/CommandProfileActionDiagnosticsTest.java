package com.alechilles.alecstamework.items;

import com.alechilles.alecstelemetry.api.TelemetryBreadcrumbContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Regression coverage for actionable, privacy-bounded profile-action diagnostics. */
class CommandProfileActionDiagnosticsTest {
    @Test
    void rejectedRecallBreadcrumbIncludesReasonWithoutRawIdentity() {
        UUID cachedUuid = UUID.randomUUID();
        UUID targetUuid = UUID.randomUUID();
        CommandNpcProfileActionResolver.ActionTarget target = target(
                CommandNpcProfileActionResolver.ResolutionStatus.BLOCKED,
                "profile-secret",
                cachedUuid,
                targetUuid,
                "profile_already_recovered"
        );

        TelemetryBreadcrumbContext breadcrumb =
                CommandProfileActionDiagnostics.breadcrumb("recall", target);
        String serialized = breadcrumb.toString();

        assertEquals("profile_already_recovered", breadcrumb.detail());
        assertEquals("recall", breadcrumb.operation());
        assertEquals("blocked", breadcrumb.failureClass());
        assertEquals("rejected", breadcrumb.disposition());
        assertEquals("profile_already_recovered", breadcrumb.attributes().get("reason"));
        assertFalse(serialized.contains("profile-secret"));
        assertFalse(serialized.contains(cachedUuid.toString()));
        assertFalse(serialized.contains(targetUuid.toString()));
    }

    @Test
    void durableLifecycleBlocksUseSpecificExistingFeedback() {
        assertEquals(
                "tamework.ui.notifications.command.move.captured",
                CommandProfileActionDiagnostics.feedbackKey(blocked("profile_is_captured"))
        );
        assertEquals(
                "tamework.ui.notifications.command.move.dead",
                CommandProfileActionDiagnostics.feedbackKey(blocked("profile_is_dead"))
        );
        assertEquals(
                "tamework.ui.notifications.command.move.inCoop",
                CommandProfileActionDiagnostics.feedbackKey(blocked("profile_is_cooped"))
        );
        assertEquals(
                "tamework.ui.notifications.command.move.lost",
                CommandProfileActionDiagnostics.feedbackKey(blocked("profile_is_lost"))
        );
        assertNull(CommandProfileActionDiagnostics.feedbackKey(
                blocked("profile_already_recovered")));
    }

    private CommandNpcProfileActionResolver.ActionTarget blocked(String reason) {
        return target(
                CommandNpcProfileActionResolver.ResolutionStatus.BLOCKED,
                "profile-a",
                UUID.randomUUID(),
                null,
                reason
        );
    }

    private CommandNpcProfileActionResolver.ActionTarget target(
            CommandNpcProfileActionResolver.ResolutionStatus status,
            String profileId,
            UUID cachedNpcUuid,
            UUID targetNpcUuid,
            String reason) {
        return new CommandNpcProfileActionResolver.ActionTarget(
                status,
                profileId,
                cachedNpcUuid,
                targetNpcUuid,
                null,
                List.of(cachedNpcUuid, targetNpcUuid != null ? targetNpcUuid : cachedNpcUuid),
                List.of(),
                reason
        );
    }
}
