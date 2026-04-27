package org.neonalig.createpolyphony.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.neonalig.createpolyphony.CreatePolyphony;
import org.neonalig.createpolyphony.client.screen.SoundFontPickerScreen;
import org.neonalig.createpolyphony.client.sound.SoundFontManager;
import org.neonalig.createpolyphony.instrument.InstrumentItem;

/**
 * Client-only interaction hook for opening the soundfont picker screen.
 */
@SuppressWarnings("removal")
@EventBusSubscriber(modid = CreatePolyphony.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class SoundFontGuiInteractionHandler {

    private SoundFontGuiInteractionHandler() {}

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (shouldSkip(event.getEntity().isShiftKeyDown(), event.getItemStack().getItem() instanceof InstrumentItem, event.getHand(), event.getEntity().getMainHandItem().getItem() instanceof InstrumentItem)) {
            return;
        }
        if (openScreen()) {
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (shouldSkip(event.getEntity().isShiftKeyDown(), event.getItemStack().getItem() instanceof InstrumentItem, event.getHand(), event.getEntity().getMainHandItem().getItem() instanceof InstrumentItem)) {
            return;
        }
        if (openScreen()) {
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
        }
    }

    private static boolean shouldSkip(boolean sneaking, boolean holdingInstrument, InteractionHand hand, boolean mainHandIsInstrument) {
        if (!sneaking || !holdingInstrument) return true;
        // If both hands hold instruments, let main-hand interaction handle the open.
        return hand == InteractionHand.OFF_HAND && mainHandIsInstrument;
    }

    private static boolean openScreen() {
        SoundFontManager manager = SoundFontManager.get();
        if (manager == null) return false;
        Minecraft.getInstance().setScreen(new SoundFontPickerScreen(manager));
        return true;
    }
}


