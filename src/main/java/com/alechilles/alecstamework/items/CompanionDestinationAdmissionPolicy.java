package com.alechilles.alecstamework.items;

import com.hypixel.hytale.server.core.universe.world.World;
import javax.annotation.Nonnull;

/**
 * Decides whether a command action may place a live companion in a destination world.
 *
 * <p>Worlds that freeze every NPC cannot host a functional companion. Rejecting them before
 * relocation or restoration also prevents temporary instance teardown from turning a successful
 * command into a misleading Lost transition.</p>
 */
final class CompanionDestinationAdmissionPolicy {
    enum Decision {
        ALLOWED,
        NPCS_FROZEN
    }

    private CompanionDestinationAdmissionPolicy() {
    }

    static Decision assess(@Nonnull World world) {
        return assess(world.getWorldConfig().isAllNPCFrozen());
    }

    static Decision assess(boolean allNpcsFrozen) {
        return allNpcsFrozen ? Decision.NPCS_FROZEN : Decision.ALLOWED;
    }
}
