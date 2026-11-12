package com.alechilles.alecstamework.access.groups;

import com.alechilles.alecstamework.api.access.AccessGroupMembership;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * The players added as members or owners of an OrbisGuard region the owner also owns, through
 * OrbisGuard's public API. Only regions of worlds OrbisGuard has loaded count.
 */
final class OrbisGuardRegionMembersGroup extends ModGroupProvider {
    OrbisGuardRegionMembersGroup() {
        super("orbisguard:members", "server.tamework.share.builtInGroup.orbisGuardRegionMembers",
                "OrbisGuard:OrbisGuard");
    }

    @Nonnull
    @Override
    Lookup bind(@Nonnull ClassLoader loader) throws ReflectiveOperationException {
        Class<?> api = Class.forName("com.orbisguard.api.OrbisGuardAPI", false, loader);
        Class<?> container = Class.forName("com.orbisguard.api.region.IRegionContainer", false, loader);
        Class<?> manager = Class.forName("com.orbisguard.api.region.IRegionManager", false, loader);
        Class<?> region = Class.forName("com.orbisguard.api.region.IRegion", false, loader);
        Method getInstance = api.getMethod("getInstance");
        Method getRegionContainer = api.getMethod("getRegionContainer");
        Method getLoadedManagers = container.getMethod("getLoadedManagers");
        Method getRegions = manager.getMethod("getRegions");
        Method isOwner = region.getMethod("isOwner", UUID.class);
        Method isMember = region.getMethod("isMember", UUID.class);
        return (owner, actor) -> {
            Object instance = getInstance.invoke(null);
            Object regions = instance == null ? null : getRegionContainer.invoke(instance);
            if (regions == null) {
                return AccessGroupMembership.UNKNOWN;
            }
            for (Object worldManager : (Collection<?>) getLoadedManagers.invoke(regions)) {
                for (Object candidate : (Collection<?>) getRegions.invoke(worldManager)) {
                    // OrbisGuard counts a region's owners as its members too.
                    if (Boolean.TRUE.equals(isOwner.invoke(candidate, owner))
                            && Boolean.TRUE.equals(isMember.invoke(candidate, actor))) {
                        return AccessGroupMembership.MEMBER;
                    }
                }
            }
            return AccessGroupMembership.NOT_MEMBER;
        };
    }
}
