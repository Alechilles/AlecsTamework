package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudController;
import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudSessionContributor;
import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudView;
import com.alechilles.alecstamework.api.commandhud.CommandHudContribution;
import com.alechilles.alecstamework.api.commandhud.CommandHudContributorDescriptor;
import com.alechilles.alecstamework.api.commandhud.CommandHudOpenContext;
import com.alechilles.alecstamework.api.commandhud.CommandHudRegistration;
import com.alechilles.alecstamework.api.commandhud.CommandHudRendererDescriptor;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudController;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudSessionContributor;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudView;
import com.alechilles.alecstamework.api.commandui.CommandUiValue;
import com.alechilles.alecstamework.api.internal.CommandHudRegistry;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the detached command HUD self-test through real registry composition. */
class CommandHudSelfTestRuntimeTest {
    private static final UUID PLAYER_UUID = UUID.fromString(
            "e839f9f4-cb53-4e9d-a8c4-2ea8d9f8a3a8");

    @Test
    void composesBothSurfacesAndCleansUp() throws Exception {
        CommandHudRegistry registry = new CommandHudRegistry();
        String targetRendererId = "selftest:target-renderer";
        String targetContributorId = "selftest:target-contributor";
        String hotswapRendererId = "selftest:hotswap-renderer";
        String hotswapContributorId = "selftest:hotswap-contributor";
        CommandHudRegistration targetRenderer = registerTargetRenderer(
                registry, targetRendererId, targetContributorId);
        CommandHudRegistration targetContributor = registerTargetContributor(
                registry, targetContributorId);
        CommandHudRegistration hotswapRenderer = registerHotswapRenderer(
                registry, hotswapRendererId, hotswapContributorId);
        CommandHudRegistration hotswapContributor = registerHotswapContributor(
                registry, hotswapContributorId);
        try {
            CommandHudSelfTestRuntime.CommandHudSelfTestResult result =
                    CommandHudSelfTestRuntime.run(
                            registry, PLAYER_UUID, targetRendererId, targetContributorId,
                            hotswapRendererId, hotswapContributorId);

            assertTrue(result.targetRendererCreated());
            assertTrue(result.targetContributionReady());
            assertTrue(result.targetFocusedRefresh());
            assertTrue(result.targetSessionClosed());
            assertTrue(result.hotswapRendererCreated());
            assertTrue(result.hotswapContributionReady());
            assertTrue(result.hotswapFocusedRefresh());
            assertTrue(result.hotswapSessionClosed());
            assertTrue(registry.diagnostics().sessions().isEmpty());
        } finally {
            close(targetContributor);
            close(targetRenderer);
            close(hotswapContributor);
            close(hotswapRenderer);
            assertFalse(registry.diagnostics().renderers().stream().anyMatch(
                    value -> value.rendererId().startsWith("selftest:")));
            assertFalse(registry.diagnostics().contributors().stream().anyMatch(
                    value -> value.contributorId().startsWith("selftest:")));
            registry.close();
        }
    }

    private static CommandHudRegistration registerTargetRenderer(
            CommandHudRegistry registry, String rendererId, String contributorId) {
        return registry.registerTargetRenderer(rendererId,
                new CommandHudRendererDescriptor(Set.of(contributorId)), ignored ->
                        new CommandTargetHudController() {
                            @Override
                            public void buildInitial(CommandHudOpenContext context,
                                                     CommandTargetHudView view,
                                                     UICommandBuilder commands) {
                            }
                        }).registration();
    }

    private static CommandHudRegistration registerHotswapRenderer(
            CommandHudRegistry registry, String rendererId, String contributorId) {
        return registry.registerHotswapRenderer(rendererId,
                new CommandHudRendererDescriptor(Set.of(contributorId)), ignored ->
                        new CommandHotswapHudController() {
                            @Override
                            public void buildInitial(CommandHudOpenContext context,
                                                     CommandHotswapHudView view,
                                                     UICommandBuilder commands) {
                            }
                        }).registration();
    }

    private static CommandHudRegistration registerTargetContributor(
            CommandHudRegistry registry, String contributorId) {
        return registry.registerTargetContributor(contributorId,
                new CommandHudContributorDescriptor(Set.of("selftest")), context ->
                        (CommandTargetHudSessionContributor) (base, previous, scope) ->
                                CommandHudContribution.available(context.contributorId(),
                                        Map.of("probe/value", CommandUiValue.of("ready"))))
                .registration();
    }

    private static CommandHudRegistration registerHotswapContributor(
            CommandHudRegistry registry, String contributorId) {
        return registry.registerHotswapContributor(contributorId,
                new CommandHudContributorDescriptor(Set.of("selftest")), context ->
                        (CommandHotswapHudSessionContributor) (base, previous, scope) ->
                                CommandHudContribution.available(context.contributorId(),
                                        Map.of("probe/value", CommandUiValue.of("ready"))))
                .registration();
    }

    private static void close(CommandHudRegistration registration) {
        if (registration != null) registration.close();
    }
}
