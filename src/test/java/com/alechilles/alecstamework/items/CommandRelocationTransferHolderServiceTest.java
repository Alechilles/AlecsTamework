package com.alechilles.alecstamework.items;

import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards detached-holder positioning and rollback for cross-world recall. */
class CommandRelocationTransferHolderServiceTest {

    @Test
    void destinationPreparationReplacesWorldBoundTransformAndRollbackRestoresSource() {
        ComponentRegistry<EntityStore> registry = new ComponentRegistry<>();
        ComponentType<EntityStore, TransformComponent> transformType = registry.registerComponent(
                TransformComponent.class, TransformComponent::new
        );
        Holder<EntityStore> holder = registry.newHolder();
        TransformComponent original = new TransformComponent(
                new Vector3d(32.0, 70.0, -48.0), new Rotation3f(0.2f, 1.1f, -0.3f)
        );
        holder.addComponent(transformType, original);
        CommandRelocationTransferHolderService service =
                new CommandRelocationTransferHolderService(transformType);

        CommandRelocationTransferHolderService.SourceTransform snapshot =
                service.prepareForDestination(holder, new Vector3d(-128.0, 85.0, 224.0));

        assertNotNull(snapshot);
        TransformComponent destination = holder.getComponent(transformType);
        assertNotNull(destination);
        assertNotSame(original, destination);
        assertEquals(new Vector3d(-128.0, 85.0, 224.0), destination.getPosition());
        assertEquals(original.getRotation(), destination.getRotation());
        assertNull(destination.getSectionRef(),
                "A destination holder must not retain a source-world section ref.");
        assertTrue(service.restoreSource(holder, snapshot));
        TransformComponent restored = holder.getComponent(transformType);
        assertEquals(new Vector3d(32.0, 70.0, -48.0), restored.getPosition());
        assertEquals(original.getRotation(), restored.getRotation());
        assertNull(restored.getSectionRef(),
                "Rollback must let the source store bind its own section.");
    }
}
