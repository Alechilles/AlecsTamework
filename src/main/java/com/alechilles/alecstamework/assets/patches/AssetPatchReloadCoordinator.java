package com.alechilles.alecstamework.assets.patches;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.logging.Level;

import javax.annotation.Nonnull;

import com.hypixel.hytale.assetstore.AssetPack;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.npc.NPCPlugin;

/**
 * Reloads generated optional asset patch outputs through the narrow runtime paths known to be safe.
 */
public final class AssetPatchReloadCoordinator {
    private final JavaPlugin plugin;
    private final NpcBuilderReloadAdapter npcBuilderReloadAdapter;

    public AssetPatchReloadCoordinator(@Nonnull JavaPlugin plugin) {
        this(
                plugin,
                new NpcPluginBuilderReloadAdapter()
        );
    }

    AssetPatchReloadCoordinator(@Nonnull JavaPlugin plugin,
                                @Nonnull NpcBuilderReloadAdapter npcBuilderReloadAdapter) {
        this.plugin = plugin;
        this.npcBuilderReloadAdapter = npcBuilderReloadAdapter;
    }

    public void reloadPublishedTargets(@Nonnull AssetPack generatedPack,
                                       @Nonnull Collection<String> affectedTargets,
                                       @Nonnull AssetPatchStatus status) {
        LinkedHashSet<String> normalizedTargets = new LinkedHashSet<>();
        for (String target : affectedTargets) {
            normalizedTargets.add(AssetPatchDefinition.normalizeAssetPath(target));
        }

        boolean reloadNpcBuilders = false;
        for (String target : normalizedTargets) {
            AssetPatchTargetClassification classification = AssetPatchTargetClassifier.classify(target);
            switch (classification.reloadMode()) {
                case NPC_BUILDERS -> reloadNpcBuilders = true;
                case HYTALE_ASSET_STORE, TAMEWORK_CONFIG, RESTART_REQUIRED ->
                        status.addRestartRequiredTarget(target);
            }
        }

        if (reloadNpcBuilders) {
            reloadNpcBuilders(generatedPack, status);
        }
    }

    private void reloadNpcBuilders(@Nonnull AssetPack generatedPack, @Nonnull AssetPatchStatus status) {
        try {
            npcBuilderReloadAdapter.load(generatedPack);
            status.addHotReloadedTarget("Server/NPC/Roles/*");
        } catch (RuntimeException ex) {
            markReloadFailure("Server/NPC/Roles/*", ex, status);
        }
    }

    private void markReloadFailure(@Nonnull String target,
                                   @Nonnull RuntimeException ex,
                                   @Nonnull AssetPatchStatus status) {
        status.addRestartRequiredTarget(target);
        String message = "Failed to hot-reload generated patch target " + target + "; restart required.";
        status.addFailed(message);
        if (plugin != null && plugin.getLogger() != null) {
            plugin.getLogger().at(Level.WARNING).withCause(ex).log(message);
        }
    }

    interface NpcBuilderReloadAdapter {
        void load(@Nonnull AssetPack generatedPack);
    }

    private static final class NpcPluginBuilderReloadAdapter implements NpcBuilderReloadAdapter {
        @Override
        public void load(@Nonnull AssetPack generatedPack) {
            NPCPlugin npcPlugin = NPCPlugin.get();
            if (npcPlugin != null) {
                npcPlugin.getBuilderManager().loadBuilders(generatedPack, true);
            }
        }
    }
}
