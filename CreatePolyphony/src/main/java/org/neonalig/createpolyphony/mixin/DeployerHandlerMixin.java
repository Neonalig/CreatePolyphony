package org.neonalig.createpolyphony.mixin;

import com.simibubi.create.content.kinetics.deployer.DeployerFakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.neonalig.createpolyphony.link.PolyphonyLinkManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * Hooks Create deployer activations (stationary and contraption) so automation
 * holders can participate in tracker link assignment.
 */
@Mixin(targets = "com.simibubi.create.content.kinetics.deployer.DeployerHandler", remap = false)
public abstract class DeployerHandlerMixin {

    @Inject(
        method = "activate(Lcom/simibubi/create/content/kinetics/deployer/DeployerFakePlayer;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/Vec3;Lcom/simibubi/create/content/kinetics/deployer/DeployerBlockEntity$Mode;)V",
        at = @At("TAIL")
    )
    private static void createpolyphony$onActivate(DeployerFakePlayer fakePlayer,
                                                    Vec3 center,
                                                    BlockPos targetPos,
                                                    Vec3 movementVector,
                                                    @Coerce Object mode,
                                                    CallbackInfo ci) {
        if (!(fakePlayer.level() instanceof ServerLevel level)) return;

        // DeployerFakePlayer.getUUID() returns the *owner player's* UUID, which is shared
        // by every deployer they placed.  If two deployers are on the same contraption their
        // shared UUID causes each syncHolderLinks call to overwrite the other's registration,
        // so only one instrument ever plays at a time.
        //
        // Fix: build a per-deployer-instance UUID by combining the owner UUID's top half
        // with the Java identity hash of this specific fake player object.
        // Each DeployerBlockEntity holds a single fake player instance (lazy-created once),
        // so System.identityHashCode is stable for the lifetime of the deployer block entity.
        UUID ownerId = fakePlayer.getUUID();
        UUID holderId = new UUID(
            ownerId.getMostSignificantBits(),
            (long) System.identityHashCode(fakePlayer) & 0xFFFFFFFFL
        );

        PolyphonyLinkManager.registerAutomationActivation(
            level,
            holderId,
            fakePlayer.getMainHandItem(),
            targetPos,
            center
        );
    }
}


