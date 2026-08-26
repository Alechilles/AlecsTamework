package com.alechilles.alecstamework.selftest;

import com.alechilles.alecstamework.api.TameworkApi;
import com.alechilles.alecstamework.api.TameworkApiCapability;
import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudController;
import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudSessionContributor;
import com.alechilles.alecstamework.api.commandhud.CommandHudApi;
import com.alechilles.alecstamework.api.commandhud.CommandHudContribution;
import com.alechilles.alecstamework.api.commandhud.CommandHudContributorDescriptor;
import com.alechilles.alecstamework.api.commandhud.CommandHudOpenContext;
import com.alechilles.alecstamework.api.commandhud.CommandHudRegistration;
import com.alechilles.alecstamework.api.commandhud.CommandHudRegistrationResult;
import com.alechilles.alecstamework.api.commandhud.CommandHudRendererDescriptor;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudController;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudSessionContributor;
import com.alechilles.alecstamework.api.commandui.CommandUiValue;
import com.alechilles.alecstamework.items.CommandHudSelfTestRuntime;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Runs the detached public command HUD registration and composition smoke test. */
public final class CommandHudApiSelfTestSuite {
    private static final EnumSet<TameworkApiCapability> REQUIRED_CAPABILITIES = EnumSet.of(
            TameworkApiCapability.COMMAND_HUD_RENDERERS,
            TameworkApiCapability.COMMAND_HUD_CONTRIBUTORS);

    private CommandHudApiSelfTestSuite() {
    }

    /** Runs the suite without changing durable gameplay state. */
    @Nonnull
    public static ApiSelfTestSuiteResult run(@Nonnull ApiSelfTestContext context) {
        ArrayList<ApiSelfTestAssertion> assertions = new ArrayList<>();
        TameworkApi api = context.api();
        EnumSet<TameworkApiCapability> capabilities = api.getCapabilities();
        assertions.add(check(
                "command HUD capabilities advertised",
                capabilities.containsAll(REQUIRED_CAPABILITIES),
                "capabilities=" + capabilities));
        CommandHudApi commandHud = api.commandHud();
        assertions.add(check(
                "command HUD registration available",
                commandHud.available(),
                "available=" + commandHud.available()));
        if (!commandHud.available() || !capabilities.containsAll(REQUIRED_CAPABILITIES)) {
            return new ApiSelfTestSuiteResult("command-hud", assertions);
        }

        UUID playerUuid = resolvePlayerUuid(context);
        String suffix = playerUuid.toString().replace("-", "");
        String targetRendererId = "selftest:" + suffix + "/target-renderer";
        String targetContributorId = "selftest:" + suffix + "/target-contributor";
        String hotswapRendererId = "selftest:" + suffix + "/hotswap-renderer";
        String hotswapContributorId = "selftest:" + suffix + "/hotswap-contributor";
        AtomicInteger targetRendererCreates = new AtomicInteger();
        AtomicInteger hotswapRendererCreates = new AtomicInteger();
        AtomicInteger targetContributorCreates = new AtomicInteger();
        AtomicInteger hotswapContributorCreates = new AtomicInteger();
        AtomicInteger targetFocusedScopes = new AtomicInteger();
        AtomicInteger hotswapFocusedScopes = new AtomicInteger();

        CommandHudRegistration targetRenderer = null;
        CommandHudRegistration targetContributor = null;
        CommandHudRegistration hotswapRenderer = null;
        CommandHudRegistration hotswapContributor = null;
        try {
            targetRenderer = registeredTargetRenderer(commandHud, targetRendererId,
                    targetContributorId, targetRendererCreates);
            assertions.add(check("target HUD renderer registers", targetRenderer != null,
                    targetRendererId));
            targetContributor = registeredTargetContributor(commandHud, targetContributorId,
                    targetContributorCreates, targetFocusedScopes);
            assertions.add(check("target HUD contributor registers", targetContributor != null,
                    targetContributorId));
            hotswapRenderer = registeredHotswapRenderer(commandHud, hotswapRendererId,
                    hotswapContributorId, hotswapRendererCreates);
            assertions.add(check("hotswap HUD renderer registers", hotswapRenderer != null,
                    hotswapRendererId));
            hotswapContributor = registeredHotswapContributor(commandHud, hotswapContributorId,
                    hotswapContributorCreates, hotswapFocusedScopes);
            assertions.add(check("hotswap HUD contributor registers", hotswapContributor != null,
                    hotswapContributorId));

            if (targetRenderer == null || targetContributor == null
                    || hotswapRenderer == null || hotswapContributor == null) {
                return new ApiSelfTestSuiteResult("command-hud", assertions);
            }
            CommandHudSelfTestRuntime.CommandHudSelfTestResult result =
                    CommandHudSelfTestRuntime.run(
                            commandHud, playerUuid, targetRendererId, targetContributorId,
                            hotswapRendererId, hotswapContributorId);
            assertions.add(check(
                    "target HUD renderer composes",
                    result != null && result.targetRendererCreated()
                            && targetRendererCreates.get() > 0,
                    result == null ? "handler unavailable"
                            : "created=" + result.targetRendererCreated()));
            assertions.add(check(
                    "target HUD contributor composes",
                    result != null && result.targetContributionReady()
                            && targetContributorCreates.get() > 0,
                    result == null ? "handler unavailable"
                            : "ready=" + result.targetContributionReady()));
            assertions.add(check(
                    "target HUD focused refreshes",
                    result != null && result.targetFocusedRefresh()
                            && targetFocusedScopes.get() > 0,
                    result == null ? "handler unavailable"
                            : "focused=" + result.targetFocusedRefresh()));
            assertions.add(check(
                    "hotswap HUD renderer composes",
                    result != null && result.hotswapRendererCreated()
                            && hotswapRendererCreates.get() > 0,
                    result == null ? "handler unavailable"
                            : "created=" + result.hotswapRendererCreated()));
            assertions.add(check(
                    "hotswap HUD contributor composes",
                    result != null && result.hotswapContributionReady()
                            && hotswapContributorCreates.get() > 0,
                    result == null ? "handler unavailable"
                            : "ready=" + result.hotswapContributionReady()));
            assertions.add(check(
                    "hotswap HUD focused refreshes",
                    result != null && result.hotswapFocusedRefresh()
                            && hotswapFocusedScopes.get() > 0,
                    result == null ? "handler unavailable"
                            : "focused=" + result.hotswapFocusedRefresh()));
            assertions.add(check(
                    "command HUD sessions close",
                    result != null && result.targetSessionClosed()
                            && result.hotswapSessionClosed(),
                    result == null ? "handler unavailable"
                            : "target=" + result.targetSessionClosed()
                            + ", hotswap=" + result.hotswapSessionClosed()));
        } finally {
            close(targetContributor);
            close(targetRenderer);
            close(hotswapContributor);
            close(hotswapRenderer);
        }
        assertions.add(cleanupAssertion(commandHud, targetRendererId, targetContributorId,
                hotswapRendererId, hotswapContributorId));
        return new ApiSelfTestSuiteResult("command-hud", assertions);
    }

    @Nonnull
    private static UUID resolvePlayerUuid(@Nonnull ApiSelfTestContext context) {
        if (context.player() != null && context.player().getUuid() != null) {
            return context.player().getUuid();
        }
        return UUID.nameUUIDFromBytes(
                "tamework-command-hud-selftest".getBytes(StandardCharsets.UTF_8));
    }

    @Nullable
    private static CommandHudRegistration registeredTargetRenderer(
            @Nonnull CommandHudApi api,
            @Nonnull String rendererId,
            @Nonnull String contributorId,
            @Nonnull AtomicInteger creates
    ) {
        CommandHudRegistrationResult result = api.registerTargetRenderer(
                rendererId, new CommandHudRendererDescriptor(Set.of(contributorId)), context -> {
                    creates.incrementAndGet();
                    return new CommandTargetHudController() {
                        @Override
                        public void buildInitial(CommandHudOpenContext context,
                                                 com.alechilles.alecstamework.api.commandhud.CommandTargetHudView view,
                                                 UICommandBuilder commands) {
                        }
                    };
                });
        return result.registration();
    }

    @Nullable
    private static CommandHudRegistration registeredHotswapRenderer(
            @Nonnull CommandHudApi api,
            @Nonnull String rendererId,
            @Nonnull String contributorId,
            @Nonnull AtomicInteger creates
    ) {
        CommandHudRegistrationResult result = api.registerHotswapRenderer(
                rendererId, new CommandHudRendererDescriptor(Set.of(contributorId)), context -> {
                    creates.incrementAndGet();
                    return new CommandHotswapHudController() {
                        @Override
                        public void buildInitial(CommandHudOpenContext context,
                                                 com.alechilles.alecstamework.api.commandhud.CommandHotswapHudView view,
                                                 UICommandBuilder commands) {
                        }
                    };
                });
        return result.registration();
    }

    @Nullable
    private static CommandHudRegistration registeredTargetContributor(
            @Nonnull CommandHudApi api,
            @Nonnull String contributorId,
            @Nonnull AtomicInteger creates,
            @Nonnull AtomicInteger focusedScopes
    ) {
        CommandHudRegistrationResult result = api.registerTargetContributor(
                contributorId, new CommandHudContributorDescriptor(Set.of("selftest")), context -> {
                    creates.incrementAndGet();
                    return targetContributor(context, focusedScopes);
                });
        return result.registration();
    }

    @Nullable
    private static CommandHudRegistration registeredHotswapContributor(
            @Nonnull CommandHudApi api,
            @Nonnull String contributorId,
            @Nonnull AtomicInteger creates,
            @Nonnull AtomicInteger focusedScopes
    ) {
        CommandHudRegistrationResult result = api.registerHotswapContributor(
                contributorId, new CommandHudContributorDescriptor(Set.of("selftest")), context -> {
                    creates.incrementAndGet();
                    return hotswapContributor(context, focusedScopes);
                });
        return result.registration();
    }

    @Nonnull
    private static CommandTargetHudSessionContributor targetContributor(
            @Nonnull com.alechilles.alecstamework.api.commandhud.CommandHudContributorCreateContext context,
            @Nonnull AtomicInteger focusedScopes
    ) {
        return (base, previous, scope) -> {
            if (scope.contains("probe/value")) focusedScopes.incrementAndGet();
            return CommandHudContribution.available(context.contributorId(),
                    Map.of("probe/value", CommandUiValue.of(scope.fullRefresh() ? "full" : "focused")));
        };
    }

    @Nonnull
    private static CommandHotswapHudSessionContributor hotswapContributor(
            @Nonnull com.alechilles.alecstamework.api.commandhud.CommandHudContributorCreateContext context,
            @Nonnull AtomicInteger focusedScopes
    ) {
        return (base, previous, scope) -> {
            if (scope.contains("probe/value")) focusedScopes.incrementAndGet();
            return CommandHudContribution.available(context.contributorId(),
                    Map.of("probe/value", CommandUiValue.of(scope.fullRefresh() ? "full" : "focused")));
        };
    }

    @Nonnull
    private static ApiSelfTestAssertion cleanupAssertion(
            @Nonnull CommandHudApi api,
            @Nonnull String... ids
    ) {
        var diagnostics = api.diagnostics();
        boolean clean = diagnostics.renderers().stream().noneMatch(
                registration -> contains(ids, registration.rendererId()))
                && diagnostics.contributors().stream().noneMatch(
                registration -> contains(ids, registration.contributorId()))
                && diagnostics.sessions().stream().noneMatch(session ->
                contains(ids, session.rendererId()) || contains(ids, session.itemId())
                        || contains(ids, session.configId()));
        return check("command HUD registrations clean up", clean,
                "renderers=" + diagnostics.renderers().size()
                        + ", contributors=" + diagnostics.contributors().size()
                        + ", sessions=" + diagnostics.sessions().size());
    }

    private static boolean contains(@Nonnull String[] ids, @Nullable String value) {
        if (value == null) return false;
        for (String id : ids) {
            if (value.equals(id)) return true;
        }
        return false;
    }

    private static void close(@Nullable CommandHudRegistration registration) {
        if (registration != null) registration.close();
    }

    @Nonnull
    private static ApiSelfTestAssertion check(
            @Nonnull String name,
            boolean passed,
            @Nonnull String detail
    ) {
        return new ApiSelfTestAssertion(name, passed, detail);
    }
}
