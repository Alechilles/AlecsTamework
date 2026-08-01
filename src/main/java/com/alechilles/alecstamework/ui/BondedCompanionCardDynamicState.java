package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.api.BondedCompanionReviveQuote;
import com.alechilles.alecstamework.api.BondedCompanionPresentationAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Identifies live card changes that can be patched without recreating controls.
 */
final class BondedCompanionCardDynamicState {
    private BondedCompanionCardDynamicState() {
    }

    static boolean changedOnlyByLiveFields(
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
                && attributesWithoutLiveFields(previous.attributes()).equals(
                        attributesWithoutLiveFields(current.attributes()))
                && previous.extensions().equals(current.extensions())
                && sameStatusExceptCooldown(previous.status(), current.status())
                && sameQuoteExceptCooldown(previous.reviveQuote(),
                        current.reviveQuote());
    }

    private static Map<String, String> attributesWithoutLiveFields(
            Map<String, String> attributes
    ) {
        Map<String, String> fixed = new HashMap<>(attributes);
        fixed.remove("sessionRemainingMs");
        fixed.remove("currentHealth");
        fixed.remove("maxHealth");
        fixed.remove("healthPercent");
        fixed.remove("level");
        fixed.remove("currentXp");
        fixed.remove("levelingConfigId");
        fixed.remove("talentConfigId");
        fixed.remove("talentSpentPoints");
        fixed.remove(BondedCompanionPresentationAttributes.FLIGHT_TOGGLE_AIRBORNE);
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
