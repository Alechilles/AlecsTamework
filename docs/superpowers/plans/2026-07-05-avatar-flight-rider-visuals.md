# Avatar Flight Rider Visuals Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Hide all equipped visuals on the transformed player-dragon while showing a visual-only copy of the player's normal model, with equipment, riding on the transformed player.

**Architecture:** Keep the real player as the controllable dragon entity. Add a non-serialized fake rider entity that copies the player's saved pre-transform model and attaches to the real player through vanilla `MountedComponent`. Keep inventory untouched by sending packet-level equipment overrides: blank equipment for the real transformed player, mirrored equipment for the fake rider.

**Tech Stack:** Java, Hytale ECS/components, Tamework `TwAvatarFlightConfig`, vanilla `EquipmentUpdate`, vanilla `MountedComponent`, JUnit architecture tests.

---

## Source-Backed Constraints

- Hytale 0.5.6 `InventoryUtils#createEquipmentUpdate` builds armor, right-hand, and left-hand equipment packet fields from inventory components. Use it as the baseline, then blank fields for the transformed owner.
- Hytale 0.5.6 `Model#toPacket` sends copied model attachment/texture/gradient/animation state. Use a copied saved `Model`, not a freshly recreated skin-only model, for the rider visual.
- Hytale 0.5.6 `EntitySpawnPage#createOrUpdatePreview` proves non-serialized visual entities with `NetworkId`, `TransformComponent`, and `ModelComponent` are a base-game pattern.
- Hytale 0.5.6 `MountedComponent` + `MountSystems.TrackerUpdate` send mount attachment packets for entities mounted to entities. Use that for the fake rider attachment instead of manual transform syncing.

## File Structure

- Modify `src/main/java/com/alechilles/alecstamework/config/assets/TwAvatarFlightConfig.java`
  - Add a `RiderVisualSettings` object section with inheritance-aware codec fields.
- Modify `src/main/resources/Server/Tamework/AvatarFlight/Tamework_Avatar_Flight_Default.json`
  - Add default `RiderVisual` settings.
- Modify `src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightModelService.java`
  - Expose a defensive copy of the saved pre-transform model.
- Create `src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightRiderVisualComponent.java`
  - Store owner UUID, fake rider entity ref UUID if available, and equipment signature for sync throttling.
- Create `src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightRiderVisualService.java`
  - Spawn/remove fake rider entities and attach them to the transformed player.
- Create `src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightEquipmentPacketService.java`
  - Build blank owner equipment and mirrored fake-rider equipment updates.
- Modify `src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightEquipmentVisualSystem.java`
  - Use the equipment packet service, blank armor as well as hands, and mirror owner equipment to the fake rider.
- Modify `src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightActivator.java`
  - Start/stop rider visuals during avatar-flight enable/disable and disconnect cleanup.
- Modify `src/main/java/com/alechilles/alecstamework/Tamework.java`
  - Register the new rider visual component and pass it into the visual system.
- Add tests under `src/test/java/com/alechilles/alecstamework/avatarflight/`
  - Config inheritance coverage.
  - Equipment packet architecture coverage.
  - Rider visual lifecycle architecture coverage.
- Update `CHANGELOG.md`
  - Add a user-facing unreleased entry for transformed mount-form visuals.

---

### Task 1: Config Surface

**Files:**
- Modify: `src/main/java/com/alechilles/alecstamework/config/assets/TwAvatarFlightConfig.java`
- Modify: `src/main/resources/Server/Tamework/AvatarFlight/Tamework_Avatar_Flight_Default.json`
- Test: `src/test/java/com/alechilles/alecstamework/avatarflight/AvatarFlightRiderVisualConfigArchitectureTest.java`

- [ ] **Step 1: Add a failing architecture test for config shape**

Create `src/test/java/com/alechilles/alecstamework/avatarflight/AvatarFlightRiderVisualConfigArchitectureTest.java`:

```java
package com.alechilles.alecstamework.avatarflight;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AvatarFlightRiderVisualConfigArchitectureTest {
    private static final Path CONFIG = Path.of(
            "src", "main", "java", "com", "alechilles", "alecstamework",
            "config", "assets", "TwAvatarFlightConfig.java"
    );
    private static final Path DEFAULT_JSON = Path.of(
            "src", "main", "resources", "Server", "Tamework", "AvatarFlight",
            "Tamework_Avatar_Flight_Default.json"
    );

    @Test
    void riderVisualConfigIsCodecBackedAndInheritanceAware() throws Exception {
        String source = Files.readString(CONFIG, StandardCharsets.UTF_8);

        assertTrue(source.contains("BuilderCodec<RiderVisualSettings> RIDER_VISUAL_CODEC"));
        assertTrue(source.contains("new KeyedCodec<>(\"RiderVisual\", RIDER_VISUAL_CODEC)"));
        assertTrue(source.contains("inheritOrCopyRiderVisual("));
        assertTrue(source.contains("if (!keys.contains(\"ShowRider\"))"));
        assertTrue(source.contains("public RiderVisualSettings getRiderVisual()"));
    }

    @Test
    void defaultAvatarFlightConfigDeclaresRiderVisualDefaults() throws Exception {
        String json = Files.readString(DEFAULT_JSON, StandardCharsets.UTF_8);

        assertTrue(json.contains("\"RiderVisual\""));
        assertTrue(json.contains("\"HideOwnerEquipment\": true"));
        assertTrue(json.contains("\"ShowRider\": true"));
        assertTrue(json.contains("\"SeatOffsetX\": 0.0"));
        assertTrue(json.contains("\"SeatOffsetY\": 1.35"));
        assertTrue(json.contains("\"SeatOffsetZ\": -0.25"));
        assertTrue(json.contains("\"EquipmentResendIntervalMs\": 250.0"));
    }
}
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```powershell
.\mvnw.cmd -Dtest=AvatarFlightRiderVisualConfigArchitectureTest test
```

Expected: FAIL because `RiderVisualSettings`, `RIDER_VISUAL_CODEC`, and default JSON fields do not exist yet.

- [ ] **Step 3: Add `RiderVisualSettings` to `TwAvatarFlightConfig`**

Add a nested config section with these defaults:

```java
private static final BuilderCodec<RiderVisualSettings> RIDER_VISUAL_CODEC = BuilderCodec.builder(
        RiderVisualSettings.class,
        RiderVisualSettings::new
)
        .<Boolean>append(new KeyedCodec<>("HideOwnerEquipment", Codec.BOOLEAN),
                (settings, value) -> settings.hideOwnerEquipment = value == null || value,
                settings -> settings.hideOwnerEquipment)
        .documentation("Whether avatar flight sends equipment packets that hide the transformed player's equipped visuals. Inheritance: missing nested key inherits parent value.")
        .add()
        .<Boolean>append(new KeyedCodec<>("HideOwnerArmor", Codec.BOOLEAN),
                (settings, value) -> settings.hideOwnerArmor = value == null || value,
                settings -> settings.hideOwnerArmor)
        .documentation("Whether hidden owner equipment also blanks armor slots. Inheritance: missing nested key inherits parent value.")
        .add()
        .<Boolean>append(new KeyedCodec<>("HideOwnerHands", Codec.BOOLEAN),
                (settings, value) -> settings.hideOwnerHands = value == null || value,
                settings -> settings.hideOwnerHands)
        .documentation("Whether hidden owner equipment blanks right-hand and left-hand item visuals. Inheritance: missing nested key inherits parent value.")
        .add()
        .<Boolean>append(new KeyedCodec<>("ShowRider", Codec.BOOLEAN),
                (settings, value) -> settings.showRider = value == null || value,
                settings -> settings.showRider)
        .documentation("Whether avatar flight spawns a visual-only copy of the player's saved model as a rider. Inheritance: missing nested key inherits parent value.")
        .add()
        .<Double>append(new KeyedCodec<>("SeatOffsetX", Codec.DOUBLE),
                (settings, value) -> settings.seatOffsetX = finiteOrDefault(value, 0.0),
                settings -> settings.seatOffsetX)
        .documentation("Fake rider attachment offset X relative to the transformed player entity. Inheritance: missing nested key inherits parent value.")
        .add()
        .<Double>append(new KeyedCodec<>("SeatOffsetY", Codec.DOUBLE),
                (settings, value) -> settings.seatOffsetY = finiteOrDefault(value, 1.35),
                settings -> settings.seatOffsetY)
        .documentation("Fake rider attachment offset Y relative to the transformed player entity. Inheritance: missing nested key inherits parent value.")
        .add()
        .<Double>append(new KeyedCodec<>("SeatOffsetZ", Codec.DOUBLE),
                (settings, value) -> settings.seatOffsetZ = finiteOrDefault(value, -0.25),
                settings -> settings.seatOffsetZ)
        .documentation("Fake rider attachment offset Z relative to the transformed player entity. Inheritance: missing nested key inherits parent value.")
        .add()
        .<Double>append(new KeyedCodec<>("EquipmentResendIntervalMs", Codec.DOUBLE),
                (settings, value) -> settings.equipmentResendIntervalMs = positiveOrDefault(value, 250.0),
                settings -> settings.equipmentResendIntervalMs)
        .documentation("Minimum milliseconds between repeated fake-rider equipment packets when the signature is unchanged. Inheritance: missing nested key inherits parent value.")
        .add()
        .build();
```

Add `private RiderVisualSettings riderVisual = new RiderVisualSettings();`, append the top-level codec key:

```java
.<RiderVisualSettings>append(new KeyedCodec<>("RiderVisual", RIDER_VISUAL_CODEC),
        (config, value) -> config.riderVisual = value == null ? new RiderVisualSettings() : value,
        config -> config.riderVisual)
.documentation("Avatar-flight rider visual and transformed-owner equipment visibility settings. Omitted section inherits; explicit nested keys override and missing nested keys inherit.")
.add()
```

Add inheritance:

```java
private void inheritOrCopyRiderVisual(TwAvatarFlightConfig parent,
                                      @Nullable Set<String> keys,
                                      Set<String> top) {
    if (!top.contains("RiderVisual")) riderVisual = parent.riderVisual;
    else if (keys != null && riderVisual != null && parent.riderVisual != null) {
        if (!keys.contains("HideOwnerEquipment")) riderVisual.hideOwnerEquipment = parent.riderVisual.hideOwnerEquipment;
        if (!keys.contains("HideOwnerArmor")) riderVisual.hideOwnerArmor = parent.riderVisual.hideOwnerArmor;
        if (!keys.contains("HideOwnerHands")) riderVisual.hideOwnerHands = parent.riderVisual.hideOwnerHands;
        if (!keys.contains("ShowRider")) riderVisual.showRider = parent.riderVisual.showRider;
        if (!keys.contains("SeatOffsetX")) riderVisual.seatOffsetX = parent.riderVisual.seatOffsetX;
        if (!keys.contains("SeatOffsetY")) riderVisual.seatOffsetY = parent.riderVisual.seatOffsetY;
        if (!keys.contains("SeatOffsetZ")) riderVisual.seatOffsetZ = parent.riderVisual.seatOffsetZ;
        if (!keys.contains("EquipmentResendIntervalMs")) {
            riderVisual.equipmentResendIntervalMs = parent.riderVisual.equipmentResendIntervalMs;
        }
    }
}
```

Call `inheritOrCopyRiderVisual(parent, nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "RiderVisual"), top);` from both inheritance paths used by the class.

Add helper and getter:

```java
private static double finiteOrDefault(@Nullable Double value, double fallback) {
    return value != null && Double.isFinite(value) ? value : fallback;
}

public RiderVisualSettings getRiderVisual() {
    return riderVisual == null ? new RiderVisualSettings() : riderVisual;
}

public static final class RiderVisualSettings {
    private boolean hideOwnerEquipment = true;
    private boolean hideOwnerArmor = true;
    private boolean hideOwnerHands = true;
    private boolean showRider = true;
    private double seatOffsetX = 0.0;
    private double seatOffsetY = 1.35;
    private double seatOffsetZ = -0.25;
    private double equipmentResendIntervalMs = 250.0;

    public boolean isHideOwnerEquipment() { return hideOwnerEquipment; }
    public boolean isHideOwnerArmor() { return hideOwnerArmor; }
    public boolean isHideOwnerHands() { return hideOwnerHands; }
    public boolean isShowRider() { return showRider; }
    public double getSeatOffsetX() { return seatOffsetX; }
    public double getSeatOffsetY() { return seatOffsetY; }
    public double getSeatOffsetZ() { return seatOffsetZ; }
    public long getEquipmentResendIntervalMs() { return Math.round(Math.max(1.0, equipmentResendIntervalMs)); }
}
```

- [ ] **Step 4: Add default JSON section**

Add this object to `Tamework_Avatar_Flight_Default.json` between `Animation` and `Debug`:

```json
  "RiderVisual": {
    "HideOwnerEquipment": true,
    "HideOwnerArmor": true,
    "HideOwnerHands": true,
    "ShowRider": true,
    "SeatOffsetX": 0.0,
    "SeatOffsetY": 1.35,
    "SeatOffsetZ": -0.25,
    "EquipmentResendIntervalMs": 250.0
  },
```

- [ ] **Step 5: Run focused test**

Run:

```powershell
.\mvnw.cmd -Dtest=AvatarFlightRiderVisualConfigArchitectureTest test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/alechilles/alecstamework/config/assets/TwAvatarFlightConfig.java src/main/resources/Server/Tamework/AvatarFlight/Tamework_Avatar_Flight_Default.json src/test/java/com/alechilles/alecstamework/avatarflight/AvatarFlightRiderVisualConfigArchitectureTest.java
git commit -m "Feat: add avatar flight rider visual config"
```

---

### Task 2: Owner Equipment Hiding

**Files:**
- Create: `src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightEquipmentPacketService.java`
- Modify: `src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightEquipmentVisualSystem.java`
- Test: `src/test/java/com/alechilles/alecstamework/avatarflight/AvatarFlightEquipmentVisualSystemArchitectureTest.java`

- [ ] **Step 1: Add failing architecture test**

Create `AvatarFlightEquipmentVisualSystemArchitectureTest.java`:

```java
package com.alechilles.alecstamework.avatarflight;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AvatarFlightEquipmentVisualSystemArchitectureTest {
    private static final Path SYSTEM = Path.of(
            "src", "main", "java", "com", "alechilles", "alecstamework",
            "avatarflight", "AvatarFlightEquipmentVisualSystem.java"
    );
    private static final Path SERVICE = Path.of(
            "src", "main", "java", "com", "alechilles", "alecstamework",
            "avatarflight", "AvatarFlightEquipmentPacketService.java"
    );

    @Test
    void equipmentServiceBlanksOwnerArmorAndHandsWithoutMutatingInventory() throws Exception {
        String source = Files.readString(SERVICE, StandardCharsets.UTF_8);

        assertTrue(source.contains("createHiddenOwnerEquipmentUpdate("));
        assertTrue(source.contains("update.rightHandItemId = BlockType.EMPTY_KEY"));
        assertTrue(source.contains("update.leftHandItemId = BlockType.EMPTY_KEY"));
        assertTrue(source.contains("Arrays.fill(update.armorIds, \"\")"));
        assertFalse(source.contains("removeItem"));
        assertFalse(source.contains("setItem"));
    }

    @Test
    void visualSystemUsesConfigurablePacketService() throws Exception {
        String source = Files.readString(SYSTEM, StandardCharsets.UTF_8);

        assertTrue(source.contains("TwAvatarFlightConfig.resolve(flight.getConfigId())"));
        assertTrue(source.contains("config.getRiderVisual()"));
        assertTrue(source.contains("AvatarFlightEquipmentPacketService.createHiddenOwnerEquipmentUpdate("));
        assertTrue(source.contains("AvatarFlightEquipmentPacketService.createCurrentEquipmentUpdate("));
    }
}
```

- [ ] **Step 2: Run focused test and verify failure**

```powershell
.\mvnw.cmd -Dtest=AvatarFlightEquipmentVisualSystemArchitectureTest test
```

Expected: FAIL because the service does not exist and armor is not blanked.

- [ ] **Step 3: Create `AvatarFlightEquipmentPacketService`**

Implement a final utility service with no mutable static state:

```java
package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.TwAvatarFlightConfig;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.EquipmentUpdate;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.InventoryUtils;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSettings;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Arrays;
import javax.annotation.Nonnull;

/**
 * Builds packet-only avatar-flight equipment visuals without mutating player inventory.
 */
public final class AvatarFlightEquipmentPacketService {
    private AvatarFlightEquipmentPacketService() {
    }

    @Nonnull
    public static EquipmentUpdate createCurrentEquipmentUpdate(@Nonnull Ref<EntityStore> ref,
                                                               @Nonnull ComponentAccessor<EntityStore> accessor) {
        PlayerSettings playerSettings = accessor.getComponent(ref, PlayerSettings.getComponentType());
        InventoryComponent.Armor armor = accessor.getComponent(ref, InventoryComponent.Armor.getComponentType());
        InventoryComponent.Utility utility = accessor.getComponent(ref, InventoryComponent.Utility.getComponentType());
        return InventoryUtils.createEquipmentUpdate(ref, accessor, playerSettings, armor, utility);
    }

    @Nonnull
    public static EquipmentUpdate createHiddenOwnerEquipmentUpdate(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull TwAvatarFlightConfig.RiderVisualSettings settings) {
        EquipmentUpdate update = createCurrentEquipmentUpdate(ref, accessor);
        if (!settings.isHideOwnerEquipment()) {
            return update;
        }
        if (settings.isHideOwnerHands()) {
            update.rightHandItemId = BlockType.EMPTY_KEY;
            update.leftHandItemId = BlockType.EMPTY_KEY;
        }
        if (settings.isHideOwnerArmor() && update.armorIds != null) {
            Arrays.fill(update.armorIds, "");
        }
        return update;
    }
}
```

- [ ] **Step 4: Update `AvatarFlightEquipmentVisualSystem` to use the service and config**

Change the query tick to read the flight component:

```java
AvatarFlightComponent flight = archetypeChunk.getComponent(index, flightType);
if (ref == null || visible == null || flight == null) {
    return;
}
TwAvatarFlightConfig config = TwAvatarFlightConfig.resolve(flight.getConfigId());
queueHiddenOwnerUpdate(ref, commandBuffer, visible, config.getRiderVisual());
```

Replace `queueHiddenHandUpdate` with:

```java
private static void queueHiddenOwnerUpdate(@Nonnull Ref<EntityStore> ref,
                                           @Nonnull ComponentAccessor<EntityStore> accessor,
                                           @Nonnull EntityTrackerSystems.Visible visible,
                                           @Nonnull TwAvatarFlightConfig.RiderVisualSettings settings) {
    EquipmentUpdate update = AvatarFlightEquipmentPacketService.createHiddenOwnerEquipmentUpdate(
            ref,
            accessor,
            settings
    );
    queue(ref, update, visible.visibleTo);
    queue(ref, update, visible.newlyVisibleTo);
}
```

Change restore to call:

```java
EquipmentUpdate update = AvatarFlightEquipmentPacketService.createCurrentEquipmentUpdate(ref, accessor);
```

Remove the old private `createCurrentEquipmentUpdate` method and unused imports.

- [ ] **Step 5: Run focused test**

```powershell
.\mvnw.cmd -Dtest=AvatarFlightEquipmentVisualSystemArchitectureTest test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightEquipmentPacketService.java src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightEquipmentVisualSystem.java src/test/java/com/alechilles/alecstamework/avatarflight/AvatarFlightEquipmentVisualSystemArchitectureTest.java
git commit -m "Feat: hide avatar flight owner equipment visuals"
```

---

### Task 3: Saved Model Access and Rider Visual Component

**Files:**
- Modify: `src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightModelService.java`
- Create: `src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightRiderVisualComponent.java`
- Modify: `src/main/java/com/alechilles/alecstamework/Tamework.java`
- Test: `src/test/java/com/alechilles/alecstamework/avatarflight/AvatarFlightRiderVisualComponentArchitectureTest.java`

- [ ] **Step 1: Add failing architecture test**

Create `AvatarFlightRiderVisualComponentArchitectureTest.java`:

```java
package com.alechilles.alecstamework.avatarflight;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AvatarFlightRiderVisualComponentArchitectureTest {
    @Test
    void modelServiceExposesDefensiveSavedModelCopy() throws Exception {
        String source = Files.readString(Path.of(
                "src", "main", "java", "com", "alechilles", "alecstamework",
                "avatarflight", "AvatarFlightModelService.java"
        ), StandardCharsets.UTF_8);

        assertTrue(source.contains("public Model savedModelCopy("));
        assertTrue(source.contains("return saved == null ? null : new Model(saved)"));
    }

    @Test
    void riderVisualComponentIsRegistered() throws Exception {
        String component = Files.readString(Path.of(
                "src", "main", "java", "com", "alechilles", "alecstamework",
                "avatarflight", "AvatarFlightRiderVisualComponent.java"
        ), StandardCharsets.UTF_8);
        String plugin = Files.readString(Path.of(
                "src", "main", "java", "com", "alechilles", "alecstamework", "Tamework.java"
        ), StandardCharsets.UTF_8);

        assertTrue(component.contains("BuilderCodec<AvatarFlightRiderVisualComponent> CODEC"));
        assertTrue(component.contains("private String riderEntityUuid"));
        assertTrue(component.contains("private String equipmentSignature"));
        assertTrue(plugin.contains("ComponentType<EntityStore, AvatarFlightRiderVisualComponent> avatarFlightRiderVisualComponentType"));
        assertTrue(plugin.contains("\"TameworkAvatarFlightRiderVisual\""));
    }
}
```

- [ ] **Step 2: Run test and verify failure**

```powershell
.\mvnw.cmd -Dtest=AvatarFlightRiderVisualComponentArchitectureTest test
```

Expected: FAIL because component and saved model accessor do not exist.

- [ ] **Step 3: Add saved model copy method**

In `AvatarFlightModelService`:

```java
@Nullable
public Model savedModelCopy(@Nonnull UUID playerUuid) {
    Model saved = SAVED_MODELS.get(playerUuid);
    return saved == null ? null : new Model(saved);
}
```

- [ ] **Step 4: Create `AvatarFlightRiderVisualComponent`**

```java
package com.alechilles.alecstamework.avatarflight;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nullable;

/**
 * Tracks the visual-only rider entity attached to a transformed avatar-flight player.
 */
public final class AvatarFlightRiderVisualComponent implements Component<EntityStore> {
    public static final BuilderCodec<AvatarFlightRiderVisualComponent> CODEC = BuilderCodec.builder(
            AvatarFlightRiderVisualComponent.class,
            AvatarFlightRiderVisualComponent::new
    )
            .<String>append(new KeyedCodec<>("OwnerUuid", Codec.STRING),
                    AvatarFlightRiderVisualComponent::setOwnerUuid,
                    AvatarFlightRiderVisualComponent::getOwnerUuid)
            .add()
            .<String>append(new KeyedCodec<>("RiderEntityUuid", Codec.STRING),
                    AvatarFlightRiderVisualComponent::setRiderEntityUuid,
                    AvatarFlightRiderVisualComponent::getRiderEntityUuid)
            .add()
            .<String>append(new KeyedCodec<>("EquipmentSignature", Codec.STRING),
                    AvatarFlightRiderVisualComponent::setEquipmentSignature,
                    AvatarFlightRiderVisualComponent::getEquipmentSignature)
            .add()
            .<Long>append(new KeyedCodec<>("LastEquipmentSentAtMs", Codec.LONG),
                    AvatarFlightRiderVisualComponent::setLastEquipmentSentAtMs,
                    AvatarFlightRiderVisualComponent::getLastEquipmentSentAtMs)
            .add()
            .build();

    @Nullable
    private String ownerUuid;
    @Nullable
    private String riderEntityUuid;
    private String equipmentSignature = "";
    private long lastEquipmentSentAtMs;

    @Nullable
    public static ComponentType<EntityStore, AvatarFlightRiderVisualComponent> getComponentType() {
        Tamework instance = Tamework.getInstance();
        return instance == null ? null : instance.getAvatarFlightRiderVisualComponentType();
    }

    @Nullable
    public String getOwnerUuid() { return ownerUuid; }
    public void setOwnerUuid(@Nullable String ownerUuid) { this.ownerUuid = ownerUuid; }
    @Nullable
    public String getRiderEntityUuid() { return riderEntityUuid; }
    public void setRiderEntityUuid(@Nullable String riderEntityUuid) { this.riderEntityUuid = riderEntityUuid; }
    public String getEquipmentSignature() { return equipmentSignature == null ? "" : equipmentSignature; }
    public void setEquipmentSignature(@Nullable String equipmentSignature) {
        this.equipmentSignature = equipmentSignature == null ? "" : equipmentSignature;
    }
    public long getLastEquipmentSentAtMs() { return lastEquipmentSentAtMs; }
    public void setLastEquipmentSentAtMs(long lastEquipmentSentAtMs) { this.lastEquipmentSentAtMs = lastEquipmentSentAtMs; }

    @Override
    public AvatarFlightRiderVisualComponent clone() {
        AvatarFlightRiderVisualComponent clone = new AvatarFlightRiderVisualComponent();
        clone.ownerUuid = ownerUuid;
        clone.riderEntityUuid = riderEntityUuid;
        clone.equipmentSignature = equipmentSignature;
        clone.lastEquipmentSentAtMs = lastEquipmentSentAtMs;
        return clone;
    }
}
```

Add `import com.alechilles.alecstamework.Tamework;`.

- [ ] **Step 5: Register component in `Tamework`**

Add field:

```java
private ComponentType<EntityStore, AvatarFlightRiderVisualComponent> avatarFlightRiderVisualComponentType;
```

Register after avatar-flight input:

```java
avatarFlightRiderVisualComponentType = getEntityStoreRegistry().registerComponent(
        AvatarFlightRiderVisualComponent.class,
        "TameworkAvatarFlightRiderVisual",
        AvatarFlightRiderVisualComponent.CODEC
);
```

Add getter:

```java
public ComponentType<EntityStore, AvatarFlightRiderVisualComponent> getAvatarFlightRiderVisualComponentType() {
    return avatarFlightRiderVisualComponentType;
}
```

- [ ] **Step 6: Run focused test**

```powershell
.\mvnw.cmd -Dtest=AvatarFlightRiderVisualComponentArchitectureTest test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightModelService.java src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightRiderVisualComponent.java src/main/java/com/alechilles/alecstamework/Tamework.java src/test/java/com/alechilles/alecstamework/avatarflight/AvatarFlightRiderVisualComponentArchitectureTest.java
git commit -m "Feat: track avatar flight rider visuals"
```

---

### Task 4: Fake Rider Lifecycle

**Files:**
- Create: `src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightRiderVisualService.java`
- Modify: `src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightActivator.java`
- Test: `src/test/java/com/alechilles/alecstamework/avatarflight/AvatarFlightRiderVisualServiceArchitectureTest.java`

- [ ] **Step 1: Add failing architecture test**

Create `AvatarFlightRiderVisualServiceArchitectureTest.java`:

```java
package com.alechilles.alecstamework.avatarflight;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AvatarFlightRiderVisualServiceArchitectureTest {
    private static final Path SERVICE = Path.of(
            "src", "main", "java", "com", "alechilles", "alecstamework",
            "avatarflight", "AvatarFlightRiderVisualService.java"
    );
    private static final Path ACTIVATOR = Path.of(
            "src", "main", "java", "com", "alechilles", "alecstamework",
            "avatarflight", "AvatarFlightActivator.java"
    );

    @Test
    void riderVisualServiceCreatesNonSerializedMountedModelEntity() throws Exception {
        String source = Files.readString(SERVICE, StandardCharsets.UTF_8);

        assertTrue(source.contains("new NonSerialized()"));
        assertTrue(source.contains("new NetworkId("));
        assertTrue(source.contains("new ModelComponent(new Model(savedModel))"));
        assertTrue(source.contains("new MountedComponent(ownerRef"));
        assertTrue(source.contains("MountController.BlockMount"));
        assertTrue(source.contains("AddReason.SPAWN"));
        assertTrue(source.contains("RemoveReason.REMOVE"));
        assertFalse(source.contains("PlayerRef.getComponent(Player"));
    }

    @Test
    void activatorStartsAndStopsRiderVisualsAroundModelSwap() throws Exception {
        String source = Files.readString(ACTIVATOR, StandardCharsets.UTF_8);

        assertTrue(source.contains("riderVisualService.spawn("));
        assertTrue(source.contains("riderVisualService.remove("));
        assertTrue(source.indexOf("modelService.apply(") < source.indexOf("riderVisualService.spawn("));
        assertTrue(source.indexOf("riderVisualService.remove(") < source.indexOf("modelService.restore("));
    }
}
```

- [ ] **Step 2: Run focused test and verify failure**

```powershell
.\mvnw.cmd -Dtest=AvatarFlightRiderVisualServiceArchitectureTest test
```

Expected: FAIL because service and activator calls do not exist.

- [ ] **Step 3: Implement `AvatarFlightRiderVisualService`**

Create the service with these responsibilities:

```java
/**
 * Creates and removes the visual-only saved-player model that rides the transformed player.
 */
public final class AvatarFlightRiderVisualService {
    public boolean spawn(Store<EntityStore> store,
                         Ref<EntityStore> ownerRef,
                         UUID ownerUuid,
                         TwAvatarFlightConfig config,
                         Model savedModel) {
        TwAvatarFlightConfig.RiderVisualSettings settings = config.getRiderVisual();
        if (!settings.isShowRider() || savedModel == null) {
            return false;
        }
        ComponentType<EntityStore, AvatarFlightRiderVisualComponent> visualType =
                AvatarFlightRiderVisualComponent.getComponentType();
        if (visualType == null) {
            return false;
        }

        TransformComponent ownerTransform = store.getComponent(ownerRef, TransformComponent.getComponentType());
        if (ownerTransform == null || ownerTransform.getTransform() == null) {
            return false;
        }

        Holder<EntityStore> holder = EntityStore.REGISTRY.newHolder();
        holder.putComponent(NetworkId.getComponentType(),
                new NetworkId(store.getExternalData().takeNextNetworkId()));
        holder.putComponent(NonSerialized.getComponentType(), new NonSerialized());
        holder.putComponent(TransformComponent.getComponentType(), ownerTransform.clone());
        holder.putComponent(HeadRotation.getComponentType(), new HeadRotation(ownerTransform.getRotation()));
        holder.putComponent(ModelComponent.getComponentType(), new ModelComponent(new Model(savedModel)));
        holder.putComponent(MountedComponent.getComponentType(), new MountedComponent(
                ownerRef,
                new Rotation3f(
                        (float) settings.getSeatOffsetX(),
                        (float) settings.getSeatOffsetY(),
                        (float) settings.getSeatOffsetZ()
                ),
                MountController.BlockMount
        ));

        Ref<EntityStore> riderRef = store.addEntity(holder, AddReason.SPAWN);
        AvatarFlightRiderVisualComponent visual = new AvatarFlightRiderVisualComponent();
        visual.setOwnerUuid(ownerUuid.toString());
        UUIDComponent riderUuid = store.getComponent(riderRef, UUIDComponent.getComponentType());
        visual.setRiderEntityUuid(riderUuid == null ? "" : riderUuid.getUuid().toString());
        store.putComponent(ownerRef, visualType, visual);
        return true;
    }

    public void remove(Store<EntityStore> store, Ref<EntityStore> ownerRef) {
        ComponentType<EntityStore, AvatarFlightRiderVisualComponent> visualType =
                AvatarFlightRiderVisualComponent.getComponentType();
        if (visualType == null) {
            return;
        }
        AvatarFlightRiderVisualComponent visual = store.getComponent(ownerRef, visualType);
        if (visual == null) {
            return;
        }
        Ref<EntityStore> riderRef = resolveRiderRef(store, visual);
        if (riderRef != null) {
            store.removeEntity(riderRef, RemoveReason.REMOVE);
        }
        store.tryRemoveComponent(ownerRef, visualType);
    }
}
```

Use `store.addEntity(holder, AddReason.SPAWN)` here because `AvatarFlightActivator` is command/event driven, not an ECS tick system.

- [ ] **Step 4: Wire lifecycle in `AvatarFlightActivator`**

Add field:

```java
private final AvatarFlightRiderVisualService riderVisualService = new AvatarFlightRiderVisualService();
```

In `enable`, after successful `modelService.apply(...)` and before adding `AvatarFlightComponent`, call:

```java
if (applyModel && config.getRiderVisual().isShowRider()) {
    riderVisualService.spawn(store, ref, playerUuid, config, modelService.savedModelCopy(playerUuid));
}
```

In `disable`, before restoring equipment/model:

```java
riderVisualService.remove(store, ref);
```

In `onPlayerDisconnect`, resolve the player ref already supplied by the event and call `remove` before clearing saved model if a live store is available from the ref/event path. If no store is available in the event API, leave removal to entity removal cleanup in Task 6 and clear the registry here.

- [ ] **Step 5: Run focused test**

```powershell
.\mvnw.cmd -Dtest=AvatarFlightRiderVisualServiceArchitectureTest test
```

Expected: PASS after the service imports and helper methods compile.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightRiderVisualService.java src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightActivator.java src/test/java/com/alechilles/alecstamework/avatarflight/AvatarFlightRiderVisualServiceArchitectureTest.java
git commit -m "Feat: spawn avatar flight rider visuals"
```

---

### Task 5: Fake Rider Equipment Mirroring

**Files:**
- Modify: `src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightEquipmentPacketService.java`
- Modify: `src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightEquipmentVisualSystem.java`
- Modify: `src/main/java/com/alechilles/alecstamework/Tamework.java`
- Test: `src/test/java/com/alechilles/alecstamework/avatarflight/AvatarFlightRiderEquipmentArchitectureTest.java`

- [ ] **Step 1: Add failing architecture test**

Create `AvatarFlightRiderEquipmentArchitectureTest.java`:

```java
package com.alechilles.alecstamework.avatarflight;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AvatarFlightRiderEquipmentArchitectureTest {
    @Test
    void visualSystemMirrorsOwnerEquipmentToFakeRider() throws Exception {
        String system = Files.readString(Path.of(
                "src", "main", "java", "com", "alechilles", "alecstamework",
                "avatarflight", "AvatarFlightEquipmentVisualSystem.java"
        ), StandardCharsets.UTF_8);
        String service = Files.readString(Path.of(
                "src", "main", "java", "com", "alechilles", "alecstamework",
                "avatarflight", "AvatarFlightEquipmentPacketService.java"
        ), StandardCharsets.UTF_8);

        assertTrue(system.contains("AvatarFlightRiderVisualComponent"));
        assertTrue(system.contains("queueRiderEquipmentUpdate("));
        assertTrue(system.contains("settings.getEquipmentResendIntervalMs()"));
        assertTrue(service.contains("equipmentSignature("));
        assertTrue(service.contains("createCurrentEquipmentUpdate(ownerRef"));
    }
}
```

- [ ] **Step 2: Run focused test and verify failure**

```powershell
.\mvnw.cmd -Dtest=AvatarFlightRiderEquipmentArchitectureTest test
```

Expected: FAIL because rider equipment mirroring is not wired.

- [ ] **Step 3: Add equipment signature helper**

In `AvatarFlightEquipmentPacketService`:

```java
@Nonnull
public static String equipmentSignature(@Nonnull EquipmentUpdate update) {
    String armor = update.armorIds == null ? "" : String.join(",", update.armorIds);
    return safe(update.rightHandItemId) + "|" + safe(update.leftHandItemId) + "|" + armor;
}

@Nonnull
private static String safe(@Nullable String value) {
    return value == null ? "" : value;
}
```

- [ ] **Step 4: Update visual system constructor and query**

Pass `AvatarFlightRiderVisualComponent` type into `AvatarFlightEquipmentVisualSystem` from `Tamework`:

```java
new AvatarFlightEquipmentVisualSystem(
        avatarFlightComponentType,
        avatarFlightRiderVisualComponentType,
        EntityTrackerSystems.Visible.getComponentType()
)
```

Update system fields/query to include the visual component as optional lookup, not a required query component:

```java
private final ComponentType<EntityStore, AvatarFlightRiderVisualComponent> riderVisualType;
```

- [ ] **Step 5: Queue mirrored equipment to fake rider**

In `tick`, after owner hidden update:

```java
AvatarFlightRiderVisualComponent riderVisual = commandBuffer.getComponent(ref, riderVisualType);
if (riderVisual != null && config.getRiderVisual().isShowRider()) {
    queueRiderEquipmentUpdate(ref, commandBuffer, riderVisual, config.getRiderVisual());
}
```

Implement:

```java
private static void queueRiderEquipmentUpdate(@Nonnull Ref<EntityStore> ownerRef,
                                              @Nonnull ComponentAccessor<EntityStore> accessor,
                                              @Nonnull AvatarFlightRiderVisualComponent riderVisual,
                                              @Nonnull TwAvatarFlightConfig.RiderVisualSettings settings) {
    Ref<EntityStore> riderRef = AvatarFlightRiderVisualService.resolveRiderRef(accessor, riderVisual);
    if (riderRef == null) {
        return;
    }
    EntityTrackerSystems.Visible riderVisible = accessor.getComponent(
            riderRef,
            EntityTrackerSystems.Visible.getComponentType()
    );
    if (riderVisible == null) {
        return;
    }
    EquipmentUpdate update = AvatarFlightEquipmentPacketService.createCurrentEquipmentUpdate(ownerRef, accessor);
    String signature = AvatarFlightEquipmentPacketService.equipmentSignature(update);
    long now = System.currentTimeMillis();
    boolean signatureChanged = !signature.equals(riderVisual.getEquipmentSignature());
    boolean intervalElapsed = now - riderVisual.getLastEquipmentSentAtMs() >= settings.getEquipmentResendIntervalMs();
    if (!signatureChanged && !intervalElapsed) {
        return;
    }
    riderVisual.setEquipmentSignature(signature);
    riderVisual.setLastEquipmentSentAtMs(now);
    queue(riderRef, update, riderVisible.visibleTo);
    queue(riderRef, update, riderVisible.newlyVisibleTo);
}
```

If mutating `riderVisual` from this tick system causes an ECS write-safety failure, change the implementation to clone and write it through `CommandBuffer`:

```java
AvatarFlightRiderVisualComponent updated = riderVisual.clone();
updated.setEquipmentSignature(signature);
updated.setLastEquipmentSentAtMs(now);
commandBuffer.putComponent(ownerRef, riderVisualType, updated);
```

- [ ] **Step 6: Run focused test**

```powershell
.\mvnw.cmd -Dtest=AvatarFlightRiderEquipmentArchitectureTest test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightEquipmentPacketService.java src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightEquipmentVisualSystem.java src/main/java/com/alechilles/alecstamework/Tamework.java src/test/java/com/alechilles/alecstamework/avatarflight/AvatarFlightRiderEquipmentArchitectureTest.java
git commit -m "Feat: mirror avatar flight rider equipment"
```

---

### Task 6: Cleanup Safety

**Files:**
- Create: `src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightRiderVisualCleanupSystem.java`
- Modify: `src/main/java/com/alechilles/alecstamework/Tamework.java`
- Test: `src/test/java/com/alechilles/alecstamework/avatarflight/AvatarFlightRiderVisualCleanupArchitectureTest.java`

- [ ] **Step 1: Add failing cleanup architecture test**

Create `AvatarFlightRiderVisualCleanupArchitectureTest.java`:

```java
package com.alechilles.alecstamework.avatarflight;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AvatarFlightRiderVisualCleanupArchitectureTest {
    @Test
    void cleanupSystemRemovesVisualRiderWhenOwnerComponentIsRemoved() throws Exception {
        String cleanup = Files.readString(Path.of(
                "src", "main", "java", "com", "alechilles", "alecstamework",
                "avatarflight", "AvatarFlightRiderVisualCleanupSystem.java"
        ), StandardCharsets.UTF_8);
        String plugin = Files.readString(Path.of(
                "src", "main", "java", "com", "alechilles", "alecstamework", "Tamework.java"
        ), StandardCharsets.UTF_8);

        assertTrue(cleanup.contains("extends EntitySystem"));
        assertTrue(cleanup.contains("onEntityRemoved"));
        assertTrue(cleanup.contains("commandBuffer.removeEntity"));
        assertTrue(cleanup.contains("RemoveReason.REMOVE"));
        assertTrue(plugin.contains("new AvatarFlightRiderVisualCleanupSystem("));
    }
}
```

- [ ] **Step 2: Run focused test and verify failure**

```powershell
.\mvnw.cmd -Dtest=AvatarFlightRiderVisualCleanupArchitectureTest test
```

Expected: FAIL because cleanup system does not exist.

- [ ] **Step 3: Implement cleanup system**

Follow local removal-listener patterns such as `MountedRideCleanupSystem` and `MountedGlideCleanupSystem`. The system should:

- Listen for owner entity removal.
- Check whether the removed owner had `AvatarFlightRiderVisualComponent`.
- Resolve the rider ref.
- Queue rider removal through `CommandBuffer.removeEntity(riderRef, RemoveReason.REMOVE)`.

Do not scan `Universe.getPlayers()` and do not call `PlayerRef.getComponent(Player)`.

- [ ] **Step 4: Register cleanup system**

In `Tamework`, register after `AvatarFlightEquipmentVisualSystem`:

```java
getEntityStoreRegistry().registerSystem(
        new AvatarFlightRiderVisualCleanupSystem(avatarFlightRiderVisualComponentType)
);
```

- [ ] **Step 5: Run focused test**

```powershell
.\mvnw.cmd -Dtest=AvatarFlightRiderVisualCleanupArchitectureTest test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightRiderVisualCleanupSystem.java src/main/java/com/alechilles/alecstamework/Tamework.java src/test/java/com/alechilles/alecstamework/avatarflight/AvatarFlightRiderVisualCleanupArchitectureTest.java
git commit -m "Fix: clean up avatar flight rider visuals"
```

---

### Task 7: Docs, Changelog, and Full Verification

**Files:**
- Modify: `CHANGELOG.md`
- Optionally modify: `wiki/Modder-Documentation/Config-Reference/Avatar-Flight.md` if that page exists

- [ ] **Step 1: Update changelog**

Add one player-facing entry under the current unreleased section:

```markdown
- Improved avatar-flight mount form visuals so transformed players can hide their equipped gear while showing a visual rider copy with the player's normal equipment.
```

- [ ] **Step 2: Update config docs if an avatar-flight config page exists**

Run:

```powershell
rg "AvatarFlight|Avatar Flight|Tamework_Avatar_Flight" -n docs wiki
```

If an avatar-flight config page exists, add a `RiderVisual` section documenting:

```markdown
### RiderVisual

Controls the mount-form visual split used by avatar flight.

- `HideOwnerEquipment`: hides equipped visuals on the transformed player entity.
- `HideOwnerArmor`: blanks armor visuals when owner equipment hiding is enabled.
- `HideOwnerHands`: blanks held-item and utility/offhand visuals when owner equipment hiding is enabled.
- `ShowRider`: spawns a visual-only copy of the saved player model riding the transformed player.
- `SeatOffsetX`, `SeatOffsetY`, `SeatOffsetZ`: rider attachment offset relative to the transformed player.
- `EquipmentResendIntervalMs`: minimum resend interval for unchanged fake-rider equipment packets.
```

If no page exists, do not create a broad new docs page in this task.

- [ ] **Step 3: Run focused avatar-flight tests**

```powershell
.\mvnw.cmd -Dtest=AvatarFlight* test
```

Expected: PASS.

- [ ] **Step 4: Run ECS/thread-safety guard checks**

```powershell
rg "PlayerRef\\.getComponent\\(Player|getComponent\\(Player\\.getComponentType\\(\\)\\)|Universe\\.get\\(\\).*getPlayers" -n src/main/java
.\mvnw.cmd -Dtest=EcsWriteSafetyGuardTest,AsyncThreadSafetyGuardTest test
```

Expected: no new unsafe runtime player access; guard tests PASS.

- [ ] **Step 5: Run full tests**

```powershell
.\mvnw.cmd test
```

Expected: PASS.

- [ ] **Step 6: Package for local runtime testing**

```powershell
.\mvnw.cmd package -DskipTests
```

Expected: build succeeds and produces the mod jar under `target`.

- [ ] **Step 7: Runtime test checklist**

Copy the built jar to the active `UserData\Mods` runtime location using the repo's existing packaging/copy workflow. Then test in-game:

- Equip armor and a visible held item.
- Enable dragon/avatar flight.
- Confirm the dragon body has no visible player armor or held-item attachments.
- Confirm a normal player visual appears seated on the dragon.
- Confirm the fake rider shows the armor and held item.
- Switch hotbar items while in mount form.
- Confirm the fake rider equipment updates and the dragon body stays clean.
- Disable avatar flight.
- Confirm the fake rider disappears.
- Confirm the real player model and equipment visuals return.
- Disconnect while in mount form, reconnect, and confirm no orphan fake rider remains.

- [ ] **Step 8: Commit final docs/verification changes**

```powershell
git add CHANGELOG.md
git add wiki/Modder-Documentation/Config-Reference/Avatar-Flight.md
git commit -m "Docs: document avatar flight rider visuals"
```

Skip the wiki `git add` path if no avatar-flight config page exists.

---

## Residual Risks

- `MountedComponent` may visually attach the fake rider but choose an unexpected default orientation. If the rider appears sideways/backwards, add config fields for seat pitch/yaw/roll only after observing the runtime behavior.
- If `EquipmentUpdate` packets do not render equipment on a non-player fake rider, the fallback is to add minimal inventory/equipment components to the fake rider entity. Try packet mirroring first because it avoids gameplay side effects.
- If fake rider cleanup cannot resolve the rider by UUID reliably, change `AvatarFlightRiderVisualComponent` to store the rider `Ref` in whatever serializable/ref-backed form existing Tamework mounted systems use.

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-07-05-avatar-flight-rider-visuals.md`. Two execution options:

1. **Subagent-Driven (recommended)** - Dispatch a fresh subagent per task, review between tasks, fast iteration.
2. **Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints.
