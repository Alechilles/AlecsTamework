package com.alechilles.alecstamework.persistence.health;

import javax.annotation.Nonnull;

/** Named authority dimensions used by mutation admission and diagnostics. */
public enum PersistenceEvidenceDimension {
    CANONICAL_PROFILE_CATALOG("canonical_profile_catalog"),
    OWNER_POPULATION_CATALOG("owner_population_catalog"),
    GLOBAL_OWNER_COUNTS("global_owner_counts"),
    PER_WORLD_OWNER_COUNTS("per_world_owner_counts"),
    PHYSICAL_CLAIM_OCCUPANCY("physical_claim_occupancy"),
    SAVED_WORLD_ENTITIES("saved_world_entities"),
    BASE_CONTAINER_EVIDENCE("base_container_evidence"),
    PERSISTED_PLAYER_INVENTORIES("persisted_player_inventories"),
    LIVE_PLAYER_OVERLAY("live_player_overlay"),
    CUSTOM_CONTAINER_EVIDENCE("custom_container_evidence"),
    LOADED_PROJECTION_IDENTITIES("loaded_projection_identities"),
    MANAGED_COOP_CATALOG("managed_coop_catalog"),
    BREEDING_REPLAY_JOURNAL("breeding_replay_journal"),
    OPERATION_JOURNAL("operation_journal");

    private final String key;

    PersistenceEvidenceDimension(String key) {
        this.key = key;
    }

    @Nonnull
    public String key() {
        return key;
    }
}
