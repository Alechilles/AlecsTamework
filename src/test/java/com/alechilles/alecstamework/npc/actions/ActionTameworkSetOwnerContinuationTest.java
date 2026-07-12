package com.alechilles.alecstamework.npc.actions;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the standalone tame list against consuming/taming before owner admission succeeds. */
class ActionTameworkSetOwnerContinuationTest {
    private static final Path TEMPLATE_ROOT = Path.of(
            "src", "main", "resources", "Server", "NPC", "Roles", "_Core", "Templates"
    );

    @Test
    void shippedVanillaTameListsContainOnlyDeferredSetOwnerAction() throws Exception {
        for (String fileName : List.of(
                "Template_Tamework_Example_Vanilla.json",
                "Template_Tamework_Example_Simple_Vanilla.json"
        )) {
            JsonElement root = JsonParser.parseString(Files.readString(
                    TEMPLATE_ROOT.resolve(fileName), StandardCharsets.UTF_8
            ));
            List<JsonArray> actionLists = new ArrayList<>();
            collectSetOwnerActionLists(root, actionLists);

            assertEquals(1, actionLists.size(), fileName + " should define one tame owner action list");
            JsonArray actions = actionLists.get(0);
            assertEquals(1, actions.size(), fileName + " must not run eager success siblings");
            JsonObject action = actions.get(0).getAsJsonObject();
            assertTrue(action.get("TameOnApplied").getAsBoolean());
            assertTrue(action.get("ConsumeHeldItemOnApplied").getAsBoolean());
            assertEquals("Idle.Default", action.get("StateOnApplied").getAsString());
            assertEquals("Pet_Heal", action.get("ParticleSystemOnApplied").getAsString());
            assertEquals("InteractSoundFeed", action.get("SoundEventParamOnApplied").getAsString());
        }
    }

    @Test
    void deferredEffectsReceiveFreshOwnerMutationContext() throws Exception {
        String action = Files.readString(Path.of(
                "src", "main", "java", "com", "alechilles", "alecstamework", "npc", "actions",
                "ActionTameworkSetOwner.java"
        ), StandardCharsets.UTF_8);
        int callback = action.indexOf("public void onApplied(OwnerPopulationDecision decision,");
        int continuation = action.indexOf("appliedEffects.apply(context, playerId, expectedHeldItemId)");

        assertTrue(callback >= 0 && continuation > callback,
                "Tame/item/state/presentation work must live in the admitted fresh-context callback.");
    }

    private static void collectSetOwnerActionLists(JsonElement element, List<JsonArray> matches) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (JsonElement child : array) {
                if (child.isJsonObject()
                        && child.getAsJsonObject().has("Type")
                        && "TameworkSetOwner".equals(child.getAsJsonObject().get("Type").getAsString())) {
                    matches.add(array);
                    break;
                }
            }
            for (JsonElement child : array) {
                collectSetOwnerActionLists(child, matches);
            }
            return;
        }
        if (element.isJsonObject()) {
            for (var entry : element.getAsJsonObject().entrySet()) {
                collectSetOwnerActionLists(entry.getValue(), matches);
            }
        }
    }
}
