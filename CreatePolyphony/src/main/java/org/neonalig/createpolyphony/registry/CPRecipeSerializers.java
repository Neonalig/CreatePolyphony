package org.neonalig.createpolyphony.registry;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.neonalig.createpolyphony.CreatePolyphony;
import org.neonalig.createpolyphony.recipe.UnlinkInstrumentRecipe;

public final class CPRecipeSerializers {

    public static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS =
        DeferredRegister.create(BuiltInRegistries.RECIPE_SERIALIZER, CreatePolyphony.MODID);

    public static final DeferredHolder<RecipeSerializer<?>, SimpleCraftingRecipeSerializer<UnlinkInstrumentRecipe>> UNLINK_INSTRUMENT =
        RECIPE_SERIALIZERS.register("unlink_instrument",
            () -> new SimpleCraftingRecipeSerializer<>(UnlinkInstrumentRecipe::new));

    public static void register(IEventBus modEventBus) {
        RECIPE_SERIALIZERS.register(modEventBus);
    }

    private CPRecipeSerializers() {}
}
