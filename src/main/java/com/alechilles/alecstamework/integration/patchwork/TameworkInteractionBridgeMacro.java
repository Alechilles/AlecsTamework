package com.alechilles.alecstamework.integration.patchwork;

import com.alechilles.patchwork.embedded.PatchworkMacroProvider;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Expands the legacy interaction bridge macro into its two explicit insert operations. */
final class TameworkInteractionBridgeMacro implements PatchworkMacroProvider {
    @Override public String macroId() { return "TameworkInteractionBridge"; }

    @Override public JsonArray expand(JsonObject operation) {
        JsonObject options = options(operation);
        JsonObject actionFields = objectOption(options, "ActionFields", new JsonObject());
        JsonArray result = new JsonArray();
        result.add(raw(operation, suffixId(operation, ".prompt"), position(operation), true, branch("Any", true,
                action("TameworkInteractPrompt", actionFields)), matcherForAction("TameworkInteractPrompt")));
        result.add(raw(operation, suffixId(operation, ".interact"), "End", false, branch("HasInteracted", false,
                lockAction("InteractionTarget"), lockAction("MasterTarget"), action("TameworkInteract", actionFields)),
                matcherForAction("TameworkInteract")));
        return result;
    }

    private static JsonObject raw(JsonObject source, String id, String position, boolean includeFind, JsonObject value, JsonObject existing) {
        JsonObject raw = new JsonObject(); raw.addProperty("Id", id); raw.addProperty("Op", "Insert");
        copy(source, raw, "Path"); if (position != null) raw.addProperty("Position", position); raw.addProperty("Required", required(source));
        raw.add("Value", value); if (includeFind) copy(source, raw, "Find"); raw.add("Existing", existing); return raw;
    }
    private static JsonObject options(JsonObject operation) { JsonElement value = operation.get("Options"); if (value == null || value.isJsonNull()) return new JsonObject(); if (!value.isJsonObject()) throw new IllegalArgumentException("Options must be an object."); return value.getAsJsonObject(); }
    private static JsonObject objectOption(JsonObject options, String name, JsonObject fallback) { JsonElement value = options.get(name); if (value == null || value.isJsonNull()) return fallback; if (!value.isJsonObject()) throw new IllegalArgumentException(name + " must be an object."); return value.getAsJsonObject(); }
    private static JsonObject action(String type, JsonObject fields) { JsonObject result = fields.deepCopy(); result.addProperty("Type", type); return result; }
    private static JsonObject lockAction(String slot) { JsonObject result = new JsonObject(); result.addProperty("Type", "LockOnInteractionTarget"); result.addProperty("TargetSlot", slot); return result; }
    private static JsonObject branch(String sensorType, boolean continues, JsonObject... actions) { JsonObject result = new JsonObject(); JsonObject enabled = new JsonObject(); enabled.addProperty("Compute", "true"); result.add("Enabled", enabled); JsonObject sensor = new JsonObject(); sensor.addProperty("Type", sensorType); result.add("Sensor", sensor); JsonArray array = new JsonArray(); for (JsonObject action : actions) array.add(action); result.add("Actions", array); if (continues) result.addProperty("Continue", true); return result; }
    private static JsonObject matcherForAction(String type) { JsonObject action = new JsonObject(); action.addProperty("Type", type); JsonObject contains = new JsonObject(); contains.add("$Contains", action); JsonObject result = new JsonObject(); result.add("Actions", contains); return result; }
    private static void copy(JsonObject source, JsonObject target, String name) { JsonElement value = source.get(name); if (value != null) target.add(name, value.deepCopy()); }
    private static String suffixId(JsonObject operation, String suffix) { return operation.get("Id").getAsString() + suffix; }
    private static String position(JsonObject operation) { JsonElement value = operation.get("Position"); return value == null || value.isJsonNull() ? null : value.getAsString(); }
    private static boolean required(JsonObject operation) { JsonElement value = operation.get("Required"); return value == null || value.isJsonNull() || value.getAsBoolean(); }
}
