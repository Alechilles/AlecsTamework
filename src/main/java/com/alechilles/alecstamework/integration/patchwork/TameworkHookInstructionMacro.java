package com.alechilles.alecstamework.integration.patchwork;

import com.alechilles.patchwork.embedded.PatchworkMacroProvider;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Expands the legacy hook instruction macro into one explicit insert operation. */
final class TameworkHookInstructionMacro implements PatchworkMacroProvider {
    @Override public String macroId() { return "TameworkHookInstruction"; }
    @Override public JsonArray expand(JsonObject operation) {
        JsonObject options = options(operation); String id = operation.get("Id").getAsString();
        String hookId = requiredStringOption(options, "HookId", id); boolean consume = booleanOption(options, "Consume", true);
        JsonObject branch = new JsonObject(); JsonObject enabled = new JsonObject(); enabled.addProperty("Compute", "true"); branch.add("Enabled", enabled); branch.addProperty("Continue", true);
        JsonObject sensor = new JsonObject(); sensor.addProperty("Type", "TameworkHook"); sensor.addProperty("HookId", hookId); sensor.addProperty("Consume", consume); branch.add("Sensor", sensor);
        JsonElement instructions = options.get("Instructions"); branch.add("Instructions", instructions != null && instructions.isJsonArray() ? instructions.deepCopy() : new JsonArray());
        JsonObject raw = raw(operation, id + ".hook", branch, matcher(hookId)); JsonArray result = new JsonArray(); result.add(raw); return result;
    }
    private static JsonObject raw(JsonObject source, String id, JsonObject value, JsonObject existing) { JsonObject raw = new JsonObject(); raw.addProperty("Id", id); raw.addProperty("Op", "Insert"); copy(source, raw, "Path"); copy(source, raw, "Position"); raw.addProperty("Required", required(source)); raw.add("Value", value); copy(source, raw, "Find"); raw.add("Existing", existing); return raw; }
    private static JsonObject matcher(String hookId) { JsonObject sensor = new JsonObject(); sensor.addProperty("Type", "TameworkHook"); sensor.addProperty("HookId", hookId); JsonObject result = new JsonObject(); result.add("Sensor", sensor); return result; }
    private static JsonObject options(JsonObject operation) { JsonElement value = operation.get("Options"); if (value == null || value.isJsonNull()) return new JsonObject(); if (!value.isJsonObject()) throw new IllegalArgumentException("Options must be an object."); return value.getAsJsonObject(); }
    private static String requiredStringOption(JsonObject options, String name, String context) { JsonElement value = options.get(name); if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().isBlank()) throw new IllegalArgumentException(context + " requires option " + name + "."); return value.getAsString(); }
    private static boolean booleanOption(JsonObject options, String name, boolean fallback) { JsonElement value = options.get(name); if (value == null || value.isJsonNull()) return fallback; if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException(name + " must be a boolean."); return value.getAsBoolean(); }
    private static boolean required(JsonObject operation) { JsonElement value = operation.get("Required"); return value == null || value.isJsonNull() || value.getAsBoolean(); }
    private static void copy(JsonObject source, JsonObject target, String name) { JsonElement value = source.get(name); if (value != null) target.add(name, value.deepCopy()); }
}
