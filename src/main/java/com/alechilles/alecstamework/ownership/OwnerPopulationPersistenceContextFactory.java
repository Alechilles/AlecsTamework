package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.persistence.health.PersistenceEvidenceDimension;
import com.alechilles.alecstamework.persistence.incidents.PersistenceDomain;
import com.alechilles.alecstamework.persistence.incidents.PersistenceScope;
import com.alechilles.alecstamework.persistence.incidents.PersistenceScopeFactory;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Derives the narrow persistence domain and durable scopes represented by an owner plan. */
final class OwnerPopulationPersistenceContextFactory {
    private static final Set<String> OWNER_COVERAGE = Set.of(
            PersistenceEvidenceDimension.CANONICAL_PROFILE_CATALOG.key(),
            PersistenceEvidenceDimension.OWNER_POPULATION_CATALOG.key());

    private final PersistenceScopeFactory scopes;

    OwnerPopulationPersistenceContextFactory(@Nonnull PersistenceScopeFactory scopes) {
        this.scopes = scopes;
    }

    @Nonnull
    Context create(@Nonnull OwnerPopulationAdmissionPlan plan) {
        JsonObject target = parseTarget(plan.targetContextJson());
        PersistenceDomain domain = domain(plan, target);
        List<PersistenceScope> exactScopes = ownerScopes(plan);
        addOperationScope(exactScopes, target);
        addCoopScopes(exactScopes, target);
        addBreedingScopes(exactScopes, target);
        return new Context(domain, List.copyOf(exactScopes), coverage(domain));
    }

    @Nonnull
    private PersistenceDomain domain(OwnerPopulationAdmissionPlan plan, JsonObject target) {
        PersistenceDomain explicit = explicitDomain(target);
        if (explicit != null) return explicit;
        String source = lower(plan.source());
        if (source.startsWith("coop_capture")) return PersistenceDomain.MANAGED_COOP_INTAKE;
        if (source.startsWith("coop_release")) return PersistenceDomain.MANAGED_COOP_RELEASE;
        if (source.startsWith("breeding")) return PersistenceDomain.BREEDING_BIRTH;
        if (source.equals("command_spawn")) return PersistenceDomain.ADMIN_TAMED_SPAWN;
        if (source.equals("spawner_release")) return PersistenceDomain.CAPTURE_RELEASE;
        if (source.equals("dead_restore") || source.equals("lost_restore")) {
            return PersistenceDomain.DEATH_LOST_RECOVERY;
        }
        return operationDomain(plan.transition().operation());
    }

    @Nullable
    private PersistenceDomain explicitDomain(JsonObject target) {
        String value = text(target, "persistenceDomain");
        if (value == null) return null;
        try {
            return PersistenceDomain.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    @Nonnull
    private PersistenceDomain operationDomain(OwnerPopulationOperation operation) {
        return switch (operation) {
            case BREEDING -> PersistenceDomain.BREEDING_BIRTH;
            case REHOME -> PersistenceDomain.RECALL_RELOCATION;
            case NEW_OWNERSHIP, OWNER_TRANSFER, OWNER_CLEAR -> PersistenceDomain.TAMING_OWNERSHIP;
            case ADMIN_FORCE -> PersistenceDomain.ADMIN_TAMED_SPAWN;
            case RESTORE, LEGACY_ADOPTION -> PersistenceDomain.TAMED_SPAWN;
            case LIFECYCLE_CHANGE -> PersistenceDomain.OWNER_MUTATION;
        };
    }

    @Nonnull
    private List<PersistenceScope> ownerScopes(OwnerPopulationAdmissionPlan plan) {
        OwnerPopulationTransitionRequest transition = plan.transition();
        List<PersistenceScope> result = new ArrayList<>();
        result.add(scopes.profile(transition.profileId()));
        addOwner(result, transition.expectedOwnerId(), transition.sourceWorldName());
        addOwner(result, transition.newOwnerId(), transition.destinationWorldName());
        return result;
    }

    private void addOwner(List<PersistenceScope> result, UUID ownerId, String worldName) {
        if (ownerId == null) return;
        result.add(scopes.ownerGlobal(ownerId));
        result.add(scopes.ownerWorld(ownerId, worldName));
    }

    private void addOperationScope(List<PersistenceScope> result, JsonObject target) {
        String operationId = firstText(target, "operationId", "idempotencyKey");
        if (operationId != null) result.add(scopes.operation(operationId));
    }

    private void addCoopScopes(List<PersistenceScope> result, JsonObject target) {
        JsonObject mutation = object(target, "managedCoopMutation");
        if (mutation == null) return;
        String world = text(mutation, "worldName");
        Integer x = integer(mutation, "x");
        Integer y = integer(mutation, "y");
        Integer z = integer(mutation, "z");
        if (world == null || x == null || y == null || z == null) return;
        String authority = new ManagedCoopAuthorityKey(world, x, y, z).authorityId();
        result.add(scopes.coopAuthority(authority));
        Integer slot = integer(mutation, "residentSlot");
        if (slot != null && slot >= 0) result.add(scopes.coopSlot(authority, slot));
    }

    private void addBreedingScopes(List<PersistenceScope> result, JsonObject target) {
        String attempt = text(target, "idempotencyKey");
        if (attempt != null && attempt.startsWith("breeding:")) {
            result.add(scopes.breedingAttempt(attempt));
        }
        JsonArray parents = array(target, "parentProfileIds");
        if (parents == null) return;
        for (JsonElement element : parents) {
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                String profile = trim(element.getAsString());
                if (!profile.isEmpty()) result.add(scopes.breedingParent(profile));
            }
        }
    }

    @Nonnull
    private Set<String> coverage(PersistenceDomain domain) {
        LinkedHashSet<String> required = new LinkedHashSet<>(OWNER_COVERAGE);
        if (domain == PersistenceDomain.MANAGED_COOP_INTAKE
                || domain == PersistenceDomain.MANAGED_COOP_RELEASE) {
            required.add(PersistenceEvidenceDimension.MANAGED_COOP_CATALOG.key());
        }
        if (domain == PersistenceDomain.BREEDING_BIRTH) {
            required.add(PersistenceEvidenceDimension.BREEDING_REPLAY_JOURNAL.key());
        }
        return Set.copyOf(required);
    }

    @Nonnull
    private JsonObject parseTarget(@Nullable String json) {
        if (json == null || json.isBlank()) return new JsonObject();
        try {
            JsonElement parsed = JsonParser.parseString(json);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException invalid) {
            return new JsonObject();
        }
    }

    @Nullable
    private JsonObject object(JsonObject source, String field) {
        JsonElement value = source.get(field);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    @Nullable
    private JsonArray array(JsonObject source, String field) {
        JsonElement value = source.get(field);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : null;
    }

    @Nullable
    private String firstText(JsonObject source, String first, String second) {
        String value = text(source, first);
        return value == null ? text(source, second) : value;
    }

    @Nullable
    private String text(JsonObject source, String field) {
        JsonElement value = source.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return null;
        String normalized = trim(value.getAsString());
        return normalized.isEmpty() ? null : normalized;
    }

    @Nullable
    private Integer integer(JsonObject source, String field) {
        JsonElement value = source.get(field);
        try {
            return value != null && value.isJsonPrimitive() ? value.getAsInt() : null;
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    @Nonnull
    private String trim(@Nullable String value) {
        return value == null ? "" : value.trim();
    }

    @Nonnull
    private String lower(@Nullable String value) {
        return trim(value).toLowerCase(Locale.ROOT);
    }

    record Context(@Nonnull PersistenceDomain domain,
                   @Nonnull List<PersistenceScope> scopes,
                   @Nonnull Set<String> requiredCoverage) {
    }
}
