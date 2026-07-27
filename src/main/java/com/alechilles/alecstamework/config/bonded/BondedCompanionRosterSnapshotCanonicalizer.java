package com.alechilles.alecstamework.config.bonded;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Canonicalizes the two public views of one immutable bonded-roster snapshot.
 *
 * <p>The compatibility representative view is derived from the complete
 * family map and therefore cannot select one arbitrary family from an
 * ambiguous roster.</p>
 */
final class BondedCompanionRosterSnapshotCanonicalizer {
    private BondedCompanionRosterSnapshotCanonicalizer() {
    }

    static CanonicalMaps canonicalize(
            Map<String, BondedCompanionRosterRegistry.RosterDefinition>
                    suppliedRepresentatives,
            Map<String, Map<String, BondedCompanionRosterRegistry.RosterDefinition>>
                    suppliedFamilies
    ) {
        Map<String, Map<String, BondedCompanionRosterRegistry.RosterDefinition>>
                families = immutableFamilies(suppliedFamilies);
        Map<String, BondedCompanionRosterRegistry.RosterDefinition>
                expectedRepresentatives = representatives(families);
        if (!immutableRepresentatives(suppliedRepresentatives).equals(
                expectedRepresentatives)) {
            throw new IllegalArgumentException(
                    "Incoherent bonded roster representative map."
            );
        }
        return new CanonicalMaps(expectedRepresentatives, families);
    }

    static Map<String, Map<String, BondedCompanionRosterRegistry.RosterDefinition>>
            singletonFamilies(
                    Map<String, BondedCompanionRosterRegistry.RosterDefinition>
                            definitions
            ) {
        TreeMap<String, Map<String, BondedCompanionRosterRegistry.RosterDefinition>>
                families = new TreeMap<>();
        for (Map.Entry<String, BondedCompanionRosterRegistry.RosterDefinition>
                entry : Objects.requireNonNull(definitions, "byRosterId")
                .entrySet()) {
            BondedCompanionRosterRegistry.RosterDefinition definition =
                    Objects.requireNonNull(entry.getValue(), "definition");
            families.put(entry.getKey(), Map.of(
                    definition.familyId(), definition
            ));
        }
        return families;
    }

    private static Map<String, Map<String,
            BondedCompanionRosterRegistry.RosterDefinition>> immutableFamilies(
            Map<String, Map<String, BondedCompanionRosterRegistry.RosterDefinition>>
                    source
    ) {
        TreeMap<String, Map<String, BondedCompanionRosterRegistry.RosterDefinition>>
                ordered = new TreeMap<>();
        for (Map.Entry<String, Map<String,
                BondedCompanionRosterRegistry.RosterDefinition>> roster
                : Objects.requireNonNull(source, "familiesByRosterId")
                .entrySet()) {
            String rosterId = exactKey(roster.getKey(), "rosterId");
            TreeMap<String, BondedCompanionRosterRegistry.RosterDefinition>
                    definitions = new TreeMap<>();
            for (Map.Entry<String,
                    BondedCompanionRosterRegistry.RosterDefinition> family
                    : Objects.requireNonNull(roster.getValue(), "families")
                    .entrySet()) {
                String familyId = exactKey(family.getKey(), "familyId");
                BondedCompanionRosterRegistry.RosterDefinition definition =
                        Objects.requireNonNull(
                                family.getValue(), "family definition"
                        );
                if (!rosterId.equals(definition.rosterId())
                        || !familyId.equals(definition.familyId())) {
                    throw new IllegalArgumentException(
                            "Incoherent bonded roster family map."
                    );
                }
                definitions.put(familyId, definition);
            }
            if (definitions.isEmpty()) {
                throw new IllegalArgumentException(
                        "Bonded roster must define at least one family: "
                                + rosterId
                );
            }
            ordered.put(rosterId, Collections.unmodifiableMap(
                    new LinkedHashMap<>(definitions)
            ));
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(ordered));
    }

    private static Map<String, BondedCompanionRosterRegistry.RosterDefinition>
            representatives(
                    Map<String, Map<String,
                            BondedCompanionRosterRegistry.RosterDefinition>>
                            families
            ) {
        LinkedHashMap<String, BondedCompanionRosterRegistry.RosterDefinition>
                result = new LinkedHashMap<>();
        families.forEach((rosterId, definitions) -> {
            if (definitions.size() == 1) {
                result.put(rosterId, definitions.values().iterator().next());
            }
        });
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, BondedCompanionRosterRegistry.RosterDefinition>
            immutableRepresentatives(
                    Map<String, BondedCompanionRosterRegistry.RosterDefinition>
                            source
            ) {
        TreeMap<String, BondedCompanionRosterRegistry.RosterDefinition> ordered =
                new TreeMap<>();
        for (Map.Entry<String, BondedCompanionRosterRegistry.RosterDefinition>
                entry : Objects.requireNonNull(source, "byRosterId").entrySet()) {
            ordered.put(
                    exactKey(entry.getKey(), "rosterId"),
                    Objects.requireNonNull(entry.getValue(), "definition")
            );
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(ordered));
    }

    private static String exactKey(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty() || !normalized.equals(value)) {
            throw new IllegalArgumentException(
                    "Invalid bonded roster " + field + " key."
            );
        }
        return normalized;
    }

    record CanonicalMaps(
            Map<String, BondedCompanionRosterRegistry.RosterDefinition>
                    representatives,
            Map<String, Map<String, BondedCompanionRosterRegistry.RosterDefinition>>
                    families
    ) {
    }
}
