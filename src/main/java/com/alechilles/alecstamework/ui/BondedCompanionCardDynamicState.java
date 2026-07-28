package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.api.BondedCompanionReviveQuote;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Identifies card changes caused only by locally advancing session and
 * cooldown timers, which can be patched without recreating card controls.
 */
final class BondedCompanionCardDynamicState {
    private BondedCompanionCardDynamicState() {
    }

    static boolean changedOnlyByTimers(
            @Nonnull BondedCompanionPanelPresentation previous,
            @Nonnull BondedCompanionPanelPresentation current
    ) {
        return !previous.equals(current)
                && previous.profileId().equals(current.profileId())
                && previous.rosterId().equals(current.rosterId())
                && previous.roleId().equals(current.roleId())
                && previous.revision() == current.revision()
                && Objects.equals(previous.displayName(), current.displayName())
                && Objects.equals(previous.species(), current.species())
                && Objects.equals(previous.gender(), current.gender())
                && Objects.equals(previous.rolePresentation(), current.rolePresentation())
                && attributesWithoutSessionTimer(previous.attributes()).equals(
                        attributesWithoutSessionTimer(current.attributes()))
                && previous.extensions().equals(current.extensions())
                && sameStatusExceptCooldown(previous.status(), current.status())
                && sameQuoteExceptCooldown(previous.reviveQuote(),
                        current.reviveQuote());
    }

    private static Map<String, String> attributesWithoutSessionTimer(
            Map<String, String> attributes
    ) {
        Map<String, String> fixed = new HashMap<>(attributes);
        fixed.remove("sessionRemainingMs");
        return fixed;
    }

    private static boolean sameStatusExceptCooldown(
            BondedCompanionStatusPresentation previous,
            BondedCompanionStatusPresentation current
    ) {
        return previous.state() == current.state()
                && previous.action() == current.action()
                && previous.actionEnabled() == current.actionEnabled()
                && previous.blockReason() == current.blockReason()
                && Objects.equals(previous.unavailableReason(),
                        current.unavailableReason());
    }

    private static boolean sameQuoteExceptCooldown(
            @Nullable BondedCompanionReviveQuote previous,
            @Nullable BondedCompanionReviveQuote current
    ) {
        if (previous == null || current == null) return previous == current;
        return previous.profileId().equals(current.profileId())
                && previous.enabled() == current.enabled()
                && previous.costs().equals(current.costs())
                && previous.policyRevision() == current.policyRevision();
    }
}
