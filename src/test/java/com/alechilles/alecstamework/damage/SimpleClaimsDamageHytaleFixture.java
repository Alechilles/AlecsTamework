package com.alechilles.alecstamework.damage;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.damage.SimpleClaimsDamageAdapterMatrix.LiveOwnerEvidence;
import com.alechilles.alecstamework.damage.SimpleClaimsDamageAdapterMatrix.Scenario;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.TestEntityComponentStore;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.WorldConfig;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import sun.misc.Unsafe;

import static com.alechilles.alecstamework.damage.SimpleClaimsDamageAdapterMatrix.ATTACKER;
import static com.alechilles.alecstamework.damage.SimpleClaimsDamageAdapterMatrix.TARGET_POSITION;
import static com.alechilles.alecstamework.damage.SimpleClaimsDamageAdapterMatrix.WORLD_NAME;

/** Lightweight Hytale ECS/world scaffolding for the cross-adapter damage matrix. */
public final class SimpleClaimsDamageHytaleFixture {
    private static final UUID LINKS_OWNER =
            UUID.fromString("00000000-0000-0000-0000-00000000a103");
    private static final UUID NAME_OWNER =
            UUID.fromString("00000000-0000-0000-0000-00000000a104");

    private SimpleClaimsDamageHytaleFixture() {
    }

    /** Installs only the core component identities read by the production adapters. */
    public static final class HytaleModuleScope implements AutoCloseable {
        private final Object oldEntityModule;
        private final Object oldTamework;
        private final ComponentType<EntityStore, NPCEntity> npcType = new ComponentType<>();
        private final ComponentType<EntityStore, Player> playerType = new ComponentType<>();
        private final ComponentType<EntityStore, TransformComponent> transformType = new ComponentType<>();
        private final ComponentType<EntityStore, TameworkOwnerComponent> ownerType = new ComponentType<>();
        private final ComponentType<EntityStore, TameworkCommandLinksComponent> linksType = new ComponentType<>();
        private final ComponentType<EntityStore, TameworkNpcNameComponent> nameType = new ComponentType<>();

        private HytaleModuleScope(Object oldEntityModule, Object oldTamework) {
            this.oldEntityModule = oldEntityModule;
            this.oldTamework = oldTamework;
        }

        @Nonnull
        public static HytaleModuleScope install() throws Exception {
            Field instanceField = staticField(EntityModule.class, "instance");
            Field tameworkInstanceField = staticField(Tamework.class, "instance");
            HytaleModuleScope scope = new HytaleModuleScope(
                    instanceField.get(null),
                    tameworkInstanceField.get(null)
            );
            EntityModule module = (EntityModule) unsafe().allocateInstance(EntityModule.class);
            Map<Class<?>, ComponentType<EntityStore, ?>> types = new HashMap<>();
            types.put(NPCEntity.class, scope.npcType);
            setObjectField(module, EntityModule.class, "classToComponentType", types);
            setObjectField(module, EntityModule.class, "playerComponentType", scope.playerType);
            setObjectField(module, EntityModule.class, "transformComponentType", scope.transformType);
            instanceField.set(null, module);

            Tamework tamework = (Tamework) unsafe().allocateInstance(Tamework.class);
            setObjectField(tamework, Tamework.class, "ownerComponentType", scope.ownerType);
            setObjectField(tamework, Tamework.class, "commandLinksComponentType", scope.linksType);
            setObjectField(tamework, Tamework.class, "npcNameComponentType", scope.nameType);
            tameworkInstanceField.set(null, tamework);
            return scope;
        }

        @Override
        public void close() throws Exception {
            staticField(EntityModule.class, "instance").set(null, oldEntityModule);
            staticField(Tamework.class, "instance").set(null, oldTamework);
        }
    }

    /** Live target/store fixture consumed unchanged by handle(...) and evaluateDamage(...). */
    public static final class WorldFixture implements AutoCloseable {
        private final Scenario scenario;
        private final UUID targetUuid = UUID.randomUUID();
        private final TestWorld world;
        private final TestEntityStore entityStore;
        private final TestEntityComponentStore store;
        private final Ref<EntityStore> targetRef;
        private final Ref<EntityStore> attackerRef;
        private final Ref<EntityStore> projectileRef;
        private final TransformComponent targetTransform;
        private final ComponentType<EntityStore, TameworkOwnerComponent> ownerType =
                TameworkOwnerComponent.getComponentType();
        private final ComponentType<EntityStore, TameworkCommandLinksComponent> linksType =
                TameworkCommandLinksComponent.getComponentType();
        private final ComponentType<EntityStore, TameworkNpcNameComponent> nameType =
                TameworkNpcNameComponent.getComponentType();

        private WorldFixture(@Nonnull Scenario scenario) throws Exception {
            this.scenario = scenario;
            world = (TestWorld) unsafe().allocateInstance(TestWorld.class);
            entityStore = new TestEntityStore(world);
            store = new TestEntityComponentStore(entityStore);
            entityStore.store = store;

            targetRef = store.createReference();
            attackerRef = store.createReference();
            projectileRef = store.createReference();
            world.initialize(WORLD_NAME, targetUuid, targetRef, entityStore);

            NPCEntity npc = new NPCEntity();
            npc.setLegacyUUID(targetUuid);
            npc.setRoleName(scenario.targetTamed() ? "Tamed_DamageAdapter" : "Wild_DamageAdapter");
            targetTransform = new TransformComponent();
            targetTransform.setPosition(TARGET_POSITION);
            Player attacker = (Player) unsafe().allocateInstance(Player.class);
            attacker.setLegacyUUID(ATTACKER);
            store.put(targetRef, NPCEntity.getComponentType(), npc);
            store.put(targetRef, TransformComponent.getComponentType(), targetTransform);
            store.put(attackerRef, Player.getComponentType(), attacker);
            TameworkOwnerComponent owner = ownerComponent();
            if (owner != null) {
                store.put(targetRef, ownerType, owner);
            }
            TameworkCommandLinksComponent links = linksComponent();
            if (links != null) {
                store.put(targetRef, linksType, links);
            }
            TameworkNpcNameComponent name = nameComponent();
            if (name != null) {
                store.put(targetRef, nameType, name);
            }
        }

        @Nonnull
        public static WorldFixture open(@Nonnull Scenario scenario) throws Exception {
            return new WorldFixture(scenario);
        }

        @Nonnull
        public UUID targetUuid() {
            return targetUuid;
        }

        @Nonnull
        public World world() {
            return world;
        }

        @Nonnull
        public Store<EntityStore> store() {
            return store;
        }

        @Nonnull
        public Damage newDamage() {
            Damage.Source source = switch (scenario.sourceKind()) {
                case DIRECT -> new Damage.EntitySource(attackerRef);
                case PROJECTILE -> new Damage.ProjectileSource(attackerRef, projectileRef);
                case ENVIRONMENT -> new Damage.EnvironmentSource("damage-adapter-fixture");
            };
            Damage damage = new Damage(source, 0, 10.0f);
            damage.setCancelled(scenario.initiallyCancelled());
            return damage;
        }

        public void invoke(@Nonnull OwnerDamageFilterSystem system, @Nonnull Damage damage) throws Exception {
            setObjectField(system, OwnerDamageFilterSystem.class, "ownerType", ownerType);
            setObjectField(system, OwnerDamageFilterSystem.class, "linksType", linksType);
            setObjectField(system, OwnerDamageFilterSystem.class, "npcNameType", nameType);
            ArchetypeChunk<EntityStore> chunk = new TestChunk(
                    store,
                    targetRef,
                    ownerType,
                    linksType,
                    nameType,
                    TransformComponent.getComponentType(),
                    ownerComponent(),
                    linksComponent(),
                    nameComponent(),
                    targetTransform
            );
            system.handle(0, chunk, store, null, damage);
        }

        @Nullable
        private TameworkOwnerComponent ownerComponent() {
            if (scenario.liveOwnerEvidence() != LiveOwnerEvidence.CANONICAL
                    && scenario.liveOwnerEvidence()
                    != LiveOwnerEvidence.CANONICAL_WITH_DERIVED_METADATA) {
                return null;
            }
            UUID owner = scenario.targetOwnerUuid();
            return owner == null ? null : new TameworkOwnerComponent(owner, "Fixture Owner");
        }

        @Nullable
        private TameworkCommandLinksComponent linksComponent() {
            return switch (scenario.liveOwnerEvidence()) {
                case CANONICAL_WITH_DERIVED_METADATA ->
                        new TameworkCommandLinksComponent(LINKS_OWNER, new String[] {"fixture-tool"});
                case COMMAND_LINK_ONLY ->
                        new TameworkCommandLinksComponent(ATTACKER, new String[] {"fixture-tool"});
                default -> null;
            };
        }

        @Nullable
        private TameworkNpcNameComponent nameComponent() {
            UUID owner = switch (scenario.liveOwnerEvidence()) {
                case CANONICAL_WITH_DERIVED_METADATA -> NAME_OWNER;
                case NPC_NAME_ONLY -> ATTACKER;
                default -> null;
            };
            return owner == null ? null : new TameworkNpcNameComponent(
                    "Fixture Name", owner, 1L, TameworkNpcNameComponent.NameSource.Player
            );
        }

        @Override
        public void close() {
            store.close();
        }
    }

    /** Makes the fixture world visible (or deliberately invisible) to the production API resolver. */
    public static final class UniverseScope implements AutoCloseable {
        private final Object oldUniverse;

        private UniverseScope(Object oldUniverse) {
            this.oldUniverse = oldUniverse;
        }

        @Nonnull
        public static UniverseScope install(@Nonnull World world, boolean exposeWorld) throws Exception {
            Field instanceField = staticField(Universe.class, "instance");
            UniverseScope scope = new UniverseScope(instanceField.get(null));
            TestUniverse universe = (TestUniverse) unsafe().allocateInstance(TestUniverse.class);
            universe.worlds = exposeWorld ? Map.of(world.getName(), world) : Map.of();
            instanceField.set(null, universe);
            return scope;
        }

        @Override
        public void close() throws Exception {
            staticField(Universe.class, "instance").set(null, oldUniverse);
        }
    }

    private static final class TestChunk extends ArchetypeChunk<EntityStore> {
        private final Ref<EntityStore> targetRef;
        private final ComponentType<EntityStore, TameworkOwnerComponent> ownerType;
        private final ComponentType<EntityStore, TameworkCommandLinksComponent> linksType;
        private final ComponentType<EntityStore, TameworkNpcNameComponent> nameType;
        private final ComponentType<EntityStore, TransformComponent> transformType;
        @Nullable
        private final TameworkOwnerComponent owner;
        @Nullable
        private final TameworkCommandLinksComponent links;
        @Nullable
        private final TameworkNpcNameComponent name;
        private final TransformComponent transform;

        private TestChunk(Store<EntityStore> store,
                          Ref<EntityStore> targetRef,
                          ComponentType<EntityStore, TameworkOwnerComponent> ownerType,
                          ComponentType<EntityStore, TameworkCommandLinksComponent> linksType,
                          ComponentType<EntityStore, TameworkNpcNameComponent> nameType,
                          ComponentType<EntityStore, TransformComponent> transformType,
                          @Nullable TameworkOwnerComponent owner,
                          @Nullable TameworkCommandLinksComponent links,
                          @Nullable TameworkNpcNameComponent name,
                          TransformComponent transform) {
            super(store, Archetype.empty());
            this.targetRef = targetRef;
            this.ownerType = ownerType;
            this.linksType = linksType;
            this.nameType = nameType;
            this.transformType = transformType;
            this.owner = owner;
            this.links = links;
            this.name = name;
            this.transform = transform;
        }

        @Override
        public Ref<EntityStore> getReferenceTo(int index) {
            return targetRef;
        }

        @Nullable
        @Override
        @SuppressWarnings("unchecked")
        public <T extends Component<EntityStore>> T getComponent(
                int index,
                ComponentType<EntityStore, T> type) {
            if ((Object) type == ownerType) {
                return (T) owner;
            }
            if ((Object) type == linksType) {
                return (T) links;
            }
            if ((Object) type == nameType) {
                return (T) name;
            }
            if ((Object) type == transformType) {
                return (T) transform;
            }
            return null;
        }
    }

    private static final class TestEntityStore extends EntityStore {
        private Store<EntityStore> store;

        private TestEntityStore(World world) {
            super(world);
        }

        @Override
        public Store<EntityStore> getStore() {
            return store;
        }
    }

    private static final class TestWorld extends World {
        private String testName;
        private UUID targetUuid;
        private Ref<EntityStore> targetRef;
        private EntityStore testEntityStore;

        private TestWorld() throws IOException {
            super("unused", Path.of("."), new WorldConfig());
        }

        private void initialize(String name,
                                UUID targetUuid,
                                Ref<EntityStore> targetRef,
                                EntityStore entityStore) {
            this.testName = name;
            this.targetUuid = targetUuid;
            this.targetRef = targetRef;
            this.testEntityStore = entityStore;
        }

        @Override
        public String getName() {
            return testName;
        }

        @Override
        public Ref<EntityStore> getEntityRef(UUID uuid) {
            return targetUuid.equals(uuid) ? targetRef : null;
        }

        @Override
        public EntityStore getEntityStore() {
            return testEntityStore;
        }
    }

    private static final class TestUniverse extends Universe {
        private Map<String, World> worlds;

        private TestUniverse(JavaPluginInit init) {
            super(init);
        }

        @Override
        public Map<String, World> getWorlds() {
            return worlds;
        }
    }

    private static void setObjectField(Object target, Class<?> owner, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        unsafe().putObject(target, unsafe().objectFieldOffset(field), value);
    }

    private static Field staticField(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }
}
