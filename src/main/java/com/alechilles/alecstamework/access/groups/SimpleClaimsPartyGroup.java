package com.alechilles.alecstamework.access.groups;

import com.alechilles.alecstamework.api.access.AccessGroupMembership;
import java.lang.reflect.Method;
import java.util.Map;
import javax.annotation.Nonnull;

/**
 * The owner's SimpleClaims party. SimpleClaims keeps a concurrent player-to-party map, filled from
 * its saved parties at startup, so offline owners answer too.
 */
final class SimpleClaimsPartyGroup extends ModGroupProvider {
    SimpleClaimsPartyGroup() {
        super("simpleclaims:party", "server.tamework.share.builtInGroup.simpleClaimsParty", "Buuz135:SimpleClaims");
    }

    @Nonnull
    @Override
    Lookup bind(@Nonnull ClassLoader loader) throws ReflectiveOperationException {
        Class<?> claimManager = Class.forName("com.buuz135.simpleclaims.claim.ClaimManager", false, loader);
        Method getInstance = claimManager.getMethod("getInstance");
        Method getPlayerToParty = claimManager.getMethod("getPlayerToParty");
        return (owner, actor) -> {
            Object manager = getInstance.invoke(null);
            if (manager == null) {
                return AccessGroupMembership.UNKNOWN;
            }
            Map<?, ?> playerToParty = (Map<?, ?>) getPlayerToParty.invoke(manager);
            Object ownerParty = playerToParty.get(owner);
            return member(ownerParty != null && ownerParty.equals(playerToParty.get(actor)));
        };
    }
}
