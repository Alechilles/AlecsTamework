package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.damage.SimpleClaimsCapabilityRuntime;
import com.alechilles.alecstamework.integration.claims.ClaimModDamageProvider;
import com.alechilles.alecstamework.integration.claims.ClaimModProvider;
import com.alechilles.alecstamework.integration.claims.ClaimModSelector;
import com.alechilles.alecstamework.integration.questlinesclaims.QuestLinesClaimsProvider;
import com.alechilles.alecstamework.integration.simpleclaims.SimpleClaimsClaimModProvider;
import com.alechilles.alecstamework.settings.ClaimModChoice;
import java.util.function.Predicate;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Resolves the claim mod to read for one claim-limit or breeding check, on the world thread. */
@FunctionalInterface
interface ClaimProviderResolver {
    @Nonnull
    Resolution resolve(@Nonnull ClaimModChoice choice);

    /** The claim mods of the running plugin, or fresh ones when Tamework is not running. */
    @Nonnull
    static ClaimProviderResolver live() {
        Tamework plugin = Tamework.getInstance();
        return plugin == null
                ? live(new SimpleClaimsCapabilityRuntime(), new QuestLinesClaimsProvider(), ClaimModSelector.SERVER_PLUGINS)
                : live(plugin.getSimpleClaimsCapabilityRuntime(), plugin.getQuestLinesClaimsProvider(),
                        ClaimModSelector.SERVER_PLUGINS);
    }

    @Nonnull
    static ClaimProviderResolver live(@Nonnull SimpleClaimsCapabilityRuntime simpleClaims,
                                      @Nonnull ClaimModDamageProvider questLines,
                                      @Nonnull Predicate<ClaimModChoice> installed) {
        return choice -> {
            if (ClaimModSelector.select(choice, installed) == ClaimModChoice.QUESTLINES_CLAIMS) {
                String reason = questLines.unavailableReason();
                return reason == null ? Resolution.ready(questLines) : Resolution.unavailable(reason);
            }
            SimpleClaimsCapabilityRuntime.BridgeResolution bridge = simpleClaims.resolveBridge();
            return bridge.available()
                    ? Resolution.ready(new SimpleClaimsClaimModProvider(bridge.bridge()))
                    : Resolution.unavailable(bridge.reason());
        };
    }

    /** A ready claim mod, or why none can be read. */
    record Resolution(@Nullable ClaimModProvider provider, @Nullable String reason) {
        @Nonnull
        static Resolution ready(@Nonnull ClaimModProvider provider) {
            return new Resolution(provider, null);
        }

        @Nonnull
        static Resolution unavailable(@Nullable String reason) {
            return new Resolution(null, reason);
        }

        boolean available() {
            return provider != null;
        }
    }
}
