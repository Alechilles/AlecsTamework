package com.alechilles.alecstamework.access.groups;

import com.alechilles.alecstamework.api.access.AccessGroupMembership;
import java.lang.reflect.Method;
import java.util.List;
import javax.annotation.Nonnull;

/**
 * The players an owner added as members of their own QuestLines Claims claims, except members the
 * owner put in the built-in "untrusted" group. Claims owned by a guild, nation or city do not
 * count: they are not the owner's. QuestLines keeps its claims in memory.
 */
final class QuestLinesClaimMembersGroup extends ModGroupProvider {
    /** QuestLines' built-in group for members who get no trust ({@code RegionGroup.UNTRUSTED_ID}). */
    private static final String UNTRUSTED_GROUP = "untrusted";

    QuestLinesClaimMembersGroup() {
        super("questlinesclaims:members", "server.tamework.share.builtInGroup.questLinesClaimMembers",
                "net.evilcraft:QuestLinesClaims");
    }

    @Nonnull
    @Override
    Lookup bind(@Nonnull ClassLoader loader) throws ReflectiveOperationException {
        Class<?> plugin = Class.forName("net.evilcraft.questlinesclaims.QuestLinesClaimsPlugin", false, loader);
        Class<?> api = Class.forName("net.evilcraft.questlinesclaims.api.QuestLinesClaimsAPI", false, loader);
        Class<?> ownerType = Class.forName("net.evilcraft.questlinesclaims.data.ClaimOwnerType", false, loader);
        Class<?> claim = Class.forName("net.evilcraft.questlinesclaims.data.PlayerClaim", false, loader);
        Class<?> regionMember = Class.forName("net.evilcraft.questlinesclaims.data.RegionMember", false, loader);
        Method getInstance = plugin.getMethod("getInstance");
        Method getApi = plugin.getMethod("getApi");
        Method getClaimsForOwner = api.getMethod("getClaimsForOwner", ownerType, java.util.UUID.class);
        Method getMember = claim.getMethod("getMember", String.class);
        Method getGroupId = regionMember.getMethod("getGroupId");
        Object playerOwner = enumConstant(ownerType, "PLAYER");
        return (owner, actor) -> {
            Object instance = getInstance.invoke(null);
            Object claims = instance == null ? null : getApi.invoke(instance);
            if (claims == null) {
                return AccessGroupMembership.UNKNOWN;
            }
            String actorKey = actor.toString();
            for (Object ownerClaim : (List<?>) getClaimsForOwner.invoke(claims, playerOwner, owner)) {
                Object found = getMember.invoke(ownerClaim, actorKey);
                if (found != null && !UNTRUSTED_GROUP.equalsIgnoreCase((String) getGroupId.invoke(found))) {
                    return AccessGroupMembership.MEMBER;
                }
            }
            return AccessGroupMembership.NOT_MEMBER;
        };
    }

    @Nonnull
    private static Object enumConstant(@Nonnull Class<?> type, @Nonnull String name) throws NoSuchFieldException {
        for (Object constant : type.getEnumConstants()) {
            if (((Enum<?>) constant).name().equals(name)) {
                return constant;
            }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }
}
