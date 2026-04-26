package org.neonalig.createpolyphony.recipe;

import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;
import org.neonalig.createpolyphony.instrument.InstrumentItem;
import org.neonalig.createpolyphony.link.InstrumentLinkData;
import org.neonalig.createpolyphony.registry.CPRecipeSerializers;

/**
 * One linked instrument in the crafting grid -> same instrument with link data removed.
 */
public final class UnlinkInstrumentRecipe extends CustomRecipe {

    public UnlinkInstrumentRecipe(CraftingBookCategory category) {
        super(category);
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        return findOnlyStack(input) != null;
    }

    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        ItemStack source = findOnlyStack(input);
        if (source == null) return ItemStack.EMPTY;

        ItemStack output = source.copyWithCount(1);
        InstrumentLinkData.clearLinked(output);
        return output;
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width * height >= 1;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return CPRecipeSerializers.UNLINK_INSTRUMENT.get();
    }

    private static ItemStack findOnlyStack(CraftingInput input) {
        ItemStack found = ItemStack.EMPTY;
        for (int i = 0; i < input.size(); i++) {
            ItemStack stack = input.getItem(i);
            if (stack.isEmpty()) continue;

            if (!found.isEmpty()) {
                return null;
            }
            if (!(stack.getItem() instanceof InstrumentItem)) {
                return null;
            }
            if (!InstrumentLinkData.isLinked(stack)) {
                return null;
            }
            found = stack;
        }
        return found.isEmpty() ? null : found;
    }
}

