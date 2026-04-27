package org.neonalig.createpolyphony.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * Client-only bootstrap hooks used by the common mod entrypoint.
 */
@OnlyIn(Dist.CLIENT)
public final class PolyphonyClientBootstrap {

    private PolyphonyClientBootstrap() {}

    public static void registerConfigScreen(ModContainer modContainer) {
        modContainer.registerExtensionPoint(IConfigScreenFactory.class,
            (mc, parent) -> new ConfigurationScreen(modContainer, parent));
    }
}

