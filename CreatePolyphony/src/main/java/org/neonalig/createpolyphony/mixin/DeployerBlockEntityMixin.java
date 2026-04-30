package org.neonalig.createpolyphony.mixin;

import com.simibubi.create.content.kinetics.deployer.DeployerFakePlayer;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.neonalig.createpolyphony.Config;
import org.neonalig.createpolyphony.instrument.InstrumentItem;
import org.neonalig.createpolyphony.link.PolyphonyLinkManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * Per-tick deployer state watcher.
 *
 * <p>Two responsibilities, both required for "instrument always stops the
 * moment a holder loses it" behavior:</p>
 * <ul>
 *   <li><b>CONTINUOUS_POWERED mode:</b> heartbeat-register the deployer every
 *       tick while it's powered so its tracker participation TTL never expires
 *       mid-song; unregister immediately when speed drops or it's redstone
 *       locked so notes stop with the kinetic feed.</li>
 *   <li><b>All modes:</b> if the deployer no longer holds a linked instrument
 *       (item taken out, swapped, dropped, etc.), unregister at once. In
 *       INTERACTION_ONLY mode this is the only path that catches "instrument
 *       removed" between activations - without it the holder waits for TTL
 *       expiry before any cleanup runs.</li>
 * </ul>
 */
@Mixin(targets = "com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity", remap = false)
public abstract class DeployerBlockEntityMixin {

    @Shadow protected DeployerFakePlayer player;
    @Shadow protected boolean redstoneLocked;

    @Inject(method = "tick()V", at = @At("HEAD"))
    private void createpolyphony$heartbeat(CallbackInfo ci) {
        BlockEntity be = (BlockEntity) (Object) this;
        Level level = be.getLevel();
        if (!(level instanceof ServerLevel serverLevel)) return;

        DeployerFakePlayer fakePlayer = this.player;
        if (fakePlayer == null) return;

        UUID ownerId = fakePlayer.getUUID();
        UUID holderId = new UUID(
            ownerId.getMostSignificantBits(),
            (long) System.identityHashCode(fakePlayer) & 0xFFFFFFFFL
        );

        ItemStack heldStack = fakePlayer.getMainHandItem();
        boolean hasInstrument = InstrumentItem.familyOf(heldStack) != null;

        // Universal stop-trigger: the deployer no longer holds a linked instrument.
        // Covers manual extraction, drop-on-floor, contraption disassembly handing the
        // item back, etc. Force-unregister so any in-flight notes are NoteOff'd this tick.
        if (!hasInstrument) {
            PolyphonyLinkManager.unregisterAutomationHolder(serverLevel, holderId);
            return;
        }

        if (Config.deployerPlaybackMode() != Config.DeployerPlaybackMode.CONTINUOUS_POWERED) {
            // INTERACTION_ONLY mode: registration is driven by DeployerHandler.activate;
            // we already handled the "lost instrument" case above, so nothing more to do.
            return;
        }

        float speed = ((KineticBlockEntity) (Object) this).getSpeed();
        if (this.redstoneLocked || Math.abs(speed) < 1.0e-6F) {
            PolyphonyLinkManager.unregisterAutomationHolder(serverLevel, holderId);
            return;
        }

        PolyphonyLinkManager.registerAutomationActivation(
            serverLevel,
            holderId,
            heldStack,
            null,
            Vec3.atCenterOf(be.getBlockPos())
        );
    }
}


