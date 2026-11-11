package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.access.OwnerGrants;
import com.alechilles.alecstamework.access.SharedAccessService;
import com.alechilles.alecstamework.api.access.AccessGroupProvider;
import com.alechilles.alecstamework.api.access.AccessLevel;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * {@code /tw share list}: what the calling player shares (named players, groups turned on, groups
 * still available, how many companions are private) and who shares with them. Reads memory only.
 */
final class TameworkShareListCommand extends TameworkShareCommandGroup.Leaf {
    private static final String KEY = TameworkShareCommandGroup.KEY + "list.";

    TameworkShareListCommand() {
        super("list");
    }

    @Override
    protected void execute(@Nonnull CommandContext context,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> ref,
                           @Nonnull PlayerRef playerRef,
                           @Nonnull World world) {
        SharedAccessService service = SharedAccessService.get();
        UUID sender = playerRef.getUuid();
        OwnerGrants grants = service.grants(sender);
        if (!service.isEnabled()) {
            context.sendMessage(Message.translation(KEY + "disabled"));
        }

        if (grants.players().isEmpty()) {
            context.sendMessage(Message.translation(KEY + "players.none"));
        } else {
            context.sendMessage(Message.translation(KEY + "players.header"));
            for (OwnerGrants.PlayerGrant grant : grants.players().values()) {
                context.sendMessage(entry(Message.raw(TameworkShareCommandGroup.grantName(grant)), grant.level()));
            }
        }

        if (!grants.groups().isEmpty()) {
            context.sendMessage(Message.translation(KEY + "groups.header"));
            for (Map.Entry<String, AccessLevel> group : grants.groups().entrySet()) {
                context.sendMessage(entry(TameworkShareCommandGroup.groupName(service, group.getKey()), group.getValue()));
            }
        }
        boolean availableHeaderSent = false;
        for (AccessGroupProvider provider : service.groupProviders()) {
            if (grants.groups().containsKey(provider.id())) {
                continue;
            }
            if (!availableHeaderSent) {
                context.sendMessage(Message.translation(KEY + "groups.available"));
                availableHeaderSent = true;
            }
            // The id is shown beside the name because the group command takes the id.
            context.sendMessage(Message.translation(KEY + "groups.availableEntry")
                    .param("0", Message.translation(provider.displayNameKey())).param("1", provider.id()));
        }

        context.sendMessage(Message.translation(KEY + "private").param("0", grants.privateCompanions().size()));

        boolean sharedHeaderSent = false;
        for (UUID owner : service.ownersSharingWith(sender)) {
            AccessLevel level = service.level(owner, sender, null);
            if (!level.isGrantable()) {
                continue;
            }
            if (!sharedHeaderSent) {
                context.sendMessage(Message.translation(KEY + "sharedWithYou.header"));
                sharedHeaderSent = true;
            }
            context.sendMessage(entry(Message.raw(TameworkShareCommandGroup.ownerName(owner)), level));
        }
        if (!sharedHeaderSent) {
            context.sendMessage(Message.translation(KEY + "sharedWithYou.none"));
        }
    }

    @Nonnull
    private static Message entry(@Nonnull Message name, @Nonnull AccessLevel level) {
        return Message.translation(KEY + "entry").param("0", name)
                .param("1", TameworkShareCommandGroup.levelName(level));
    }
}
