package com.alechilles.alecstamework.api.access;

/** A group provider's answer to "is this player in the owner's group". */
public enum AccessGroupMembership {
    MEMBER,
    NOT_MEMBER,
    /** The provider cannot tell right now. Tamework treats this as no access. */
    UNKNOWN
}
