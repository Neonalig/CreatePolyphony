package org.neonalig.createpolyphony.command;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import org.neonalig.createpolyphony.network.PlayInstrumentNotePayload;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.Map;
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
                .executes(ctx -> unlinkCurrent(ctx.getSource())))
            .then(literal("panic")
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
        Map<?, ?> snapshot = PolyphonyLinkManager.snapshot();
        int count = 0;
        for (Object v : snapshot.values()) {
            try {
                // Each value is a PolyphonyLink; iterate its players map keys (UUID)
                java.lang.reflect.Method m = v.getClass().getMethod("players");
                Object playersMap = m.invoke(v);
                if (playersMap instanceof java.util.Map<?, ?> pm) {
                    for (Object uuidObj : pm.keySet()) {
                        if (!(uuidObj instanceof java.util.UUID uuid)) continue;
                        ServerPlayer p = server.getPlayerList().getPlayer(uuid);
                        if (p == null) continue;
                        PacketDistributor.sendToPlayer(p, new PlayInstrumentNotePayload(0, 0, 0xF0, 0, 0));
                        count++;
                    }
                }
            } catch (Throwable t) {
                // Reflection fallback: ignore this link if introspection fails.
                CreatePolyphony.LOGGER.warn("Failed to broadcast panic to link", t);
            }
        }
        String msg = "Panic broadcast sent to " + count + " players.";
        source.sendSuccess(() -> Component.literal(msg), true);
        return 1;
    }
}

