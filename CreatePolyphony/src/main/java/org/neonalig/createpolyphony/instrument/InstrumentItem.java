package org.neonalig.createpolyphony.instrument;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.jetbrains.annotations.Nullable;
import org.neonalig.createpolyphony.link.InstrumentLinkData;

import java.util.List;

/**
 * A held musical instrument item.
 *
 * <p>The item itself is mostly metadata - the gameplay logic lives in
 * {@code PolyphonyLinkManager} (server-side channel distribution) and the
 * client-side note playback handler. Each item knows the
 * {@link InstrumentFamily} it represents so the distribution logic can
 * prefer it for matching MIDI channels.</p>
 *
 * <p>Stack size is 1 to mirror tools / instruments players typically wield.</p>
 */
public class InstrumentItem extends Item {

    private final InstrumentFamily family;

    public InstrumentItem(InstrumentFamily family, Properties properties) {
        super(properties.stacksTo(1));
        this.family = family;
    }

    public InstrumentFamily getFamily() {
        return family;
    }

    /**
     * Convenience: extract the family from a stack, or {@code null} if the
     * stack isn't an InstrumentItem at all.
     */
    @Nullable
    public static InstrumentFamily familyOf(ItemStack stack) {
        if (stack.getItem() instanceof InstrumentItem ii) return ii.getFamily();
        return null;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, ctx, tooltip, flag);
        tooltip.add(Component.translatable("tooltip.createpolyphony.instrument.hint")
            .withStyle(net.minecraft.ChatFormatting.GRAY));

        InstrumentLinkData.LinkTarget target = InstrumentLinkData.target(stack);
        if (target != null) {
            tooltip.add(Component.translatable("tooltip.createpolyphony.linked", target.coords())
                .withStyle(net.minecraft.ChatFormatting.AQUA));
        }
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return InstrumentLinkData.isLinked(stack) || super.isFoil(stack);
    }

    @Override
    public Component getName(ItemStack stack) {
        Component base = super.getName(stack);
        InstrumentLinkData.LinkTarget target = InstrumentLinkData.target(stack);
        if (target == null) return base;
        return Component.translatable("item.createpolyphony.linked_name", base, target.coords());
    }
}
