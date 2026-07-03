package com.alechilles.alecstamework.commands;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.cosmetics.CosmeticsModule;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSkinComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;

/**
 * Temporarily swaps the executing player's model to test player-driven mount-control concepts.
 */
public final class TameworkDebugPlayerModelCommand extends AbstractPlayerCommand {
    private static final String DEFAULT_MODEL_ID = "Endgame_Pet_Dragon_Frost";
    private static final ConcurrentHashMap<UUID, Model> SAVED_MODELS = new ConcurrentHashMap<>();

    public TameworkDebugPlayerModelCommand() {
        super("debugplayermodel", "Temporarily swap your player model for mount-control testing.");
        setAllowsExtraArguments(true);
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> ref,
                           @Nonnull PlayerRef playerRef,
                           @Nonnull World world) {
        UUID playerUuid = playerRef.getUuid();
        if (playerUuid == null) {
            commandContext.sender().sendMessage(Message.raw("Player UUID is not available."));
            return;
        }

        String[] args = getArgs(commandContext);
        if (args.length > 0 && isResetArg(args[0])) {
            restoreModel(commandContext, store, ref, playerUuid);
            return;
        }
        if (args.length > 0 && "status".equals(args[0].toLowerCase(Locale.ROOT))) {
            sendStatus(commandContext, store, ref, playerUuid);
            return;
        }

        String modelId = args.length > 0 ? args[0] : DEFAULT_MODEL_ID;
        ModelAsset modelAsset = ModelAsset.getAssetMap().getAsset(modelId);
        if (modelAsset == null) {
            commandContext.sender().sendMessage(Message.raw(
                    "Model asset not found: " + modelId
                            + ". Usage: /tw debugplayermodel [ModelId] [scale] | reset | status"
            ));
            return;
        }

        Float requestedScale = args.length > 1 ? parseScale(args[1]) : null;
        if (args.length > 1 && requestedScale == null) {
            commandContext.sender().sendMessage(Message.raw("Invalid scale: " + args[1]));
            return;
        }

        ModelComponent currentModel = store.getComponent(ref, ModelComponent.getComponentType());
        if (currentModel != null && currentModel.getModel() != null) {
            SAVED_MODELS.putIfAbsent(playerUuid, new Model(currentModel.getModel()));
        }

        float scale = requestedScale != null ? clampScale(modelAsset, requestedScale) : clampScale(modelAsset, 1.0f);
        store.putComponent(ref, ModelComponent.getComponentType(),
                new ModelComponent(Model.createScaledModel(modelAsset, scale)));

        commandContext.sender().sendMessage(Message.raw(
                "Player model debug: set model=" + modelId + " scale=" + scale
                        + ". Use /tw debugplayermodel reset to restore."
        ));
    }

    private static void restoreModel(CommandContext commandContext,
                                     Store<EntityStore> store,
                                     Ref<EntityStore> ref,
                                     UUID playerUuid) {
        Model savedModel = SAVED_MODELS.remove(playerUuid);
        if (savedModel != null) {
            store.putComponent(ref, ModelComponent.getComponentType(), new ModelComponent(new Model(savedModel)));
            commandContext.sender().sendMessage(Message.raw("Player model debug: restored saved player model."));
            return;
        }

        PlayerSkinComponent skin = store.getComponent(ref, PlayerSkinComponent.getComponentType());
        if (skin != null) {
            Model fallbackModel = CosmeticsModule.get().createModel(skin.getPlayerSkin());
            store.putComponent(ref, ModelComponent.getComponentType(), new ModelComponent(fallbackModel));
            skin.setNetworkOutdated();
            commandContext.sender().sendMessage(Message.raw("Player model debug: restored model from player skin."));
            return;
        }

        commandContext.sender().sendMessage(Message.raw(
                "Player model debug: no saved player model or skin fallback is available."
        ));
    }

    private static void sendStatus(CommandContext commandContext,
                                   Store<EntityStore> store,
                                   Ref<EntityStore> ref,
                                   UUID playerUuid) {
        ModelComponent currentModel = store.getComponent(ref, ModelComponent.getComponentType());
        String currentId = currentModel != null && currentModel.getModel() != null
                ? currentModel.getModel().getModelAssetId()
                : "<none>";
        Model savedModel = SAVED_MODELS.get(playerUuid);
        String savedId = savedModel != null ? savedModel.getModelAssetId() : "<none>";
        commandContext.sender().sendMessage(Message.raw(
                "Player model debug: current=" + currentId + ", saved=" + savedId
        ));
    }

    private static String[] getArgs(CommandContext commandContext) {
        String input = commandContext.getInputString();
        if (input == null || input.isBlank()) {
            return new String[0];
        }
        String[] tokens = input.trim().split("\\s+");
        if (tokens.length <= 2) {
            return new String[0];
        }
        return Arrays.copyOfRange(tokens, 2, tokens.length);
    }

    private static boolean isResetArg(String raw) {
        String value = raw.toLowerCase(Locale.ROOT);
        return "reset".equals(value) || "restore".equals(value) || "off".equals(value) || "clear".equals(value);
    }

    private static Float parseScale(String raw) {
        try {
            return Float.parseFloat(raw);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static float clampScale(ModelAsset modelAsset, float scale) {
        return Math.max(modelAsset.getMinScale(), Math.min(modelAsset.getMaxScale(), scale));
    }
}
