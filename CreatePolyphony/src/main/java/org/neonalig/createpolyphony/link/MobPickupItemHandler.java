package org.neonalig.createpolyphony.link;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingEquipmentChangeEvent;
import org.jetbrains.annotations.Nullable;
import org.neonalig.createpolyphony.CreatePolyphony;
import org.neonalig.createpolyphony.instrument.InstrumentItem;

import java.util.UUID;

/**
 * Awards the jam-session advancement when a mob actually picks up an instrument
 * item entity thrown by a player.
 */
@SuppressWarnings("removal")
@EventBusSubscriber(modid = CreatePolyphony.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class MobPickupItemHandler {

    private static final String ROOT = CreatePolyphony.MODID;
    private static final String KEY_JAM_SESSION_THROWER = "jam_session_thrower";

    private MobPickupItemHandler() {
    }

    @SubscribeEvent
    public static void onItemToss(ItemTossEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }

        ItemStack tossed = event.getEntity().getItem();
        if (InstrumentItem.familyOf(tossed) == null) {
            return;
        }

        markJamSessionThrower(tossed, player.getUUID());
    }

    @SubscribeEvent
    public static void onLivingEquipmentChange(LivingEquipmentChangeEvent event) {
        if (!(event.getEntity() instanceof Mob mob)) {
            return;
        }

        if (event.getSlot() != EquipmentSlot.MAINHAND && event.getSlot() != EquipmentSlot.OFFHAND) {
            return;
        }

        ItemStack equipped = event.getTo();
        if (InstrumentItem.familyOf(equipped) == null) {
            return;
        }

        UUID throwerId = jamSessionThrower(equipped);
        if (throwerId == null) {
            return;
        }

        MinecraftServer server = mob.getServer();
        if (server == null) {
            return;
        }

        ServerPlayer owner = server.getPlayerList().getPlayer(throwerId);
        if (owner == null) {
            return;
        }

        if (ItemStack.matches(event.getFrom(), equipped)) {
            return;
        }

        PolyphonyAdvancementGrants.grantJamSession(owner);
        clearJamSessionThrower(equipped);
    }

    private static void markJamSessionThrower(ItemStack stack, UUID throwerId) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            CompoundTag root = tag.getCompound(ROOT);
            root.putUUID(KEY_JAM_SESSION_THROWER, throwerId);
            tag.put(ROOT, root);
        });
    }

    @Nullable
    private static UUID jamSessionThrower(ItemStack stack) {
        CompoundTag data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (!data.contains(ROOT)) {
            return null;
        }

        CompoundTag root = data.getCompound(ROOT);
        if (!root.hasUUID(KEY_JAM_SESSION_THROWER)) {
            return null;
        }

        return root.getUUID(KEY_JAM_SESSION_THROWER);
    }

    private static void clearJamSessionThrower(ItemStack stack) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            if (!tag.contains(ROOT)) {
                return;
            }

            CompoundTag root = tag.getCompound(ROOT);
            root.remove(KEY_JAM_SESSION_THROWER);
            if (root.isEmpty()) {
                tag.remove(ROOT);
            } else {
                tag.put(ROOT, root);
            }
        });
    }
}



