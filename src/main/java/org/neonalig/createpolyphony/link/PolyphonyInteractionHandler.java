package org.neonalig.createpolyphony.link;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.neonalig.createpolyphony.CreatePolyphony;
import org.neonalig.createpolyphony.instrument.InstrumentItem;

/**
 * NeoForge event subscribers that bridge in-world player actions to the
 * server-side {@link PolyphonyLinkManager}.
 *
 * <ul>
 *   <li>Right-clicking a Sound-of-Steam tracker bar with an
 *       {@link InstrumentItem} <em>links</em> the player to that tracker.</li>
 *   <li>Sneak-right-clicking the tracker bar with an instrument
 *       <em>unlinks</em> the player.</li>
 *   <li>Logging out unlinks automatically.</li>
 * </ul>
 *
 * <p>We identify the tracker bar by its registry id rather than by importing
 * its class. That way our compiled bytecode doesn't carry a hard reference to
 * Sound of Steam and the mod can still load (just inert) if SoS is missing.</p>
 */
@SuppressWarnings("removal") // EventBusSubscriber.Bus is deprecated but still functional in 1.21.1; matches the
                              // pattern used by the NeoForge MDK template's Config.java in this project.
@EventBusSubscriber(modid = CreatePolyphony.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class PolyphonyInteractionHandler {

    /**
     * Registry id of the SoS tracker bar block. Hard-coded because we don't
     * compile-time depend on Sound of Steam internals - we just match on
     * registry name.
     */
    public static final ResourceLocation TRACKER_BAR_ID =
        ResourceLocation.fromNamespaceAndPath("pipeorgans", "tracker_bar");

    private PolyphonyInteractionHandler() {}

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Player p = event.getEntity();
        if (!(p instanceof ServerPlayer player)) return;
        Level level = player.level();
        if (!(level instanceof ServerLevel sl)) return;

        BlockPos pos = event.getPos();
        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();

        ResourceLocation blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block);
        if (!TRACKER_BAR_ID.equals(blockId)) return;

        ItemStack held = event.getItemStack();
        if (!(held.getItem() instanceof InstrumentItem)) return;

        // Sneak-right-click = explicit unlink; otherwise (re)link.
        if (player.isShiftKeyDown()) {
            if (PolyphonyLinkManager.unlink(player)) {
                event.setCancellationResult(InteractionResult.SUCCESS);
                event.setCanceled(true);
            }
            return;
        }

        PolyphonyLink link = PolyphonyLinkManager.link(player, sl, pos, held);
        if (link != null) {
            // Cancel the vanilla GUI-open path so right-clicking with an instrument
            // doesn't also open the tracker bar's UI - linking is a distinct gesture.
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
        } else {
            // Defensive: held wasn't actually an InstrumentItem after all.
            CreatePolyphony.LOGGER.debug("Refused to link {} - held item is not an instrument",
                player.getName().getString());
        }

        // Sanity: also make sure the BE we're talking to actually exists.
        // (We don't strictly need to check this, but log if it's missing.)
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            CreatePolyphony.LOGGER.warn("Tracker bar at {} has no block entity - is SoS installed?", pos);
        }
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            PolyphonyLinkManager.onPlayerLogout(sp.getUUID());
        }
    }

}
