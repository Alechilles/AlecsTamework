package com.alechilles.alecstamework.api;

/** Stable persistence feature/domain identifiers accepted by availability queries. */
public enum PersistenceMutationDomain {
    ALL_PERSISTENCE,
    TAMING_OWNERSHIP,
    OWNER_MUTATION,
    ADMIN_TAMED_SPAWN,
    TAMED_SPAWN,
    CAPTURE_INTAKE,
    CAPTURE_RELEASE,
    MANAGED_COOP_INTAKE,
    MANAGED_COOP_RELEASE,
    MANAGED_COOP_AUTOMATION,
    BREEDING_PAIRING,
    BREEDING_BIRTH,
    BREEDING,
    DEATH_LOST_RECOVERY,
    RECALL_RELOCATION,
    AUTOMATIC_SCOPED_RECOVERY,
    RECONCILIATION
}
