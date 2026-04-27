package org.neonalig.createpolyphony.mixin;

import com.simibubi.create.content.kinetics.deployer.DeployerFakePlayer;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.neonalig.createpolyphony.Config;
import org.neonalig.createpolyphony.link.PolyphonyLinkManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * Continuous-powered deployer heartbeat: keep holder registration alive while powered,
 * and immediately unregister when speed/power drops so notes stop at once.
 */
@Mixin(targets = "com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity", remap = false)
public abstract class DeployerBlockEntityMixin {

    @Shadow protected DeployerFakePlayer player;
    @Shadow protected boolean redstoneLocked;

    @Inject(method = "tick()V", at = @At("HEAD"))
    private void createpolyphony$heartbeatContinuousMode(CallbackInfo ci) {
        if (Config.deployerPlaybackMode() != Config.DeployerPlaybackMode.CONTINUOUS_POWERED) return;

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

        float speed = ((KineticBlockEntity) (Object) this).getSpeed();
        if (this.redstoneLocked || Math.abs(speed) < 1.0e-6F) {
            PolyphonyLinkManager.unregisterAutomationHolder(serverLevel, holderId);
            return;
        }

        PolyphonyLinkManager.registerAutomationActivation(
            serverLevel,
            holderId,
            fakePlayer.getMainHandItem(),
            null,
            Vec3.atCenterOf(be.getBlockPos())
        );
    }
}


