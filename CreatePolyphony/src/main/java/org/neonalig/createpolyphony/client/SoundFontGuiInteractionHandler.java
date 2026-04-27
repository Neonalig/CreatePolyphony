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
        if (event.getHand() == InteractionHand.OFF_HAND
            && event.getEntity().getMainHandItem().getItem() instanceof InstrumentItem) {
            return;
        }
        if (!event.getEntity().isShiftKeyDown()) return;
        if (!(event.getItemStack().getItem() instanceof InstrumentItem)) return;

        Minecraft mc = Minecraft.getInstance();
        SoundFontManager manager = SoundFontManager.get();
        if (manager == null) return;

        mc.setScreen(new SoundFontPickerScreen(manager));
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }
}


