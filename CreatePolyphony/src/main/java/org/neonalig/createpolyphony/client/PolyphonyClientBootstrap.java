package org.neonalig.createpolyphony.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import org.neonalig.createpolyphony.instrument.InstrumentItem;

import net.minecraft.client.Minecraft;

/**
 * Client-only bootstrap hooks used by the common mod entrypoint.
 */
@OnlyIn(Dist.CLIENT)
public final class PolyphonyClientBootstrap {

    private PolyphonyClientBootstrap() {}

    public static void registerConfigScreen(ModContainer modContainer) {
        InstrumentItem.setOneManBandNameKeySupplier(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && "slim".equalsIgnoreCase(mc.player.getSkin().model().id())) {
                return "item.createpolyphony.one_alex_band";
            }
            return "item.createpolyphony.one_steve_band";
        });
        modContainer.registerExtensionPoint(IConfigScreenFactory.class,
            (mc, parent) -> new ConfigurationScreen(modContainer, parent));
    }
}

