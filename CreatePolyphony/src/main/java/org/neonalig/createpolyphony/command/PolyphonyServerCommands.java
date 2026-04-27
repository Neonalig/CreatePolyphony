package org.neonalig.createpolyphony.command;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.neonalig.createpolyphony.CreatePolyphony;
import org.neonalig.createpolyphony.link.PolyphonyLinkManager;

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
                .executes(ctx -> unlinkCurrent(ctx.getSource()))));
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
}

