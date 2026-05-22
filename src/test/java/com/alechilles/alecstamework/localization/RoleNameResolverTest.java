package com.alechilles.alecstamework.localization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

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
}
