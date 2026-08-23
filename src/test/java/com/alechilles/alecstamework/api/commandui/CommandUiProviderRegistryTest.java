package com.alechilles.alecstamework.api.commandui;

import com.alechilles.alecstamework.api.DiagnosticsApi;
import com.alechilles.alecstamework.api.InteractionExtensionApi;
import com.alechilles.alecstamework.api.NpcProfilesApi;
import com.alechilles.alecstamework.api.ProfileDataApi;
import com.alechilles.alecstamework.api.TameworkApiCapability;
import com.alechilles.alecstamework.api.TraitEffectApi;
import com.alechilles.alecstamework.api.internal.TameworkApiImpl;
import com.alechilles.alecstamework.api.internal.TameworkEventBus;
import com.alechilles.alecstamework.damage.SimpleClaimsTamedDamagePolicy;
import com.alechilles.alecstamework.api.internal.CommandUiProviderRegistry;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Observable registration behavior for the command UI provider facade. */
class CommandUiProviderRegistryTest {
    @Test
    void normalizesIdsAndKeepsFirstProviderOnDuplicate() {
        CommandUiProvider first = ignored -> null;
        CommandUiProvider second = ignored -> null;
        CommandUiProviderRegistry registry = new CommandUiProviderRegistry();

        CommandUiProviderRegistrationResult registered = registry.register(
                "  Runeteria:Husbandry  ", first
        );
        CommandUiProviderRegistrationResult duplicate = registry.register(
                "runeteria:husbandry", second
        );

        assertEquals(
                CommandUiProviderRegistrationResult.Status.REGISTERED,
                registered.status()
        );
        assertEquals(
                CommandUiProviderRegistrationResult.Status.CONFLICT,
                duplicate.status()
        );
        assertEquals(
                "runeteria:husbandry",
                registered.providerId().value()
        );
        assertSame(
                first,
                registry.find("RUNETERIA:HUSBANDRY").orElseThrow()
        );
        assertTrue(registered.registration().active());
    }

    @Test
    void closeRemovesOnlyItsGenerationAndIsIdempotent() {
        CommandUiProvider first = ignored -> null;
        CommandUiProvider second = ignored -> null;
        CommandUiProviderRegistry registry = new CommandUiProviderRegistry();

        CommandUiProviderRegistrationResult firstResult = registry.register(
                "example:menu", first
        );
        firstResult.registration().close();
        firstResult.registration().close();
        CommandUiProviderRegistrationResult secondResult = registry.register(
                "example:menu", second
        );
        firstResult.registration().close();

        assertFalse(firstResult.registration().active());
        assertTrue(secondResult.registration().active());
        assertSame(second, registry.find("example:menu").orElseThrow());

        registry.close();
        registry.close();
        assertFalse(registry.available());
        assertTrue(registry.find("example:menu").isEmpty());
    }

    @Test
    void rejectsInvalidAndReservedIdsWithoutMutatingRegistry() {
        CommandUiProviderRegistry registry = new CommandUiProviderRegistry();
        CommandUiProvider provider = ignored -> null;

        assertEquals(
                CommandUiProviderRegistrationResult.Status.INVALID,
                registry.register("menu", provider).status()
        );
        assertEquals(
                CommandUiProviderRegistrationResult.Status.INVALID,
                registry.register("tamework:standard", provider).status()
        );
        assertEquals(
                CommandUiProviderRegistrationResult.Status.INVALID,
                registry.register(" ", provider).status()
        );
        assertTrue(registry.listProviderIds().isEmpty());
    }

    @Test
    void unavailableFacadeFailsClosed() {
        CommandUiApi unavailable = CommandUiApi.unavailable();
        CommandUiProvider provider = ignored -> null;

        assertFalse(unavailable.available());
        assertEquals(
                CommandUiProviderRegistrationResult.Status.UNAVAILABLE,
                unavailable.register("example:menu", provider).status()
        );
        assertEquals(Optional.empty(), unavailable.find("example:menu"));
    }

    @Test
    void fullApiWithdrawsCapabilityWhenItsFacadeCloses() {
        TameworkApiImpl api = new TameworkApiImpl(
                noOp(NpcProfilesApi.class),
                noOp(ProfileDataApi.class),
                noOp(DiagnosticsApi.class),
                new TameworkEventBus(null),
                null,
                noOp(InteractionExtensionApi.class),
                noOp(TraitEffectApi.class),
                new SimpleClaimsTamedDamagePolicy()
        );

        assertTrue(api.commandUi().available());
        assertTrue(api.getCapabilities().contains(TameworkApiCapability.COMMAND_UI_PROVIDERS));

        api.close();

        assertFalse(api.commandUi().available());
        assertFalse(api.getCapabilities().contains(TameworkApiCapability.COMMAND_UI_PROVIDERS));
        assertEquals(
                CommandUiProviderRegistrationResult.Status.UNAVAILABLE,
                api.commandUi().register("example:menu", ignored -> null).status()
        );
    }

    @Test
    void controllerUsesEventPayloadCodecAndHytaleBuilders() {
        TestController controller = new TestController();
        UICommandBuilder commands = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        EventPayload payload = new EventPayload();

        assertSame(EventPayload.CODEC, controller.eventCodec());
        assertSame(EventPayload.CODEC, controller.codec());
        CommandUiSession session = noOp(CommandUiSession.class);
        CommandUiSnapshot snapshot = new CommandUiSnapshot(
                UUID.randomUUID(), 1L, 1L, null, List.of(), List.of(),
                new CommandUiPanelState("linked"));
        controller.buildInitial(new CommandUiOpenContext(), session, snapshot, commands, events);
        controller.handleEvent(payload, session, snapshot);

        assertSame(commands, controller.initialCommands);
        assertSame(events, controller.initialEvents);
        assertSame(payload, controller.handledEvent);
    }

    private static final class EventPayload {
        private static final BuilderCodec<EventPayload> CODEC =
                BuilderCodec.builder(EventPayload.class, EventPayload::new).build();
    }

    private static final class TestController implements CommandUiPageController<EventPayload> {
        private UICommandBuilder initialCommands;
        private UIEventBuilder initialEvents;
        private EventPayload handledEvent;

        @Override
        public BuilderCodec<EventPayload> eventCodec() {
            return EventPayload.CODEC;
        }

        @Override
        public void buildInitial(
                CommandUiOpenContext context,
                CommandUiSession session,
                CommandUiSnapshot snapshot,
                UICommandBuilder commandBuilder,
                UIEventBuilder eventBuilder
        ) {
            initialCommands = commandBuilder;
            initialEvents = eventBuilder;
        }

        @Override
        public void handleEvent(
                EventPayload event,
                CommandUiSession session,
                CommandUiSnapshot snapshot
        ) {
            handledEvent = event;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T noOp(Class<T> type) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[] {type},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("equals")) {
                        return proxy == arguments[0];
                    }
                    if (method.getName().equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    }
                    if (method.getName().equals("toString")) {
                        return type.getSimpleName() + "TestDouble";
                    }
                    Class<?> returnType = method.getReturnType();
                    if (returnType == boolean.class) return false;
                    if (returnType == byte.class) return (byte) 0;
                    if (returnType == short.class) return (short) 0;
                    if (returnType == int.class) return 0;
                    if (returnType == long.class) return 0L;
                    if (returnType == float.class) return 0F;
                    if (returnType == double.class) return 0D;
                    if (returnType == char.class) return '\0';
                    return null;
                }
        );
    }
}
