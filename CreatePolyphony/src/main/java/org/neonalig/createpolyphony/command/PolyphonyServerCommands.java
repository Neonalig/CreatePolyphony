package org.neonalig.createpolyphony.command;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.neonalig.createpolyphony.CreatePolyphony;
import org.neonalig.createpolyphony.link.PolyphonyLink;
import org.neonalig.createpolyphony.link.PolyphonyLinkManager;
import org.neonalig.createpolyphony.network.PlayInstrumentNotePayload;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static net.minecraft.commands.Commands.literal;

/**
 * Server-side commands for link management and admin safety controls.
 */
@SuppressWarnings("removal")
@EventBusSubscriber(modid = CreatePolyphony.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class PolyphonyServerCommands {

    private PolyphonyServerCommands() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(literal("cpoly")
            .then(literal("unlink")
                .executes(ctx -> unlinkCurrent(ctx.getSource())))
            .then(literal("panic")
                .requires(source -> source.hasPermission(2))
                .executes(ctx -> panicAll(ctx.getSource()))));
    }

    private static int unlinkCurrent(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ItemStack held = player.getMainHandItem();
        if (PolyphonyLinkManager.unlinkHeldInstrument(player, held)) {
            source.sendSuccess(() -> Component.translatable("command.createpolyphony.unlink.success"), false);
            return 1;
        }
        source.sendFailure(Component.translatable("command.createpolyphony.unlink.failed"));
        return 0;
    }

    private static int panicAll(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        Map<PolyphonyLinkManager.LinkKey, PolyphonyLink> snapshot = PolyphonyLinkManager.snapshot();
        Set<UUID> recipients = new HashSet<>();
        for (PolyphonyLink link : snapshot.values()) {
            recipients.addAll(link.players().keySet());
        }

        int count = 0;
        for (UUID uuid : recipients) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player == null) continue;
            PacketDistributor.sendToPlayer(player, new PlayInstrumentNotePayload(0, 0, 0xF0, 0, 0));
            count++;
        }

        String msg = "Panic broadcast sent to " + count + " linked players.";
        source.sendSuccess(() -> Component.literal(msg), true);
        return 1;
    }
}

