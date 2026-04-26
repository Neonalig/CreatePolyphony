package org.neonalig.createpolyphony.mixin;

import org.spongepowered.asm.mixin.Mixin;

/**
 * Mixin into Sound of Steam's {@code com.finchy.pipeorgans.content.midi.trackerBar.TrackerBarBlockEntity}.
 *
 * <p>The full implementation (an {@code @Inject} on {@code handleNote(ShortMessage)}
 * that dispatches the note to all {@code PolyphonyLinkManager}-linked players)
 * is added in the dispatch step of the build.</p>
 *
 * <p>This stub exists now so that:</p>
 * <ul>
 *   <li>{@code createpolyphony.mixins.json} validates at load time (the listed mixin class exists).</li>
 *   <li>The Mixin annotation processor produces a refmap, exercising the build wiring.</li>
 * </ul>
 *
 * <p>The target string is matched lazily (string-based) so the project still
 * compiles even if Sound of Steam isn't on the classpath at the moment of the
 * IDE indexing pass; Mixin will resolve it at runtime.</p>
 */
@Mixin(targets = "com.finchy.pipeorgans.content.midi.trackerBar.TrackerBarBlockEntity", remap = false)
public abstract class TrackerBarBlockEntityMixin {
    // Intentionally empty for now - real injectors are added in todo 6.
}
