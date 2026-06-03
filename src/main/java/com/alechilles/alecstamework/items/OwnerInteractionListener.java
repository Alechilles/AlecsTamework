package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.CommandItemRegistry;
import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.inventory.PlayerInventoryAccess;
import com.alechilles.alecstamework.localization.RoleNameResolver;
import com.alechilles.alecstamework.localization.TranslationRegistry;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.ownership.OwnerMessageUtil;
import com.alechilles.alecstamework.ownership.OwnerNameUtil;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.Entity;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.event.events.player.PlayerInteractEvent;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Blocks interactions with owned NPCs and notifies the player.
 */
public final class OwnerInteractionListener {
    private static final long SLOW_INTERACTION_THRESHOLD_NS = TimeUnit.MILLISECONDS.toNanos(20L);

    private final TranslationRegistry translationRegistry;
    private final HytaleLogger logger;

    public OwnerInteractionListener(TranslationRegistry translationRegistry, HytaleLogger logger) {
        this.translationRegistry = translationRegistry;
        this.logger = logger;
    }

    // Enforce owner-only interactions for NPCs with an owner component.
    public void onPlayerInteract(PlayerInteractEvent event) {
        boolean debugLag = isLagDebugEnabled();
        long startedNs = debugLag ? System.nanoTime() : 0L;
        InteractionType actionType = null;
        Entity target = null;
        Player player = null;
        try {
            if (event == null) {
                return;
            }
            if (event.isCancelled()) {
                return;
            }
            actionType = event.getActionType();
            if (actionType != InteractionType.Use
                    && actionType != InteractionType.Primary
                    && actionType != InteractionType.Secondary) {
                return;
            }
            target = event.getTargetEntity();
            if (!(target instanceof NPCEntity)) {
                return;
            }
            player = event.getPlayer();
            if (player == null) {
                return;
            }
            UUID ownerUuid = null;
            String ownerName = null;
            World world = player.getWorld();
            if (world != null) {
                Store<EntityStore> store = world.getEntityStore().getStore();
                Ref<EntityStore> ref = ((NPCEntity) target).getReference();
                if (ref != null && ref.isValid()) {
                    TameworkOwnerComponent component = store.getComponent(ref, TameworkOwnerComponent.getComponentType());
                    if (component != null) {
                        ownerUuid = component.getOwnerId();
                        ownerName = component.getOwnerName();
                    }
                }
            }
            // Skip restriction when there is no owner or the player is the owner.
            if (ownerUuid == null || ownerUuid.equals(player.getUuid())) {
                return;
            }

            InteractionOwnershipPolicy ownershipPolicy = resolveOwnershipPolicyForHeldItem(player);
            if (!isOwnerRequiredForPolicy(ownershipPolicy, resolveGlobalConfig())) {
                return;
            }

            NPCEntity npc = (NPCEntity) target;
            // Resolve NPC display name with a best-effort fallback chain.
            String npcName = null;
            String displayName = npc.getLegacyDisplayName();
            if (displayName != null && !displayName.isBlank()) {
                npcName = displayName;
            }
            if (npcName == null) {
                NPCPlugin npcPlugin = NPCPlugin.get();
                String roleNameKey = RoleNameResolver.resolveRoleNameKey(npc.getRole());
                if (npcPlugin != null) {
                    int roleIndex = npc.getRoleIndex();
                    if (roleIndex >= 0) {
                        String nameKey = npcPlugin.getName(roleIndex);
                        if (nameKey != null && translationRegistry != null) {
                            String translated = RoleNameResolver.resolveDisplayName(
                                    nameKey,
                                    roleNameKey,
                                    translationRegistry::get
                            );
                            if (translated != null && !translated.isBlank()) {
                                npcName = translated;
                            }
                        }
                    }
                }
            }
            if (npcName == null) {
                String roleName = npc.getRoleName();
                if (roleName != null && !roleName.isBlank()) {
                    if (translationRegistry != null) {
                        String roleNameKey = RoleNameResolver.resolveRoleNameKey(npc.getRole());
                        String translated = RoleNameResolver.resolveDisplayName(
                                roleName,
                                roleNameKey,
                                translationRegistry::get
                        );
                        if (translated != null && !translated.isBlank()) {
                            npcName = translated;
                        }
                    }
                    if (npcName == null) {
                        npcName = roleName;
                    }
                }
            }

            event.setCancelled(true);
            OwnerMessageUtil.sendDenied(player, npcName, ownerName, ownerUuid, ownershipPolicy.verb());
            logger.at(Level.FINE).log(
                    "Owner restrict: denied interaction player=" + OwnerNameUtil.resolve(player)
                            + " target=" + target.getUuid()
            );
        } finally {
            if (debugLag) {
                logSlowInteraction(startedNs, actionType, player, target);
            }
        }
    }

    private boolean isLagDebugEnabled() {
        Tamework instance = Tamework.getInstance();
        return instance != null && instance.isDebugLagEnabled();
    }

    private void logSlowInteraction(long startedNs,
                                    InteractionType actionType,
                                    Player player,
                                    Entity target) {
        if (startedNs <= 0L || logger == null) {
            return;
        }
        long elapsedNs = System.nanoTime() - startedNs;
        if (elapsedNs < SLOW_INTERACTION_THRESHOLD_NS) {
            return;
        }
        double elapsedMs = elapsedNs / 1_000_000.0;
        String playerName = player != null ? OwnerNameUtil.resolve(player) : "<unknown>";
        String targetId = target != null && target.getUuid() != null ? target.getUuid().toString() : "<none>";
        logger.at(Level.WARNING).log(
                "Tamework lag probe: owner interaction listener took "
                        + elapsedMs
                        + "ms (action="
                        + actionType
                        + ", player="
                        + playerName
                        + ", target="
                        + targetId
                + ")."
        );
    }

    private InteractionOwnershipPolicy resolveOwnershipPolicyForHeldItem(Player player) {
        if (player == null) {
            return InteractionOwnershipPolicy.INTERACTION;
        }
        ItemStack active = PlayerInventoryAccess.getActiveHotbarItem(player);
        if (active == null || active.isEmpty()) {
            return InteractionOwnershipPolicy.INTERACTION;
        }
        String itemId = active.getItemId();
        if (itemId == null || itemId.isBlank()) {
            return InteractionOwnershipPolicy.INTERACTION;
        }

        Tamework instance = Tamework.getInstance();
        if (instance == null) {
            return InteractionOwnershipPolicy.INTERACTION;
        }

        ItemFeatureRegistry itemFeatureRegistry = instance.getItemFeatureRegistry();
        if (itemFeatureRegistry != null) {
            ItemFeatureConfig spawnerConfig = itemFeatureRegistry.get(itemId);
            if (isSpawnerCaptureTool(spawnerConfig)) {
                return InteractionOwnershipPolicy.CAPTURE;
            }
        }

        CommandItemRegistry commandItemRegistry = instance.getCommandItemRegistry();
        if (commandItemRegistry != null) {
            TwCommandItemConfig commandConfig = commandItemRegistry.get(itemId);
            if (commandConfig != null && commandConfig.isEnabled()) {
                return InteractionOwnershipPolicy.LINKING;
            }
        }
        return InteractionOwnershipPolicy.INTERACTION;
    }

    private boolean isSpawnerCaptureTool(ItemFeatureConfig config) {
        if (config == null || !config.isSpawnerEnabled()) {
            return false;
        }
        String filledItemId = config.getSpawnerFilledItemId();
        return filledItemId != null && !filledItemId.isBlank();
    }

    private TwGlobalConfig resolveGlobalConfig() {
        TwGlobalConfig global = TwGlobalConfig.resolveActive();
        return global != null ? global : TwGlobalConfig.defaultConfig();
    }

    static boolean isOwnerRequiredForPolicy(InteractionOwnershipPolicy policy, TwGlobalConfig globalConfig) {
        TwGlobalConfig resolved = globalConfig != null ? globalConfig : TwGlobalConfig.defaultConfig();
        if (policy == null) {
            return TameworkRuntimeSettings.interactionRequiresOwner(resolved.isOwnershipInteractionRequiresOwner());
        }
        return switch (policy) {
            case CAPTURE -> TameworkRuntimeSettings.captureRequiresOwner(resolved.isOwnershipCaptureRequiresOwner());
            case LINKING -> TameworkRuntimeSettings.linkingRequiresOwner(resolved.isOwnershipLinkingRequiresOwner());
            case INTERACTION -> TameworkRuntimeSettings.interactionRequiresOwner(resolved.isOwnershipInteractionRequiresOwner());
        };
    }

    enum InteractionOwnershipPolicy {
        CAPTURE("capture"),
        LINKING("link"),
        INTERACTION("interact with");

        private final String verb;

        InteractionOwnershipPolicy(String verb) {
            this.verb = verb;
        }

        String verb() {
            return verb;
        }
    }
}
