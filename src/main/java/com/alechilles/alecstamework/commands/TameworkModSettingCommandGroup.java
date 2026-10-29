package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.TwModSetting;
import com.alechilles.alecstamework.settings.mods.ModSettingsCatalog;
import com.alechilles.alecstamework.settings.mods.ModSettingsService;
import com.google.gson.JsonPrimitive;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes.STRING;

/**
 * Console-friendly commands for settings that mods declare under Server/Tamework/ModSettings:
 * {@code /tw modsetting list|get|set|reset}.
 */
public final class TameworkModSettingCommandGroup extends AbstractCommandCollection {
    private static final String KEY = "server.tamework.commands.modSetting.";

    public TameworkModSettingCommandGroup() {
        super("modsetting", KEY + "description");
        addSubCommand(new ListCommand());
        addSubCommand(new GetCommand());
        addSubCommand(new SetCommand());
        addSubCommand(new ResetCommand());
    }

    private abstract static class ModSettingCommand extends AbstractAsyncCommand {
        ModSettingCommand(String name, String descriptionKey) {
            super(name, descriptionKey);
            requirePermission(TameworkConfigPermission.NODE);
            setPermissionGroups(TameworkConfigPermission.adminPermissionGroups());
        }

        @Override
        protected final CompletableFuture<Void> executeAsync(@Nonnull CommandContext context) {
            Tamework plugin = Tamework.getInstance();
            ModSettingsService service = plugin == null ? null : plugin.getModSettingsService();
            if (service == null) {
                context.sendMessage(Message.translation(KEY + "unavailable"));
                return CompletableFuture.completedFuture(null);
            }
            return run(context, service);
        }

        abstract CompletableFuture<Void> run(@Nonnull CommandContext context, @Nonnull ModSettingsService service);
    }

    private static final class ListCommand extends ModSettingCommand {
        ListCommand() {
            super("list", KEY + "list.description");
        }

        @Override
        CompletableFuture<Void> run(@Nonnull CommandContext context, @Nonnull ModSettingsService service) {
            ModSettingsCatalog catalog = service.catalog();
            if (catalog.mods().isEmpty()) {
                context.sendMessage(Message.translation(KEY + "none"));
                return CompletableFuture.completedFuture(null);
            }
            for (ModSettingsCatalog.ModSection mod : catalog.mods()) {
                for (ModSettingsCatalog.Group group : mod.groups()) {
                    context.sendMessage(Message.translation(KEY + "groupHeader")
                            .param("0", mod.modName())
                            .param("1", label(group.labelKey(), group.groupId())));
                    for (TwModSetting setting : group.settings()) {
                        sendValue(context, service, setting);
                    }
                }
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class GetCommand extends ModSettingCommand {
        private final RequiredArg<String> idArg = withRequiredArg("id", KEY + "argument.id", STRING);

        GetCommand() {
            super("get", KEY + "get.description");
        }

        @Override
        CompletableFuture<Void> run(@Nonnull CommandContext context, @Nonnull ModSettingsService service) {
            TwModSetting setting = find(context, service, idArg.get(context));
            if (setting != null) {
                sendValue(context, service, setting);
                sendOptions(context, setting);
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class SetCommand extends ModSettingCommand {
        private final RequiredArg<String> idArg = withRequiredArg("id", KEY + "argument.id", STRING);
        private final RequiredArg<String> valueArg = withRequiredArg("value", KEY + "argument.value", STRING);

        SetCommand() {
            super("set", KEY + "set.description");
        }

        @Override
        CompletableFuture<Void> run(@Nonnull CommandContext context, @Nonnull ModSettingsService service) {
            TwModSetting setting = find(context, service, idArg.get(context));
            if (setting == null) {
                return CompletableFuture.completedFuture(null);
            }
            String text = valueArg.get(context);
            JsonPrimitive value = setting.parse(text);
            if (value == null) {
                context.sendMessage(Message.translation(KEY + "invalidValue").param("0", text).param("1", setting.getId()));
                sendOptions(context, setting);
                return CompletableFuture.completedFuture(null);
            }
            return save(context, service, setting, value);
        }
    }

    private static final class ResetCommand extends ModSettingCommand {
        private final RequiredArg<String> idArg = withRequiredArg("id", KEY + "argument.id", STRING);

        ResetCommand() {
            super("reset", KEY + "reset.description");
        }

        @Override
        CompletableFuture<Void> run(@Nonnull CommandContext context, @Nonnull ModSettingsService service) {
            TwModSetting setting = find(context, service, idArg.get(context));
            return setting == null ? CompletableFuture.completedFuture(null) : save(context, service, setting, null);
        }
    }

    private static CompletableFuture<Void> save(@Nonnull CommandContext context,
                                                @Nonnull ModSettingsService service,
                                                @Nonnull TwModSetting setting,
                                                @Nullable JsonPrimitive value) {
        Map<String, JsonPrimitive> change = new HashMap<>();
        change.put(setting.getId(), value);
        return service.apply(change).<Void>handle((outcome, failure) -> {
            if (failure != null) {
                context.sendMessage(Message.translation(KEY + "saveFailed"));
                return null;
            }
            JsonPrimitive now = service.value(setting.getId());
            context.sendMessage(Message.translation(KEY + "saved." + outcomeKey(outcome))
                    .param("0", setting.getId())
                    .param("1", now == null ? "" : now.getAsString()));
            return null;
        }).toCompletableFuture();
    }

    @Nonnull
    private static String outcomeKey(@Nonnull ModSettingsService.ApplyOutcome outcome) {
        return switch (outcome) {
            case APPLIED -> "applied";
            case RESTART_REQUIRED -> "restart";
            case PATCH_FAILURES -> "failures";
            case NOT_REGENERATED -> "notRegenerated";
        };
    }

    @Nullable
    private static TwModSetting find(@Nonnull CommandContext context, @Nonnull ModSettingsService service, @Nonnull String id) {
        TwModSetting setting = service.catalog().setting(id);
        if (setting == null) {
            context.sendMessage(Message.translation(KEY + "unknown").param("0", id));
        }
        return setting;
    }

    private static void sendValue(@Nonnull CommandContext context, @Nonnull ModSettingsService service, @Nonnull TwModSetting setting) {
        JsonPrimitive value = service.value(setting.getId());
        context.sendMessage(Message.translation(KEY + (service.isCustomized(setting.getId()) ? "value" : "valueDefault"))
                .param("0", label(setting.getLabel(), setting.getId()))
                .param("1", setting.getId())
                .param("2", value == null ? "" : value.getAsString()));
    }

    private static void sendOptions(@Nonnull CommandContext context, @Nonnull TwModSetting setting) {
        if (setting instanceof TwModSetting.EnumSetting enumSetting) {
            List<String> values = new ArrayList<>();
            for (TwModSetting.EnumOption option : enumSetting.getOptions()) {
                if (option != null && option.getValue() != null) values.add(option.getValue());
            }
            context.sendMessage(Message.translation(KEY + "options").param("0", String.join(", ", values)));
        } else if (setting instanceof TwModSetting.IntegerSetting number && (number.getMin() != null || number.getMax() != null)) {
            context.sendMessage(Message.translation(KEY + "range")
                    .param("0", number.getMin() == null ? "-" : String.valueOf(number.getMin()))
                    .param("1", number.getMax() == null ? "-" : String.valueOf(number.getMax())));
        } else if (setting instanceof TwModSetting.DecimalSetting number && (number.getMin() != null || number.getMax() != null)) {
            context.sendMessage(Message.translation(KEY + "range")
                    .param("0", number.getMin() == null ? "-" : String.valueOf(number.getMin()))
                    .param("1", number.getMax() == null ? "-" : String.valueOf(number.getMax())));
        } else if (setting.getType() == com.alechilles.alecstamework.config.assets.TwModSettingType.BOOLEAN) {
            context.sendMessage(Message.translation(KEY + "options").param("0", "true, false"));
        }
    }

    @Nonnull
    private static Message label(@Nullable String key, @Nonnull String fallback) {
        return key == null ? Message.raw(fallback) : Message.translation(key);
    }
}
