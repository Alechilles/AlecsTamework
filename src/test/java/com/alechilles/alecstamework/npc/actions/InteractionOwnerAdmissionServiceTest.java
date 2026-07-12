package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.OwnerSource;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.SetOwnerEffect;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.ownership.OwnerMutationScheduler;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.TestEntityComponentStore;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.lang.reflect.Field;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import sun.misc.Unsafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Covers strict custom-owner parsing so a display name can never imply an owner clear. */
@ResourceLock("SimpleClaims-damage-adapter-static-state")
class InteractionOwnerAdmissionServiceTest {
    private static final UUID VALID_OWNER =
            UUID.fromString("00000000-0000-0000-0000-00000000b101");

    @Test
    void customOwnerRequiresValidUuid() {
        assertNull(InteractionOwnerAdmissionService.parseCustomOwnerUuid(null));
        assertNull(InteractionOwnerAdmissionService.parseCustomOwnerUuid("  "));
        assertNull(InteractionOwnerAdmissionService.parseCustomOwnerUuid("not-a-uuid"));

        assertEquals(VALID_OWNER, InteractionOwnerAdmissionService.parseCustomOwnerUuid(
                " " + VALID_OWNER + " "
        ));
    }

    @Test
    void nameOnlyAndMalformedCustomOwnersNeverReachMutationScheduler() throws Exception {
        Field instanceField = Tamework.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        Object previousInstance = instanceField.get(null);
        try (TestEntityComponentStore store = new TestEntityComponentStore(new EntityStore(null))) {
            Tamework poison = (Tamework) unsafe().allocateInstance(PoisonSchedulerTamework.class);
            instanceField.set(null, poison);
            Ref<EntityStore> npcRef = store.createReference();
            InteractionOwnerAdmissionService service = new InteractionOwnerAdmissionService();

            assertFalse(service.scheduleSetOwner(
                    customOwner(null, "Name Only"), npcRef, store, null,
                    InteractionStateEffects.OwnerAppliedContinuation.NOOP
            ));
            assertFalse(service.scheduleSetOwner(
                    customOwner("not-a-uuid", "Malformed"), npcRef, store, null,
                    InteractionStateEffects.OwnerAppliedContinuation.NOOP
            ));
        } finally {
            instanceField.set(null, previousInstance);
        }
    }

    private static SetOwnerEffect customOwner(String uuid, String name) throws Exception {
        SetOwnerEffect effect = new SetOwnerEffect();
        setField(effect, "source", OwnerSource.Custom);
        setField(effect, "uuid", uuid);
        setField(effect, "name", name);
        return effect;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }

    /** Fails if invalid custom-owner input crosses the mutation-scheduling boundary. */
    private static final class PoisonSchedulerTamework extends Tamework {
        private static final ComponentType<EntityStore, TameworkOwnerComponent> OWNER_TYPE =
                new ComponentType<>();

        private PoisonSchedulerTamework(JavaPluginInit init) {
            super(init);
        }

        @Override
        public ComponentType<EntityStore, TameworkOwnerComponent> getOwnerComponentType() {
            return OWNER_TYPE;
        }

        @Override
        public OwnerMutationScheduler getOwnerMutationScheduler() {
            throw new AssertionError("Invalid custom owner reached the mutation scheduler.");
        }
    }
}
