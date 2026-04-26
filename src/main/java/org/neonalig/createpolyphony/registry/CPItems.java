package org.neonalig.createpolyphony.registry;

import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.neonalig.createpolyphony.CreatePolyphony;
import org.neonalig.createpolyphony.instrument.InstrumentFamily;
import org.neonalig.createpolyphony.instrument.InstrumentItem;

import java.util.EnumMap;
import java.util.Map;

/**
 * Registers one {@link InstrumentItem} per {@link InstrumentFamily}.
 *
 * <p>Keeping the registration logic out of the main mod class makes the boot
 * sequence easier to follow and lets us iterate {@link #all()} from other
 * places (e.g. the creative tab and the JEI/Polyphony display sources).</p>
 */
public final class CPItems {

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(CreatePolyphony.MODID);

    /** Lookup table: family -&gt; registered DeferredItem holder. */
    public static final Map<InstrumentFamily, DeferredItem<InstrumentItem>> BY_FAMILY = new EnumMap<>(InstrumentFamily.class);

    static {
        // Static init so the holders are populated before mod class registers ITEMS to the bus.
        for (InstrumentFamily family : InstrumentFamily.values()) {
            BY_FAMILY.put(family, ITEMS.register(
                family.getId(),
                () -> new InstrumentItem(family, new Item.Properties())
            ));
        }
    }

    /** All registered instrument item holders, in declaration order. */
    public static Iterable<DeferredItem<InstrumentItem>> all() {
        return BY_FAMILY.values();
    }

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
    }

    private CPItems() {
        // utility class
    }
}
