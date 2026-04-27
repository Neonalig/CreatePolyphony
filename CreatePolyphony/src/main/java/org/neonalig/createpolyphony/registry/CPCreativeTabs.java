package org.neonalig.createpolyphony.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.neonalig.createpolyphony.CreatePolyphony;
import org.neonalig.createpolyphony.instrument.InstrumentFamily;

/**
 * Registers the "Create: Polyphony" creative tab containing every
 * {@link InstrumentFamily} item.
 */
public final class CPCreativeTabs {

    public static final DeferredRegister<CreativeModeTab> TABS =
        DeferredRegister.create(Registries.CREATIVE_MODE_TAB, CreatePolyphony.MODID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> POLYPHONY_TAB =
        TABS.register("polyphony", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup." + CreatePolyphony.MODID + ".polyphony"))
            .withTabsBefore(CreativeModeTabs.COMBAT)
            // Use the piano as the tab icon; it's the most universally recognisable instrument.
            .icon(() -> CPItems.BY_FAMILY.get(InstrumentFamily.ACCORDION).get().getDefaultInstance())
            .displayItems((params, output) -> {
                for (var holder : CPItems.all()) {
                    output.accept(holder.get());
                }
            })
            .build());

    public static void register(IEventBus modEventBus) {
        TABS.register(modEventBus);
    }

    private CPCreativeTabs() {
        // utility class
    }
}
