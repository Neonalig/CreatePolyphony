package org.neonalig.createpolyphony;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import org.neonalig.createpolyphony.registry.CPCreativeTabs;
import org.neonalig.createpolyphony.registry.CPItems;
import org.neonalig.createpolyphony.registry.CPRecipeSerializers;
import org.neonalig.createpolyphony.registry.CPSounds;
import org.slf4j.Logger;

/**
 * Mod entrypoint for <b>Create: Polyphony</b>.
 *
 * <p>Create: Polyphony extends Create: Sound of Steam by adding hand-held
 * instrument items. Players who interact with a Sound-of-Steam tracker bar
 * while holding an instrument become "linked" to that tracker; while linked,
 * the tracker's MIDI playback is distributed across them so each player's
 * held instrument plays the channels that best match it (with a sensible
 * round-robin fallback for unmatched channels).</p>
 *
 * <p>This class does very little itself - it just registers the deferred
 * registers for items and creative tabs. Sound, mixin, and link management
 * live in their own subpackages.</p>
 */
@Mod(CreatePolyphony.MODID)
public final class CreatePolyphony {

    public static final String MODID = "createpolyphony";
    public static final Logger LOGGER = LogUtils.getLogger();

    public CreatePolyphony(IEventBus modEventBus, ModContainer modContainer) {
        // Register deferred-register holders to the mod event bus so the
        // game knows about our items and creative tab.
        CPItems.register(modEventBus);
        CPSounds.register(modEventBus);
        CPCreativeTabs.register(modEventBus);
        CPRecipeSerializers.register(modEventBus);

        // Common setup hook (logging only for now).
        modEventBus.addListener(this::commonSetup);

        // Keep the legacy config registered so existing run/ data stays valid.
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("Create: Polyphony loading - {} instrument family items registered.",
            CPItems.BY_FAMILY.size());
    }
}
