package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.config.overrides.TwConfigAssetDescriptor;
import com.alechilles.alecstamework.config.overrides.TwConfigFamily;
import com.alechilles.alecstamework.config.overrides.TwConfigSnapshot;
import com.alechilles.alecstamework.settings.TameworkSettingsOwnedField;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TwConfigEditorFieldPolicyTest {

    @Test
    void assetEditableRequiresKnownAndDescriptorEditable() {
        assertTrue(TwConfigEditorFieldPolicy.isAssetEditable(descriptor(TwConfigFamily.GLOBAL, true, true)));
        assertFalse(TwConfigEditorFieldPolicy.isAssetEditable(descriptor(TwConfigFamily.GLOBAL, false, true)));
        assertFalse(TwConfigEditorFieldPolicy.isAssetEditable(descriptor(TwConfigFamily.GLOBAL, true, false)));
    }

    @Test
    void fallbackInspectorBuildsTypedEditablePrimitivesWhenSchemaUnavailable() {
        JsonObject effective = new JsonObject();
        effective.addProperty("Enabled", true);
        effective.addProperty("Priority", 10);

        List<TwConfigEditorFieldPolicy.EditorFieldSpec> fields = TwConfigEditorFieldPolicy.fieldsFor(
                descriptor(TwConfigFamily.OTHER, false, false),
                null,
                effective
        );

        assertFalse(fields.isEmpty());
        assertTrue(fields.stream().anyMatch(TwConfigEditorFieldPolicy.EditorFieldSpec::isEditableValue));
    }

    @Test
    void fallbackInspectorRecursesNestedObjectsAndBuildsTypedEditableFields() {
        JsonObject effective = new JsonObject();
        effective.addProperty("Parent", "ParentAsset");
        effective.addProperty("$Title", "hidden");

        JsonObject general = new JsonObject();
        general.addProperty("Enabled", true);
        general.addProperty("Priority", 3);
        general.addProperty("Radius", 5.5);
        general.addProperty("Comment", "text");
        JsonArray tags = new JsonArray();
        tags.add("one");
        tags.add("two");
        general.add("Tags", tags);
        effective.add("General", general);

        List<TwConfigEditorFieldPolicy.EditorFieldSpec> fields = TwConfigEditorFieldPolicy.fieldsFor(
                descriptor(TwConfigFamily.OTHER, false, false),
                null,
                effective
        );

        assertNotNull(TwConfigEditorFieldPolicy.findField(fields, "Parent"));
        assertNotNull(TwConfigEditorFieldPolicy.findField(fields, "General.Enabled"));
        assertNotNull(TwConfigEditorFieldPolicy.findField(fields, "General.Priority"));
        assertNotNull(TwConfigEditorFieldPolicy.findField(fields, "General.Radius"));
        assertNotNull(TwConfigEditorFieldPolicy.findField(fields, "General.Comment"));

        TwConfigEditorFieldPolicy.EditorFieldSpec tagsField =
                TwConfigEditorFieldPolicy.findField(fields, "General.Tags");
        assertNotNull(tagsField);
        assertEquals(TwConfigEditorFieldPolicy.EditorFieldType.STRING_LIST, tagsField.type());
        assertTrue(tagsField.isEditableValue());

        assertTrue(fields.stream().noneMatch(field -> field.path().startsWith("$")));
        assertTrue(fields.stream().noneMatch(field -> field.path().equalsIgnoreCase("General")));
    }

    @Test
    void parentOptionsAreScopedToSameFamilyAcrossPacks() {
        TwConfigAssetDescriptor self = descriptor(TwConfigFamily.INTERACTION, true, true, "interaction", "pack-A", "Interaction_A");
        TwConfigAssetDescriptor sibling = descriptor(TwConfigFamily.INTERACTION, true, true, "interaction", "pack-A", "Interaction_B");
        TwConfigAssetDescriptor otherPack = descriptor(TwConfigFamily.INTERACTION, true, true, "interaction", "pack-B", "Interaction_C");
        TwConfigAssetDescriptor unknownType = descriptor(TwConfigFamily.INTERACTION, false, true, "interaction", "pack-C", "Interaction_Unknown");
        TwConfigAssetDescriptor otherFamily = descriptor(TwConfigFamily.SPAWNER, true, true, "spawner", "pack-A", "Spawner_A");

        TwConfigSnapshot snapshot = new TwConfigSnapshot(
                Path.of("overrides"),
                "hash",
                List.of(self, sibling, otherPack, unknownType, otherFamily),
                new LinkedHashMap<>()
        );

        TwConfigEditorFieldPolicy.EditorFieldSpec parentField = TwConfigEditorFieldPolicy.parentField(0);
        List<String> options = TwConfigEditorFieldPolicy.optionsFor(parentField, self, snapshot);
        assertTrue(options.contains("Interaction_B"));
        assertTrue(options.contains("Interaction_C"));
        assertFalse(options.contains("Interaction_A"));
        assertFalse(options.contains("Interaction_Unknown"));
        assertFalse(options.contains("Spawner_A"));
    }

    @Test
    void findFieldMatchesCaseInsensitiveIds() {
        TwConfigEditorFieldPolicy.EditorFieldSpec parent = TwConfigEditorFieldPolicy.parentField(0);
        TwConfigEditorFieldPolicy.EditorFieldSpec found =
                TwConfigEditorFieldPolicy.findField(List.of(parent), "PARENT");
        assertNotNull(found);
        assertTrue(found.parentSelector());
    }

    @Test
    void breedingBasisFieldsUseOptionDropdownFallbackWhenSchemaIsStringBacked() {
        JsonObject effective = new JsonObject();
        JsonObject passiveBreeding = new JsonObject();
        passiveBreeding.addProperty("Basis", "REAL_TIME");
        effective.add("PassiveBreeding", passiveBreeding);
        JsonObject timing = new JsonObject();
        timing.addProperty("Basis", "WORLD_TIME_SCALED");
        effective.add("Timing", timing);

        List<TwConfigEditorFieldPolicy.EditorFieldSpec> fields = TwConfigEditorFieldPolicy.fieldsFor(
                descriptor(TwConfigFamily.BREEDING, true, true),
                null,
                effective
        );

        TwConfigEditorFieldPolicy.EditorFieldSpec passiveBasis =
                TwConfigEditorFieldPolicy.findField(fields, "PassiveBreeding.Basis");
        TwConfigEditorFieldPolicy.EditorFieldSpec timingBasis =
                TwConfigEditorFieldPolicy.findField(fields, "Timing.Basis");
        assertNotNull(passiveBasis);
        assertNotNull(timingBasis);
        assertEquals(TwConfigEditorFieldPolicy.EditorFieldType.OPTION, passiveBasis.type());
        assertEquals(TwConfigEditorFieldPolicy.EditorFieldType.OPTION, timingBasis.type());
        assertTrue(passiveBasis.options().contains("REAL_TIME"));
        assertTrue(passiveBasis.options().contains("WORLD_TIME_SCALED"));
    }

    @Test
    void breedingEditorHidesLegacySameRoleToggleButShowsRoleCompatibility() {
        JsonObject effective = new JsonObject();
        JsonObject pairing = new JsonObject();
        pairing.addProperty("RequireSameRoleId", true);
        pairing.addProperty("RoleCompatibility", "DifferentFamilyRole");
        effective.add("Pairing", pairing);

        JsonObject roleOverrides = new JsonObject();
        JsonObject deerOverride = new JsonObject();
        JsonObject overridePairing = new JsonObject();
        overridePairing.addProperty("RequireSameRoleId", false);
        overridePairing.addProperty("RoleCompatibility", "SameLifecycleFamily");
        deerOverride.add("Pairing", overridePairing);
        roleOverrides.add("Deer_Stag", deerOverride);
        effective.add("RoleOverrides", roleOverrides);

        List<TwConfigEditorFieldPolicy.EditorFieldSpec> fields = TwConfigEditorFieldPolicy.fieldsFor(
                descriptor(TwConfigFamily.BREEDING, true, true),
                null,
                effective
        );

        assertNull(TwConfigEditorFieldPolicy.findField(fields, "Pairing.RequireSameRoleId"));
        assertNull(TwConfigEditorFieldPolicy.findField(fields, "RoleOverrides.Deer_Stag.Pairing.RequireSameRoleId"));

        TwConfigEditorFieldPolicy.EditorFieldSpec compatibility =
                TwConfigEditorFieldPolicy.findField(fields, "Pairing.RoleCompatibility");
        TwConfigEditorFieldPolicy.EditorFieldSpec overrideCompatibility =
                TwConfigEditorFieldPolicy.findField(fields, "RoleOverrides.Deer_Stag.Pairing.RoleCompatibility");
        assertNotNull(compatibility);
        assertNotNull(overrideCompatibility);
        assertEquals(TwConfigEditorFieldPolicy.EditorFieldType.OPTION, compatibility.type());
        assertEquals(TwConfigEditorFieldPolicy.EditorFieldType.OPTION, overrideCompatibility.type());
        assertTrue(compatibility.options().contains("DifferentFamilyRole"));
    }

    @Test
    void settingsOwnedFieldMatrixNormalizesConfiguredPaths() {
        assertTrue(TameworkSettingsOwnedField.isSettingsOwned(TwConfigFamily.GLOBAL, "OwnershipProtection.BlockOwnerDamage"));
        assertTrue(TameworkSettingsOwnedField.isSettingsOwned(TwConfigFamily.GLOBAL, "SimpleClaims.Provider"));
        assertTrue(TameworkSettingsOwnedField.isSettingsOwned(TwConfigFamily.GLOBAL, "SimpleClaims.Breeding.LimitPerClaimChunk"));
        assertTrue(TameworkSettingsOwnedField.isSettingsOwned(TwConfigFamily.NEEDS, "Enabled"));
        assertTrue(TameworkSettingsOwnedField.isSettingsOwned(TwConfigFamily.NEEDS, "Damage.StarvationDamagePerMinute"));
        assertTrue(TameworkSettingsOwnedField.isSettingsOwned(TwConfigFamily.HAPPINESS, "Enabled"));
        assertTrue(TameworkSettingsOwnedField.isSettingsOwned(TwConfigFamily.BREEDING, "PassiveBreeding.Enabled"));
        assertTrue(TameworkSettingsOwnedField.isSettingsOwned(
                TwConfigFamily.BREEDING,
                "RoleOverrides.Deer_Stag.PassiveBreeding.Enabled"
        ));
        assertTrue(TameworkSettingsOwnedField.isSettingsOwned(TwConfigFamily.SPAWNER, "Spawn.AssignsOwner"));
        assertTrue(TameworkSettingsOwnedField.isSettingsOwned(TwConfigFamily.TRAIT, "Enabled"));
        assertTrue(TameworkSettingsOwnedField.isSettingsOwned(TwConfigFamily.COMPANION, "Command.DeadRespawnEnabled"));

        assertFalse(TameworkSettingsOwnedField.isSettingsOwned(TwConfigFamily.BREEDING, "PassiveBreeding.SweepIntervalSeconds"));
        assertFalse(TameworkSettingsOwnedField.isSettingsOwned(
                TwConfigFamily.BREEDING,
                "RoleOverrides.Deer_Stag.PassiveBreeding.SweepIntervalSeconds"
        ));
        assertFalse(TameworkSettingsOwnedField.isSettingsOwned(TwConfigFamily.SPAWNER, "Capture.OwnerRestricted"));
        assertFalse(TameworkSettingsOwnedField.isSettingsOwned(TwConfigFamily.NEEDS, "Values.HungerMin"));
        assertFalse(TameworkSettingsOwnedField.isSettingsOwned(TwConfigFamily.LEVELING, "Enabled"));
        assertFalse(TameworkSettingsOwnedField.isSettingsOwned(TwConfigFamily.TALENT, "Enabled"));
    }

    @Test
    void editorHidesSettingsOwnedFallbackFieldsAcrossFamilies() {
        JsonObject global = new JsonObject();
        JsonObject ownershipProtection = new JsonObject();
        ownershipProtection.addProperty("BlockOwnerDamage", true);
        global.add("OwnershipProtection", ownershipProtection);
        JsonObject interactionDefaults = new JsonObject();
        interactionDefaults.addProperty("HarvestAlarmName", "Harvest");
        global.add("InteractionDefaults", interactionDefaults);

        List<TwConfigEditorFieldPolicy.EditorFieldSpec> globalFields = TwConfigEditorFieldPolicy.fieldsFor(
                descriptor(TwConfigFamily.GLOBAL, true, true),
                null,
                global
        );
        assertNull(TwConfigEditorFieldPolicy.findField(globalFields, "OwnershipProtection.BlockOwnerDamage"));
        assertNotNull(TwConfigEditorFieldPolicy.findField(globalFields, "InteractionDefaults.HarvestAlarmName"));

        JsonObject needs = new JsonObject();
        needs.addProperty("Enabled", true);
        JsonObject damage = new JsonObject();
        damage.addProperty("Enabled", true);
        needs.add("Damage", damage);
        JsonObject values = new JsonObject();
        values.addProperty("HungerMin", 0);
        needs.add("Values", values);

        List<TwConfigEditorFieldPolicy.EditorFieldSpec> needsFields = TwConfigEditorFieldPolicy.fieldsFor(
                descriptor(TwConfigFamily.NEEDS, true, true),
                null,
                needs
        );
        assertNull(TwConfigEditorFieldPolicy.findField(needsFields, "Enabled"));
        assertNull(TwConfigEditorFieldPolicy.findField(needsFields, "Damage.Enabled"));
        assertNotNull(TwConfigEditorFieldPolicy.findField(needsFields, "Values.HungerMin"));

        JsonObject happiness = new JsonObject();
        happiness.addProperty("Enabled", true);
        happiness.addProperty("Priority", 5);
        List<TwConfigEditorFieldPolicy.EditorFieldSpec> happinessFields = TwConfigEditorFieldPolicy.fieldsFor(
                descriptor(TwConfigFamily.HAPPINESS, true, true),
                null,
                happiness
        );
        assertNull(TwConfigEditorFieldPolicy.findField(happinessFields, "Enabled"));
        assertNotNull(TwConfigEditorFieldPolicy.findField(happinessFields, "Priority"));

        JsonObject trait = new JsonObject();
        trait.addProperty("Enabled", true);
        trait.addProperty("Priority", 5);
        List<TwConfigEditorFieldPolicy.EditorFieldSpec> traitFields = TwConfigEditorFieldPolicy.fieldsFor(
                descriptor(TwConfigFamily.TRAIT, true, true),
                null,
                trait
        );
        assertNull(TwConfigEditorFieldPolicy.findField(traitFields, "Enabled"));
        assertNotNull(TwConfigEditorFieldPolicy.findField(traitFields, "Priority"));
    }

    @Test
    void editorHidesSettingsOwnedBreedingSpawnerAndCompanionFields() {
        JsonObject breeding = new JsonObject();
        JsonObject passiveBreeding = new JsonObject();
        passiveBreeding.addProperty("Enabled", true);
        passiveBreeding.addProperty("SweepIntervalSeconds", 60);
        breeding.add("PassiveBreeding", passiveBreeding);
        JsonObject roleOverrides = new JsonObject();
        JsonObject deerOverride = new JsonObject();
        JsonObject overridePassiveBreeding = new JsonObject();
        overridePassiveBreeding.addProperty("Enabled", true);
        overridePassiveBreeding.addProperty("SweepIntervalSeconds", 45);
        deerOverride.add("PassiveBreeding", overridePassiveBreeding);
        roleOverrides.add("Deer_Stag", deerOverride);
        breeding.add("RoleOverrides", roleOverrides);
        List<TwConfigEditorFieldPolicy.EditorFieldSpec> breedingFields = TwConfigEditorFieldPolicy.fieldsFor(
                descriptor(TwConfigFamily.BREEDING, true, true),
                null,
                breeding
        );
        assertNull(TwConfigEditorFieldPolicy.findField(breedingFields, "PassiveBreeding.Enabled"));
        assertNull(TwConfigEditorFieldPolicy.findField(
                breedingFields,
                "RoleOverrides.Deer_Stag.PassiveBreeding.Enabled"
        ));
        assertNotNull(TwConfigEditorFieldPolicy.findField(breedingFields, "PassiveBreeding.SweepIntervalSeconds"));
        assertNotNull(TwConfigEditorFieldPolicy.findField(
                breedingFields,
                "RoleOverrides.Deer_Stag.PassiveBreeding.SweepIntervalSeconds"
        ));

        JsonObject spawner = new JsonObject();
        JsonObject capture = new JsonObject();
        capture.addProperty("ClearsOwner", true);
        capture.addProperty("OwnerRestricted", true);
        spawner.add("Capture", capture);
        List<TwConfigEditorFieldPolicy.EditorFieldSpec> spawnerFields = TwConfigEditorFieldPolicy.fieldsFor(
                descriptor(TwConfigFamily.SPAWNER, true, true),
                null,
                spawner
        );
        assertNull(TwConfigEditorFieldPolicy.findField(spawnerFields, "Capture.ClearsOwner"));
        assertNotNull(TwConfigEditorFieldPolicy.findField(spawnerFields, "Capture.OwnerRestricted"));

        JsonObject companion = new JsonObject();
        JsonObject command = new JsonObject();
        command.addProperty("DeadRespawnEnabled", true);
        command.addProperty("DeadRespawnCooldownMs", 5000);
        companion.add("Command", command);
        List<TwConfigEditorFieldPolicy.EditorFieldSpec> companionFields = TwConfigEditorFieldPolicy.fieldsFor(
                descriptor(TwConfigFamily.COMPANION, true, true),
                null,
                companion
        );
        assertNull(TwConfigEditorFieldPolicy.findField(companionFields, "Command.DeadRespawnEnabled"));
        assertNotNull(TwConfigEditorFieldPolicy.findField(companionFields, "Command.DeadRespawnCooldownMs"));
    }

    @Test
    void roleOverrideFamiliesArrayExpandsIndexedNestedFields() {
        JsonObject effective = new JsonObject();
        JsonObject roleOverrides = new JsonObject();
        JsonObject bisonOverride = new JsonObject();
        JsonObject offspringLifecycle = new JsonObject();
        JsonArray families = new JsonArray();
        JsonObject firstFamily = new JsonObject();
        firstFamily.addProperty("AdultRoleId", "Tamed_Bison");
        firstFamily.addProperty("BabyRoleId", "Tamed_Bison_Calf");
        firstFamily.addProperty("TimeToFullGrownMinutes", 150);
        families.add(firstFamily);
        offspringLifecycle.add("Families", families);
        bisonOverride.add("OffspringLifecycle", offspringLifecycle);
        roleOverrides.add("Tamed_Bison", bisonOverride);
        effective.add("RoleOverrides", roleOverrides);

        List<TwConfigEditorFieldPolicy.EditorFieldSpec> fields = TwConfigEditorFieldPolicy.fieldsFor(
                descriptor(TwConfigFamily.BREEDING, true, true),
                null,
                effective
        );

        TwConfigEditorFieldPolicy.EditorFieldSpec adultRoleId =
                TwConfigEditorFieldPolicy.findField(
                        fields,
                        "RoleOverrides.Tamed_Bison.OffspringLifecycle.Families[0].AdultRoleId"
                );
        TwConfigEditorFieldPolicy.EditorFieldSpec babyRoleId =
                TwConfigEditorFieldPolicy.findField(
                        fields,
                        "RoleOverrides.Tamed_Bison.OffspringLifecycle.Families[0].BabyRoleId"
                );
        TwConfigEditorFieldPolicy.EditorFieldSpec minutes =
                TwConfigEditorFieldPolicy.findField(
                        fields,
                        "RoleOverrides.Tamed_Bison.OffspringLifecycle.Families[0].TimeToFullGrownMinutes"
                );

        assertNotNull(adultRoleId);
        assertNotNull(babyRoleId);
        assertNotNull(minutes);
        assertEquals(TwConfigEditorFieldPolicy.EditorFieldType.STRING, adultRoleId.type());
        assertEquals(TwConfigEditorFieldPolicy.EditorFieldType.STRING, babyRoleId.type());
        assertEquals(TwConfigEditorFieldPolicy.EditorFieldType.INTEGER, minutes.type());
    }

    private static TwConfigAssetDescriptor descriptor(TwConfigFamily family,
                                                      boolean knownType,
                                                      boolean editable) {
        return descriptor(family, knownType, editable, family.getId(), "pack-A", family.name() + "_Default");
    }

    private static TwConfigAssetDescriptor descriptor(TwConfigFamily family,
                                                      boolean knownType,
                                                      boolean editable,
                                                      String familyKey,
                                                      String sourcePackKey,
                                                      String assetId) {
        return new TwConfigAssetDescriptor(
                family,
                familyKey,
                family.getDisplayName(),
                family.getStorePath(),
                assetId,
                sourcePackKey,
                null,
                Path.of("source", assetId + ".json"),
                Path.of("Server", "Tamework", assetId + ".json"),
                editable,
                knownType,
                false
        );
    }
}
