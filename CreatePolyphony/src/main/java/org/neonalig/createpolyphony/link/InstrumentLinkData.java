package org.neonalig.createpolyphony.link;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import org.jetbrains.annotations.Nullable;
import org.neonalig.createpolyphony.CreatePolyphony;

/**
 * Stack-level NBT helpers for instrument link state.
 */
public final class InstrumentLinkData {

    private static final String ROOT = CreatePolyphony.MODID;
    private static final String KEY_LEVEL = "linked_level";
    private static final String KEY_X = "linked_x";
    private static final String KEY_Y = "linked_y";
    private static final String KEY_Z = "linked_z";

    private InstrumentLinkData() {}

    public static void setLinked(ItemStack stack, PolyphonyLinkManager.LinkKey key) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            CompoundTag root = tag.getCompound(ROOT);
            root.putString(KEY_LEVEL, key.levelPath());
            root.putInt(KEY_X, key.pos().getX());
            root.putInt(KEY_Y, key.pos().getY());
            root.putInt(KEY_Z, key.pos().getZ());
            tag.put(ROOT, root);
        });
    }

    public static void clearLinked(ItemStack stack) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            if (!tag.contains(ROOT)) return;
            CompoundTag root = tag.getCompound(ROOT);
            root.remove(KEY_LEVEL);
            root.remove(KEY_X);
            root.remove(KEY_Y);
            root.remove(KEY_Z);
            if (root.isEmpty()) {
                tag.remove(ROOT);
            } else {
                tag.put(ROOT, root);
            }
        });
    }

    public static boolean isLinked(ItemStack stack) {
        return target(stack) != null;
    }

    public static boolean matches(ItemStack stack, PolyphonyLinkManager.LinkKey key) {
        LinkTarget target = target(stack);
        if (target == null) return false;
        return target.levelPath().equals(key.levelPath()) && target.pos().equals(key.pos());
    }

    @Nullable
    public static LinkTarget target(ItemStack stack) {
        CompoundTag data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (!data.contains(ROOT)) return null;
        CompoundTag tag = data.getCompound(ROOT);
        if (!tag.contains(KEY_LEVEL) || !tag.contains(KEY_X) || !tag.contains(KEY_Y) || !tag.contains(KEY_Z)) {
            return null;
        }
        String levelPath = tag.getString(KEY_LEVEL);
        BlockPos pos = new BlockPos(tag.getInt(KEY_X), tag.getInt(KEY_Y), tag.getInt(KEY_Z));
        return new LinkTarget(levelPath, pos);
    }

    public record LinkTarget(String levelPath, BlockPos pos) {
        public String coords() {
            return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
        }
    }
}

