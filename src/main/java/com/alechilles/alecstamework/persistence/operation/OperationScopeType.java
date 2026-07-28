package com.alechilles.alecstamework.persistence.operation;

/** Ordered containment scopes used for fencing and failure quarantine. */
public enum OperationScopeType {
    OPERATION,
    PROFILE,
    OWNER,
    COOP,
    TOOL,
    COMMAND_FAMILY,
    FEATURE,
    GLOBAL
}
