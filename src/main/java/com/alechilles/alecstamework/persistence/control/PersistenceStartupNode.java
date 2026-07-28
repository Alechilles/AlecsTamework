package com.alechilles.alecstamework.persistence.control;

import java.util.Set;

/** Fixed replacement startup DAG; readiness is derived from completed nodes. */
public enum PersistenceStartupNode {
    OPEN_TARGET(Set.of()),
    VALIDATE_SCHEMA(Set.of(OPEN_TARGET)),
    LOAD_CANONICAL(Set.of(VALIDATE_SCHEMA)),
    RECOVER_OPERATIONS(Set.of(LOAD_CANONICAL)),
    BUILD_PROJECTIONS(Set.of(RECOVER_OPERATIONS)),
    LOAD_FEATURE_DETAIL(Set.of(BUILD_PROJECTIONS)),
    WAIT_WORLD_EVIDENCE(Set.of(LOAD_FEATURE_DETAIL)),
    RECONCILE_WORLD(Set.of(WAIT_WORLD_EVIDENCE)),
    PUBLISH_READ_READINESS(Set.of(RECONCILE_WORLD)),
    PUBLISH_MUTATION_READINESS(Set.of(PUBLISH_READ_READINESS));

    private final Set<PersistenceStartupNode> dependencies;

    PersistenceStartupNode(Set<PersistenceStartupNode> dependencies) {
        this.dependencies = Set.copyOf(dependencies);
    }

    public Set<PersistenceStartupNode> dependencies() {
        return dependencies;
    }
}
