package com.alechilles.alecstamework.integration.patchwork;

import com.alechilles.patchwork.embedded.PatchworkMacroProvider;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Expands the legacy state instruction macro into one explicit insert operation. */
final class TameworkStateInstructionMacro implements PatchworkMacroProvider {
    @Override public String macroId() { return "TameworkStateInstruction"; }
    @Override public JsonArray expand(JsonObject operation) {
        JsonObject options = options(operation); String id = operation.get("Id").getAsString(); String component = requiredStringOption(options, "Component", id);
        JsonObject branch = new JsonObject(); JsonObject enabled = new JsonObject(); enabled.addProperty("Compute", "true"); branch.add("Enabled", enabled); branch.addProperty("Continue", true);
        JsonArray instructions = new JsonArray(); JsonObject instruction = new JsonObject(); instruction.addProperty("Component", component); instructions.add(instruction); branch.add("Instructions", instructions);
        copy(options, branch, "Enabled"); copy(options, branch, "Sensor");
        JsonObject raw = new JsonObject(); raw.addProperty("Id", id + ".state"); raw.addProperty("Op", "Insert"); copy(operation, raw, "Path"); copy(operation, raw, "Position"); raw.addProperty("Required", required(operation)); raw.add("Value", branch); copy(operation, raw, "Find"); raw.add("Existing", matcher(component)); JsonArray result = new JsonArray(); result.add(raw); return result;
    }
    private static JsonObject options(JsonObject operation) { JsonElement value = operation.get("Options"); if (value == null || value.isJsonNull()) return new JsonObject(); if (!value.isJsonObject()) throw new IllegalArgumentException("Options must be an object."); return value.getAsJsonObject(); }
    private static String requiredStringOption(JsonObject options, String name, String context) { JsonElement value = options.get(name); if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().isBlank()) throw new IllegalArgumentException(context + " requires option " + name + "."); return value.getAsString(); }
    private static JsonObject matcher(String component) { JsonObject instruction = new JsonObject(); instruction.addProperty("Component", component); JsonObject contains = new JsonObject(); contains.add("$Contains", instruction); JsonObject result = new JsonObject(); result.add("Instructions", contains); return result; }
    private static boolean required(JsonObject operation) { JsonElement value = operation.get("Required"); return value == null || value.isJsonNull() || value.getAsBoolean(); }
    private static void copy(JsonObject source, JsonObject target, String name) { JsonElement value = source.get(name); if (value != null) target.add(name, value.deepCopy()); }
}
