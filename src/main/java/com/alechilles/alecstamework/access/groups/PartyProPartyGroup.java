package com.alechilles.alecstamework.access.groups;

import com.alechilles.alecstamework.api.access.AccessGroupMembership;
import java.lang.reflect.Method;
import javax.annotation.Nonnull;

/**
 * The owner's PartyPro party, through PartyPro's public API, which answers from its in-memory
 * party cache. PartyPro can drop members who stay offline too long, so a long-gone owner's party
 * may stop sharing on its own.
 */
final class PartyProPartyGroup extends ModGroupProvider {
    PartyProPartyGroup() {
        super("partypro:party", "server.tamework.share.builtInGroup.partyProParty", "tsumori:partypro");
    }

    @Nonnull
    @Override
    Lookup bind(@Nonnull ClassLoader loader) throws ReflectiveOperationException {
        Class<?> api = Class.forName("me.tsumori.partypro.api.PartyProAPI", false, loader);
        Method isAvailable = api.getMethod("isAvailable");
        Method getInstance = api.getMethod("getInstance");
        Method areInSameParty = api.getMethod("areInSameParty", java.util.UUID.class, java.util.UUID.class);
        return (owner, actor) -> {
            if (!Boolean.TRUE.equals(isAvailable.invoke(null))) {
                return AccessGroupMembership.UNKNOWN;
            }
            Object instance = getInstance.invoke(null);
            return instance == null
                    ? AccessGroupMembership.UNKNOWN
                    : member(Boolean.TRUE.equals(areInSameParty.invoke(instance, owner, actor)));
        };
    }
}
