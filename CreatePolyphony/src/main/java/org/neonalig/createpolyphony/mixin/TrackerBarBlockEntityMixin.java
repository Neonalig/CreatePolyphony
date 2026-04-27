package org.neonalig.createpolyphony.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.neonalig.createpolyphony.link.PolyphonyLinkManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.sound.midi.ShortMessage;

/**
 * Mixin into Sound of Steam's
 * {@code com.finchy.pipeorgans.content.midi.trackerBar.TrackerBarBlockEntity}.
 *
 * <p>The target's {@code handleNote(ShortMessage)} is the single funnel point
 * where the SoS sequencer pumps every MIDI event during playback (and where
 * its own {@code MidiSourceBehaviour} forwards events to wireless redstone
 * "frequency" links). By injecting at the very start, we get every event the
 * tracker emits - NoteOn, NoteOff, ProgramChange - <em>before</em> SoS does
 * its own thing, so we don't interfere with vanilla SoS behaviour.</p>
 *
 * <p>We dispatch each event to {@link PolyphonyLinkManager}, which routes it
 * to whichever linked player is responsible for that channel. SoS continues
 * its own handling normally - the two systems run in parallel.</p>
 *
 * <p>Why string-targeted: this Mixin doesn't need any SoS classes on the
 * compile classpath. The {@code @Mixin(targets = ...)} string is resolved by
 * the Mixin transformer at load time. That keeps our hard dependency on SoS
 * limited to runtime, where it's enforced by {@code mods.toml}.</p>
 */
@Mixin(targets = "com.finchy.pipeorgans.content.midi.trackerBar.TrackerBarBlockEntity", remap = false)
public abstract class TrackerBarBlockEntityMixin {

    /**
     * Inject before SoS's own dispatch logic. The target signature in SoS is:
     * <pre>{@code public void handleNote(ShortMessage sm)}</pre>
     * We don't override the call - just observe.
     */
    @Inject(method = "handleNote(Ljavax/sound/midi/ShortMessage;)V", at = @At("HEAD"))
    private void createpolyphony$onHandleNote(ShortMessage sm, CallbackInfo ci) {
        // TrackerBarBlockEntity transitively extends net.minecraft.world.level.block.entity.BlockEntity
        // via Create's KineticBlockEntity -> SmartBlockEntity -> SyncedBlockEntity -> BlockEntity.
        // Cast through Object so this also compiles when the SoS class isn't on the IDE classpath.
        BlockEntity be = (BlockEntity) (Object) this;
        Level level = be.getLevel();
        if (!(level instanceof ServerLevel sl)) return;

        BlockPos pos = be.getBlockPos();
        int status   = sm.getStatus();   // includes channel nibble
        int data1    = sm.getData1();
        int data2    = sm.getData2();

        try {
            PolyphonyLinkManager.dispatchNote(sl, pos, status, data1, data2);
        } catch (Throwable t) {
            // Never let our hook break the tracker bar's own playback.
            // Log once and swallow.
            org.neonalig.createpolyphony.CreatePolyphony.LOGGER.error(
                "Polyphony dispatch failed for status={} data1={} data2={} at {}: {}",
                status, data1, data2, pos, t.toString());
        }
    }

}
