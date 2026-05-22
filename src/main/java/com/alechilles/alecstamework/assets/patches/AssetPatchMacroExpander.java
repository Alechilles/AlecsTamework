package com.alechilles.alecstamework.assets.patches;

import java.util.List;
import java.util.Locale;

import javax.annotation.Nonnull;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Expands compact Tamework-specific patch macros into explicit raw JSON operations.
 */
final class AssetPatchMacroExpander {

    @Nonnull
    List<AssetPatchOperation> expand(@Nonnull AssetPatchOperation operation) {
        if (!"macro".equalsIgnoreCase(operation.getOp())) {
            return List.of(operation);
        }
        String macro = operation.getMacro();
        if (macro == null || macro.isBlank()) {
            throw new IllegalArgumentException("Macro operation " + operation.getId() + " requires Macro.");
        }
        return switch (macro.toLowerCase(Locale.ROOT)) {
            case "tameworkinteractionbridge" -> interactionBridge(operation);
            case "tameworkhookinstruction" -> hookInstruction(operation);
            case "tameworkstateinstruction" -> stateInstruction(operation);
            default -> throw new IllegalArgumentException("Unsupported macro '" + macro + "'.");
        };
    }

    @Nonnull
    private List<AssetPatchOperation> interactionBridge(@Nonnull AssetPatchOperation operation) {
        JsonObject options = options(operation);
        JsonObject actionFields = objectOption(options, "ActionFields", new JsonObject());
        JsonObject prompt = action("TameworkInteractPrompt", actionFields);
        JsonObject interact = action("TameworkInteract", actionFields);
        JsonObject promptBranch = branchWithActions("Any", true, prompt);
        JsonObject interactBranch = branchWithActions(
                "HasInteracted",
                false,
                lockAction("InteractionTarget"),
                lockAction("MasterTarget"),
                interact
        );
        return List.of(
                AssetPatchOperation.raw(
                        operation.getId() + ".prompt",
                        "Insert",
                        operation.getPath(),
                        operation.getPosition(),
                        operation.isRequired(),
                        promptBranch,
                        operation.getFind(),
                        matcherForAction("TameworkInteractPrompt")
                ),
                AssetPatchOperation.raw(
                        operation.getId() + ".interact",
                        "Insert",
                        operation.getPath(),
                        "End",
                        operation.isRequired(),
                        interactBranch,
                        null,
                        matcherForAction("TameworkInteract")
                )
        );
    }

    @Nonnull
    private List<AssetPatchOperation> hookInstruction(@Nonnull AssetPatchOperation operation) {
        JsonObject options = options(operation);
        String hookId = requiredStringOption(options, "HookId", operation.getId());
        boolean consume = booleanOption(options, "Consume", true);
        JsonObject branch = parseObject("""
                {
                  "Enabled": { "Compute": "true" },
                  "Continue": true,
                  "Sensor": {
                    "Type": "TameworkHook"
                  },
                  "Instructions": []
                }
                """);
        branch.getAsJsonObject("Sensor").addProperty("HookId", hookId);
        branch.getAsJsonObject("Sensor").addProperty("Consume", consume);
        JsonElement instructions = options.get("Instructions");
        if (instructions != null && instructions.isJsonArray()) {
            branch.add("Instructions", instructions.deepCopy());
        }
        return List.of(AssetPatchOperation.raw(
                operation.getId() + ".hook",
                "Insert",
                operation.getPath(),
                operation.getPosition(),
                operation.isRequired(),
                branch,
                operation.getFind(),
                matcherForSensor("TameworkHook", hookId)
        ));
    }

    @Nonnull
    private List<AssetPatchOperation> stateInstruction(@Nonnull AssetPatchOperation operation) {
        JsonObject options = options(operation);
        String component = requiredStringOption(options, "Component", operation.getId());
        JsonObject branch = parseObject("""
                {
                  "Enabled": { "Compute": "true" },
                  "Continue": true,
                  "Instructions": []
                }
                """);
        JsonObject instruction = new JsonObject();
        instruction.addProperty("Component", component);
        branch.getAsJsonArray("Instructions").add(instruction);
        JsonElement enabled = options.get("Enabled");
        if (enabled != null) {
            branch.add("Enabled", enabled.deepCopy());
        }
        JsonElement sensor = options.get("Sensor");
        if (sensor != null) {
            branch.add("Sensor", sensor.deepCopy());
        }
        return List.of(AssetPatchOperation.raw(
                operation.getId() + ".state",
                "Insert",
                operation.getPath(),
                operation.getPosition(),
                operation.isRequired(),
                branch,
                operation.getFind(),
                matcherForComponent(component)
        ));
    }

    @Nonnull
    private static JsonObject options(@Nonnull AssetPatchOperation operation) {
        JsonObject options = operation.getOptions();
        return options == null ? new JsonObject() : options;
    }

    @Nonnull
    private static JsonObject action(@Nonnull String type, @Nonnull JsonObject fields) {
        JsonObject action = fields.deepCopy();
        action.addProperty("Type", type);
        return action;
    }

    @Nonnull
    private static JsonObject lockAction(@Nonnull String targetSlot) {
        JsonObject action = new JsonObject();
        action.addProperty("Type", "LockOnInteractionTarget");
        action.addProperty("TargetSlot", targetSlot);
        return action;
    }

    @Nonnull
    private static JsonObject branchWithActions(@Nonnull String sensorType,
                                                boolean continues,
                                                @Nonnull JsonObject... actions) {
        JsonObject branch = parseObject("""
                {
                  "Enabled": { "Compute": "true" },
                  "Sensor": {},
                  "Actions": []
                }
                """);
        if (continues) {
            branch.addProperty("Continue", true);
        }
        branch.getAsJsonObject("Sensor").addProperty("Type", sensorType);
        for (JsonObject action : actions) {
            branch.getAsJsonArray("Actions").add(action);
        }
        return branch;
    }

    @Nonnull
    private static JsonObject matcherForAction(@Nonnull String type) {
        JsonObject arrayAction = new JsonObject();
        arrayAction.addProperty("Type", type);
        JsonObject result = new JsonObject();
        result.add("Actions", arrayContainsMatcher(arrayAction));
        return result;
    }

    @Nonnull
    private static JsonObject matcherForSensor(@Nonnull String type, @Nonnull String hookId) {
        JsonObject sensor = new JsonObject();
        sensor.addProperty("Type", type);
        sensor.addProperty("HookId", hookId);
        JsonObject result = new JsonObject();
        result.add("Sensor", sensor);
        return result;
    }

    @Nonnull
    private static JsonObject matcherForComponent(@Nonnull String component) {
        JsonObject instruction = new JsonObject();
        instruction.addProperty("Component", component);
        JsonObject result = new JsonObject();
        result.add("Instructions", arrayContainsMatcher(instruction));
        return result;
    }

    @Nonnull
    private static JsonObject arrayContainsMatcher(@Nonnull JsonObject matcher) {
        JsonObject wrapper = new JsonObject();
        wrapper.add("$Contains", matcher);
        return wrapper;
    }

    @Nonnull
    private static JsonObject objectOption(@Nonnull JsonObject options,
                                           @Nonnull String name,
                                           @Nonnull JsonObject fallback) {
        JsonElement element = options.get(name);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        if (!element.isJsonObject()) {
            throw new IllegalArgumentException(name + " must be an object.");
        }
        return element.getAsJsonObject();
    }

    @Nonnull
    private static String requiredStringOption(@Nonnull JsonObject options,
                                               @Nonnull String name,
                                               @Nonnull String context) {
        JsonElement element = options.get(name);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()
                || element.getAsString().isBlank()) {
            throw new IllegalArgumentException(context + " requires option " + name + ".");
        }
        return element.getAsString();
    }

    private static boolean booleanOption(@Nonnull JsonObject options, @Nonnull String name, boolean fallback) {
        JsonElement element = options.get(name);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(name + " must be a boolean.");
        }
        return element.getAsBoolean();
    }

    @Nonnull
    private static JsonObject parseObject(@Nonnull String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }
}
