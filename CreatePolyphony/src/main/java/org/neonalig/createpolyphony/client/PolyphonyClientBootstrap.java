package org.neonalig.createpolyphony.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import org.neonalig.createpolyphony.CreatePolyphony;
import org.neonalig.createpolyphony.instrument.InstrumentItem;

import net.minecraft.client.Minecraft;

/**
 * Client-only bootstrap hooks: wired via {@link EventBusSubscriber} so the
 * common mod entrypoint no longer needs a reflection guard to reference
 * client-side classes.
 */
@OnlyIn(Dist.CLIENT)
@SuppressWarnings({"removal", "unused"}) // EventBusSubscriber.Bus deprecation: see CPNetwork for context.
@EventBusSubscriber(modid = CreatePolyphony.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class PolyphonyClientBootstrap {

    private PolyphonyClientBootstrap() {}

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        InstrumentItem.setOneManBandNameKeySupplier(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && "slim".equalsIgnoreCase(mc.player.getSkin().model().id())) {
                return "item.createpolyphony.one_alex_band";
            }
            return "item.createpolyphony.one_steve_band";
        });
        ModList.get().getModContainerById(CreatePolyphony.MODID)
            .ifPresent(container -> container.registerExtensionPoint(IConfigScreenFactory.class,
                (minecraft, parent) -> new ConfigurationScreen(container, parent)));
    }
}
