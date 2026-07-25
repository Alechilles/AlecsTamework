package com.alechilles.alecstamework.localization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.EntitySupport;
import com.hypixel.hytale.server.npc.util.expression.StdScope;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

class RoleNameResolverTest {

    @Test
    void serverPluralRoleKeyAlsoChecksUnprefixedPluralLanguageKey() {
        assertTrue(RoleNameResolver.buildNameKeyCandidates("server.npcRoles.Bison.name")
                .contains("npcRoles.Bison.name"));
    }

    @Test
    void tamedRoleIdAlsoChecksBaseRoleLanguageKey() {
        assertTrue(RoleNameResolver.buildNameKeyCandidates("Tamed_Armadillo")
                .contains("npcRoles.Armadillo.name"));
    }

    @Test
    void resolvesDisplayNameFromRoleAssetNameKeyBeforeRawRoleId() {
        TranslationRegistry registry = new TranslationRegistry();
        registry.put("npcRoles.Bison.name", "Bison");

        assertEquals(
                "Bison",
                RoleNameResolver.resolveDisplayName(
                        "Tamed_Bison",
                        "server.npcRoles.Bison.name",
                        registry::get
                )
        );
    }

    @Test
    void resolvesLiveRoleNameKeyFromEntitySupportScope() throws Exception {
        StdScope scope = new StdScope(null);
        scope.addConst("NameTranslationKey", "server.npcRoles.Cat_Bobtail_Pet.name");
        Role role = roleWithSensorScope(scope);

        assertEquals(
                "server.npcRoles.Cat_Bobtail_Pet.name",
                RoleNameResolver.resolveRoleNameKey(role)
        );
    }

    @Test
    void fallsBackToRoleNameKeyIdentifierBeforeRawRoleId() {
        assertEquals(
                "Bison",
                RoleNameResolver.resolveDisplayName(
                        "Tamed_Bison",
                        "server.npcRoles.Bison.name",
                        null
                )
        );
    }

    @Test
    void baseRoleIdentifierIsGenericButARealCustomNameIsNot() {
        assertTrue(RoleNameResolver.isRoleIdentityDisplayName(
                "Wolf_Black",
                "Tamed_Wolf_Black",
                "server.npcRoles.Wolf_Black.name"
        ));
        assertFalse(RoleNameResolver.isRoleIdentityDisplayName(
                "Fenrir",
                "Tamed_Wolf_Black",
                "server.npcRoles.Wolf_Black.name"
        ));
    }

    private static Role roleWithSensorScope(StdScope scope) throws ReflectiveOperationException {
        Unsafe unsafe = unsafe();
        Role role = (Role) unsafe.allocateInstance(Role.class);
        EntitySupport entitySupport = (EntitySupport) unsafe.allocateInstance(EntitySupport.class);
        setField(entitySupport, "sensorScope", scope);
        setField(role, "entitySupport", entitySupport);
        return role;
    }

    private static void setField(Object target, String fieldName, Object value) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Unsafe unsafe() throws ReflectiveOperationException {
        Field unsafe = Unsafe.class.getDeclaredField("theUnsafe");
        unsafe.setAccessible(true);
        return (Unsafe) unsafe.get(null);
    }
}
