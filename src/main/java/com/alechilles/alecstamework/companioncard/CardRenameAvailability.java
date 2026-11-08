package com.alechilles.alecstamework.companioncard;

/** Whether the companion card's rename pencil can rename a companion for the viewing player right now. */
public enum CardRenameAvailability {
    /** The pencil works: in Item mode the player carries a usable naming item. */
    AVAILABLE,
    /** Item mode, and the player carries no naming item that can rename this companion now (none, or on cooldown). */
    NEEDS_ITEM,
    /** The player may not rename this companion (for example not its owner), whatever item they carry. */
    NOT_ALLOWED,
    /** The server turned card renaming off. */
    OFF
}
