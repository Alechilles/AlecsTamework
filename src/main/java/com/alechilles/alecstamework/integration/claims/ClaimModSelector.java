package com.alechilles.alecstamework.integration.claims;

import com.alechilles.alecstamework.settings.ClaimModChoice;
import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import java.util.function.Predicate;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Decides which claim mod the claim integration reads. The answer is computed for each check, so
 * installing, removing or switching a claim mod needs no Tamework restart.
 */
public final class ClaimModSelector {
    /** SimpleClaims' plugin id from its manifest. */
    public static final String SIMPLE_CLAIMS_PLUGIN_ID = "Buuz135:SimpleClaims";
    /** QuestLines Claims' plugin id from its manifest. */
    public static final String QUESTLINES_CLAIMS_PLUGIN_ID = "net.evilcraft:QuestLinesClaims";

    private static final PluginIdentifier SIMPLE_CLAIMS = PluginIdentifier.fromString(SIMPLE_CLAIMS_PLUGIN_ID);
    private static final PluginIdentifier QUESTLINES_CLAIMS =
            PluginIdentifier.fromString(QUESTLINES_CLAIMS_PLUGIN_ID);

    /** Answers from the server's plugin list: a mod is installed when it is loaded or available. */
    public static final Predicate<ClaimModChoice> SERVER_PLUGINS = ClaimModSelector::isInstalledOnServer;

    private ClaimModSelector() {
    }

    /**
     * The claim mod to read: {@link ClaimModChoice#SIMPLE_CLAIMS} or
     * {@link ClaimModChoice#QUESTLINES_CLAIMS}, never Automatic. An explicit choice is kept even
     * when that mod is absent; its checks then report it unavailable. Automatic picks SimpleClaims
     * when it is installed, otherwise QuestLines Claims when that is installed. With neither
     * installed it answers SimpleClaims, whose checks report it unavailable exactly as before this
     * choice existed.
     */
    @Nonnull
    public static ClaimModChoice select(@Nullable ClaimModChoice choice,
                                        @Nonnull Predicate<ClaimModChoice> installed) {
        if (choice == ClaimModChoice.SIMPLE_CLAIMS || choice == ClaimModChoice.QUESTLINES_CLAIMS) {
            return choice;
        }
        if (!installed.test(ClaimModChoice.SIMPLE_CLAIMS) && installed.test(ClaimModChoice.QUESTLINES_CLAIMS)) {
            return ClaimModChoice.QUESTLINES_CLAIMS;
        }
        return ClaimModChoice.SIMPLE_CLAIMS;
    }

    private static boolean isInstalledOnServer(@Nonnull ClaimModChoice mod) {
        PluginIdentifier id = switch (mod) {
            case SIMPLE_CLAIMS -> SIMPLE_CLAIMS;
            case QUESTLINES_CLAIMS -> QUESTLINES_CLAIMS;
            case AUTOMATIC -> null;
        };
        if (id == null) {
            return false;
        }
        try {
            PluginManager manager = PluginManager.get();
            return manager != null
                    && (manager.getPlugin(id) != null || manager.getAvailablePlugins().containsKey(id));
        } catch (RuntimeException | LinkageError unavailable) {
            return false;
        }
    }
}
