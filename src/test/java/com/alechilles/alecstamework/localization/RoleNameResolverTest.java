package com.alechilles.alecstamework.localization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.npc.compat.NpcSupportTestFixture;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.util.expression.StdScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RoleNameResolverTest {

    @AfterEach
    void clearNpcSupport() {
        NpcSupportTestFixture.clear();
    }

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
        return NpcSupportTestFixture.bindRoleWithSensorScope(scope);
    }
}
