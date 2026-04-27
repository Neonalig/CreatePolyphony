package org.neonalig.createpolyphony.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import org.neonalig.createpolyphony.CreatePolyphony;
import org.neonalig.createpolyphony.instrument.InstrumentFamily;
import org.neonalig.createpolyphony.instrument.InstrumentItem;
import org.neonalig.createpolyphony.link.InstrumentLinkData;

/**
 * Client-only cosmetic rename for the One Man Band item.
 */
@SuppressWarnings("removal")
@EventBusSubscriber(modid = CreatePolyphony.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class OneManBandNameHandler {

    private OneManBandNameHandler() {}

    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        if (event.getToolTip().isEmpty()) return;
        if (InstrumentItem.familyOf(event.getItemStack()) != InstrumentFamily.ONE_MAN_BAND) return;

        String model = "default";
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            model = mc.player.getSkin().model().id();
        }

        String key = "item.createpolyphony.one_steve_band";
        if ("slim".equalsIgnoreCase(model)) {
            key = "item.createpolyphony.one_alex_band";
        }

        MutableComponent base = Component.translatable(key);
        InstrumentLinkData.LinkTarget target = InstrumentLinkData.target(event.getItemStack());
        MutableComponent displayName = target == null
            ? base
            : Component.translatable("item.createpolyphony.linked_name", base, target.coords());

        event.getToolTip().set(0, displayName.withStyle(event.getToolTip().get(0).getStyle()));
    }
}

