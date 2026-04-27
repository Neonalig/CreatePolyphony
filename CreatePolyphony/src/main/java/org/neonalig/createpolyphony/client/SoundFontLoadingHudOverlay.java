package org.neonalig.createpolyphony.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import org.neonalig.createpolyphony.CreatePolyphony;
import org.neonalig.createpolyphony.client.screen.SoundFontPickerScreen;
import org.neonalig.createpolyphony.client.sound.SoundFontManager;

/**
 * Renders a soundfont-loading progress bar as an in-game HUD overlay.
 *
 * <p>The overlay is suppressed when the {@link SoundFontPickerScreen} is
 * already open (because that screen draws its own bar). It appears near
 * the top-centre of the screen so it doesn't obscure gameplay UI.</p>
 */
@SuppressWarnings("removal")
@EventBusSubscriber(modid = CreatePolyphony.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class SoundFontLoadingHudOverlay {

    private SoundFontLoadingHudOverlay() {}

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();

        // Suppress while the picker screen is showing — it has its own bar.
        if (mc.screen instanceof SoundFontPickerScreen) return;

        SoundFontManager manager = SoundFontManager.get();
        if (manager == null || !manager.isLoading()) return;

        GuiGraphics guiGraphics = event.getGuiGraphics();

        int screenW = mc.getWindow().getGuiScaledWidth();

        // --- text label ---
        String pending = manager.pending();
        Component label = pending == null
            ? Component.translatable("hud.createpolyphony.soundfont.loading")
            : Component.translatable("screen.createpolyphony.soundfont.loading_name", pending);

        int textY = 6;
        guiGraphics.drawCenteredString(mc.font, label, screenW / 2, textY, 0xFFE37C);

        // --- progress bar ---
        int barW = Math.min(200, screenW - 40);
        int barH = 5;
        int barX = (screenW - barW) / 2;
        int barY = textY + mc.font.lineHeight + 3;

        // background
        guiGraphics.fill(barX,     barY,     barX + barW,     barY + barH,     0xAA1A1A1A);
        // fill
        int fill = Math.clamp(Math.round((barW - 2) * manager.loadingProgress01()), 2, barW - 2);
        guiGraphics.fill(barX + 1, barY + 1, barX + 1 + fill, barY + barH - 1, 0xFF7CFF7C);
    }
}




