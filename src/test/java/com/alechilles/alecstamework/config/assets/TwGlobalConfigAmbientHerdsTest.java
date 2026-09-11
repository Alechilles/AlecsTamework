package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.codec.ExtraInfo;
import java.util.Map;
import java.util.Set;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Prevents partial global overrides from enabling unwanted species or defeating an explicit disable. */
class TwGlobalConfigAmbientHerdsTest {
    @Test
    void omittedSectionInheritsEligibilityButExplicitDisableWins() {
        var parent = parse("""
                {"AmbientHerds":{"Enabled":true,"RoleIds":["Bison","Bison_Calf"]}}
                """);
        var child = parse("{}");
        child.inheritMissingTopLevelFrom(parent, Set.of(), Map.of());
        assertTrue(child.allowsAmbientHerdRole("Bison"));
        assertFalse(child.allowsAmbientHerdRole("Wolf"));

        var disabled = parse(""" 
                {"AmbientHerds":{"Enabled":false}}
                """);
        disabled.inheritMissingTopLevelFrom(parent, Set.of("AmbientHerds"),
                Map.of("AmbientHerds", Set.of("Enabled")));
        assertFalse(disabled.allowsAmbientHerdRole("Bison"));
        assertEquals(Set.of("Bison", "Bison_Calf"), disabled.getAmbientHerdRoleIds());
    }

    @Test
    void explicitRoleListReplacesParentAndEmptyListOptsOut() {
        var parent = parse("""
                {"AmbientHerds":{"Enabled":true,"RoleIds":["Bison"]}}
                """);
        var child = parse("""
                {"AmbientHerds":{"RoleIds":["Antelope"]}}
                """);
        child.inheritMissingTopLevelFrom(parent, Set.of("AmbientHerds"),
                Map.of("AmbientHerds", Set.of("RoleIds")));
        assertTrue(child.allowsAmbientHerdRole("Antelope"));
        assertFalse(child.allowsAmbientHerdRole("Bison"));
        var empty = parse("""
                {"AmbientHerds":{"RoleIds":[]}}
                """);
        empty.inheritMissingTopLevelFrom(child, Set.of("AmbientHerds"),
                Map.of("AmbientHerds", Set.of("RoleIds")));
        assertFalse(empty.allowsAmbientHerdRole("Antelope"));
        assertFalse(parse("{}").allowsAmbientHerdRole("Bison"));
    }

    private static TwGlobalConfig parse(String json) {
        return TwGlobalConfig.CODEC.decode(BsonDocument.parse(json), new ExtraInfo());
    }
}
