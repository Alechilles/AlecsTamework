package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwInteractionConfig;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.InteractionEntry;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;

// Selects the first matching interaction entry, respecting cooldowns and requirements.
final class InteractionSelector {
    private final ActionTameworkInteract owner;
    private final TameworkInteractRequirements requirements;

    InteractionSelector(ActionTameworkInteract owner, TameworkInteractRequirements requirements) {
        this.owner = owner;
        this.requirements = requirements;
    }

    // Returns the first interaction entry that passes requirements and cooldown checks.
    ActionTameworkInteract.ResolvedInteraction selectInteraction(TwInteractionConfig config,
                                                                  Ref<EntityStore> npcRef,
                                                                  Role role,
                                                                  InfoProvider infoProvider,
                                                                  Store<EntityStore> store,
                                                                  Player player,
                                                                  InteractionContextSnapshot ctx) {
        InteractionEntry[] entries = config.getInteractions();
        for (int index = 0; index < entries.length; index++) {
            InteractionEntry entry = entries[index];
            if (entry == null || !entry.isEnabled()) {
                continue;
            }
            int cooldownSeconds = owner.resolveCooldownSeconds(config, entry);
            String cooldownAlarmName = cooldownSeconds > 0
                    ? owner.buildCooldownAlarmName(config, index)
                    : null;
            if (cooldownSeconds > 0
                    && (cooldownAlarmName == null || !owner.isCooldownReady(npcRef, store, cooldownAlarmName))) {
                continue;
            }
            if (requirements.requirementsMet(entry, npcRef, role, infoProvider, store, player, ctx)) {
                return new ActionTameworkInteract.ResolvedInteraction(entry, index, cooldownSeconds, cooldownAlarmName);
            }
        }
        return null;
    }
}
