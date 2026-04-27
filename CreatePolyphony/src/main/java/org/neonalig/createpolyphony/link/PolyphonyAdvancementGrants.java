package org.neonalig.createpolyphony.link;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.neonalig.createpolyphony.CreatePolyphony;
import org.neonalig.createpolyphony.instrument.InstrumentFamily;
import org.neonalig.createpolyphony.instrument.InstrumentItem;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Centralized helpers to grant Create: Polyphony advancements from server-side events.
 */
public final class PolyphonyAdvancementGrants {

    private static final ResourceLocation ROOT = id("polyphony/root");

    private static final ResourceLocation PIANO = id("polyphony/piano");
    private static final ResourceLocation ACOUSTIC_GUITAR = id("polyphony/acoustic_guitar");
    private static final ResourceLocation ELECTRIC_GUITAR = id("polyphony/electric_guitar");
    private static final ResourceLocation BASS_GUITAR = id("polyphony/bass_guitar");
    private static final ResourceLocation VIOLIN = id("polyphony/violin");
    private static final ResourceLocation TRUMPET = id("polyphony/trumpet");
    private static final ResourceLocation FLUTE = id("polyphony/flute");
    private static final ResourceLocation ACCORDION = id("polyphony/accordion");
    private static final ResourceLocation DRUM_KIT = id("polyphony/drum_kit");

    private static final ResourceLocation LINKED_UP = id("polyphony/linked_up");
    private static final ResourceLocation JAM_SESSION = id("polyphony/jam_session");
    private static final ResourceLocation DEPLOYER_ENCORE = id("polyphony/deployer_encore");
    private static final ResourceLocation ONE_MAN_BAND_FINALE = id("polyphony/one_man_band_finale");

    private static final Map<InstrumentFamily, ResourceLocation> INSTRUMENT_ADVANCEMENTS = new EnumMap<>(InstrumentFamily.class);

    static {
        INSTRUMENT_ADVANCEMENTS.put(InstrumentFamily.PIANO, PIANO);
        INSTRUMENT_ADVANCEMENTS.put(InstrumentFamily.ACOUSTIC_GUITAR, ACOUSTIC_GUITAR);
        INSTRUMENT_ADVANCEMENTS.put(InstrumentFamily.ELECTRIC_GUITAR, ELECTRIC_GUITAR);
        INSTRUMENT_ADVANCEMENTS.put(InstrumentFamily.BASS_GUITAR, BASS_GUITAR);
        INSTRUMENT_ADVANCEMENTS.put(InstrumentFamily.VIOLIN, VIOLIN);
        INSTRUMENT_ADVANCEMENTS.put(InstrumentFamily.TRUMPET, TRUMPET);
        INSTRUMENT_ADVANCEMENTS.put(InstrumentFamily.FLUTE, FLUTE);
        INSTRUMENT_ADVANCEMENTS.put(InstrumentFamily.ACCORDION, ACCORDION);
        INSTRUMENT_ADVANCEMENTS.put(InstrumentFamily.DRUM_KIT, DRUM_KIT);
    }

    private PolyphonyAdvancementGrants() {
    }

    public static void grantForHeldInstrument(ServerPlayer player, ItemStack stack) {
        grantRoot(player);

        InstrumentFamily family = InstrumentItem.familyOf(stack);
        if (family == null || family.isWildcard()) {
            tryGrantFinale(player);
            return;
        }

        ResourceLocation instrumentAdv = INSTRUMENT_ADVANCEMENTS.get(family);
        if (instrumentAdv != null) {
            award(player, instrumentAdv);
        }
        tryGrantFinale(player);
    }

    public static void grantLinkedUp(ServerPlayer player) {
        grantRoot(player);
        award(player, LINKED_UP);
        tryGrantFinale(player);
    }

    public static void grantJamSession(ServerPlayer player) {
        grantRoot(player);
        award(player, JAM_SESSION);
        tryGrantFinale(player);
    }

    public static void grantDeployerEncore(ServerPlayer player) {
        grantRoot(player);
        award(player, DEPLOYER_ENCORE);
        tryGrantFinale(player);
    }

    private static void grantRoot(ServerPlayer player) {
        award(player, ROOT);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(CreatePolyphony.MODID, path);
    }

    private static void award(ServerPlayer player, ResourceLocation id) {
        if (player.getServer() == null) return;

        AdvancementHolder advancement = player.getServer().getAdvancements().get(id);
        if (advancement == null) return;

        PlayerAdvancements playerAdvancements = player.getAdvancements();
        var progress = playerAdvancements.getOrStartProgress(advancement);
        if (progress.isDone()) return;

        List<String> remaining = new ArrayList<>();
        for (String criterion : progress.getRemainingCriteria()) {
            remaining.add(criterion);
        }
        for (String criterion : remaining) {
            playerAdvancements.award(advancement, criterion);
        }
    }

    private static boolean isDone(ServerPlayer player, ResourceLocation id) {
        if (player.getServer() == null) return false;
        AdvancementHolder advancement = player.getServer().getAdvancements().get(id);
        if (advancement == null) return false;
        return player.getAdvancements().getOrStartProgress(advancement).isDone();
    }

    private static void tryGrantFinale(ServerPlayer player) {
        if (!isDone(player, PIANO)
            || !isDone(player, ACOUSTIC_GUITAR)
            || !isDone(player, ELECTRIC_GUITAR)
            || !isDone(player, BASS_GUITAR)
            || !isDone(player, VIOLIN)
            || !isDone(player, TRUMPET)
            || !isDone(player, FLUTE)
            || !isDone(player, ACCORDION)
            || !isDone(player, DRUM_KIT)
            || !isDone(player, LINKED_UP)
            || !isDone(player, JAM_SESSION)
            || !isDone(player, DEPLOYER_ENCORE)) {
            return;
        }

        ItemStack oneManBand = new ItemStack(org.neonalig.createpolyphony.registry.CPItems.BY_FAMILY
            .get(InstrumentFamily.ONE_MAN_BAND)
            .get());
        if (!player.getInventory().contains(oneManBand)) {
            return;
        }

        award(player, ONE_MAN_BAND_FINALE);
    }
}

