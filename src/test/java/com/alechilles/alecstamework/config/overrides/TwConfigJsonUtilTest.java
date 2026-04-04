package com.alechilles.alecstamework.config.overrides;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies merge/path utility behavior for local override editing. */
class TwConfigJsonUtilTest {

    @Test
    void mergeUsesNestedObjectFallbackAndArrayReplacement() {
        JsonObject base = JsonParser.parseString("""
                {
                  "General": { "Enabled": true, "Priority": 10 },
                  "AllowedRoles": ["Role_A", "Role_B"],
                  "CooldownSeconds": 4
                }
                """).getAsJsonObject();
        JsonObject override = JsonParser.parseString("""
                {
                  "General": { "Priority": 3 },
                  "AllowedRoles": ["Role_C"],
                  "CooldownSeconds": 1
                }
                """).getAsJsonObject();

        JsonObject merged = TwConfigJsonUtil.merge(base, override);

        JsonObject mergedGeneral = merged.getAsJsonObject("General");
        assertNotNull(mergedGeneral);
        assertTrue(mergedGeneral.get("Enabled").getAsBoolean());
        assertEquals(3, mergedGeneral.get("Priority").getAsInt());
        assertEquals(1, merged.getAsJsonArray("AllowedRoles").size());
        assertEquals("Role_C", merged.getAsJsonArray("AllowedRoles").get(0).getAsString());
        assertEquals(1, merged.get("CooldownSeconds").getAsInt());
    }

    @Test
    void setAndRemovePathPrunesNowEmptyParentContainers() {
        JsonObject root = new JsonObject();

        TwConfigJsonUtil.setPath(root, "OwnershipProtection.BlockOwnerDamage", JsonParser.parseString("true"));
        assertTrue(TwConfigJsonUtil.hasPath(root, "OwnershipProtection.BlockOwnerDamage"));

        boolean removed = TwConfigJsonUtil.removePath(root, "OwnershipProtection.BlockOwnerDamage");
        assertTrue(removed);
        assertFalse(TwConfigJsonUtil.hasPath(root, "OwnershipProtection.BlockOwnerDamage"));
        assertTrue(root.entrySet().isEmpty());
    }

    @Test
    void setPathReplacesNonObjectIntermediatesWithObjects() {
        JsonObject root = JsonParser.parseString("""
                { "A": 5 }
                """).getAsJsonObject();

        TwConfigJsonUtil.setPath(root, "A.B.C", JsonParser.parseString("9"));

        assertEquals(9, TwConfigJsonUtil.getPath(root, "A.B.C").getAsInt());
    }

    @Test
    void setGetAndRemovePathSupportArrayObjectIndexSegments() {
        JsonObject root = new JsonObject();

        TwConfigJsonUtil.setPath(
                root,
                "RoleOverrides.Tamed_Bison.OffspringLifecycle.Families[0].AdultRoleId",
                JsonParser.parseString("\"Tamed_Bison\"")
        );
        TwConfigJsonUtil.setPath(
                root,
                "RoleOverrides.Tamed_Bison.OffspringLifecycle.Families[0].BabyRoleId",
                JsonParser.parseString("\"Tamed_Bison_Calf\"")
        );

        assertEquals(
                "Tamed_Bison",
                TwConfigJsonUtil.getPath(
                        root,
                        "RoleOverrides.Tamed_Bison.OffspringLifecycle.Families[0].AdultRoleId"
                ).getAsString()
        );
        assertEquals(
                "Tamed_Bison_Calf",
                TwConfigJsonUtil.getPath(
                        root,
                        "RoleOverrides.Tamed_Bison.OffspringLifecycle.Families[0].BabyRoleId"
                ).getAsString()
        );

        assertTrue(TwConfigJsonUtil.removePath(root, "RoleOverrides.Tamed_Bison.OffspringLifecycle.Families[0].AdultRoleId"));
        assertFalse(TwConfigJsonUtil.hasPath(root, "RoleOverrides.Tamed_Bison.OffspringLifecycle.Families[0].AdultRoleId"));
        assertTrue(TwConfigJsonUtil.hasPath(root, "RoleOverrides.Tamed_Bison.OffspringLifecycle.Families[0].BabyRoleId"));

        assertTrue(TwConfigJsonUtil.removePath(root, "RoleOverrides.Tamed_Bison.OffspringLifecycle.Families[0].BabyRoleId"));
        assertFalse(TwConfigJsonUtil.hasPath(root, "RoleOverrides.Tamed_Bison.OffspringLifecycle.Families[0].BabyRoleId"));
        assertTrue(root.entrySet().isEmpty());
    }
}
