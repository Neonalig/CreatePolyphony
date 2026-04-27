package org.neonalig.createpolyphony.client.sound;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;
import org.neonalig.createpolyphony.Config;
import org.neonalig.createpolyphony.CreatePolyphony;
import org.neonalig.createpolyphony.client.PolyphonyClientNoteHandler;
import org.neonalig.createpolyphony.synth.PolyphonySynthesizer;
import org.neonalig.createpolyphony.synth.SynthSettings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Single-source-of-truth for everything soundfont-related on the client:
 * which {@code .sf2} files are available, which one (if any) is currently
 * active, and the {@link PolyphonySynthesizer} backing the active choice.
 *
 * <h2>Filesystem layout</h2>
 * <pre>{@code
 *   <minecraft run dir>/
 *       soundfonts/
 *           MyGrand.sf2
 *           Strings.sf2
 *           selected.txt          <-- one-line file with the active filename, or empty for "None"
 * }</pre>
 * <p>The {@code soundfonts/} directory is created on first call so the
 * "Open Folder" button always has somewhere to point. The selection
 * sidecar is rewritten on every successful {@link #setActive(String)}
 * so the user's choice survives a Minecraft restart.</p>
 *
 * <h2>Synth ownership</h2>
 * <p>This manager owns exactly one {@link PolyphonySynthesizer} at a
 * time. Switching soundfonts:</p>
 * <ol>
 *   <li>Stops every currently-playing voice (via the synth).</li>
 *   <li>Loads the new {@code .sf2} into the same synth instance, so the
 *       audio stream piping into OpenAL doesn't have to be torn down
 *       and rebuilt - we keep the OpenAL source alive across swaps.</li>
 *   <li>Notifies any registered listeners so the GUI can update.</li>
 * </ol>
 * <p>Selecting "None" doesn't close the synth either - it just unloads
 * the current bank. The synth then renders silence until the user picks
 * another soundfont, which keeps swap latency negligible. The synth is
 * only fully closed on game shutdown.</p>
 *
 * <h2>Directory watcher</h2>
 * <p>A {@link WatchService} runs on a daemon thread, debouncing rapid
 * change bursts (file copies often fire 3-5 events back-to-back) into a
 * single {@link #rescan()} call. Listeners get notified after the
 * rescan so the GUI list updates without the user having to hit
 * "Refresh".</p>
 *
 * <h2>Threading contract</h2>
 * <ul>
 *   <li>{@link #available()} can be called from any thread; returns a
 *       snapshot copy.</li>
 *   <li>{@link #setActive(String)} should be called from the client main
 *       thread (it triggers OpenAL state changes).</li>
 *   <li>{@link #activeSynth()} is the network handler's hot path - lock
 *       free and uses a volatile field for visibility.</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public final class SoundFontManager {

    private static final String DIR_NAME = "soundfonts";
    private static final String SELECTION_FILE = "selected.txt";
    private static final String SF2_EXT = ".sf2";

    private static volatile SoundFontManager INSTANCE;

    /** Resolved {@code <run-dir>/soundfonts/} path. Lazily created. */
    private final Path directory;
    private final Path selectionFile;

    /** Reused across soundfont swaps when available; may be null if synth bootstrap failed. */
    @Nullable private final PolyphonySynthesizer synth;

    /** Currently-active filename inside {@link #directory}, or {@code null} for "None". */
    @Nullable private volatile String activeName;

    /** Most recent directory scan result, sorted alphabetically. Atomically replaced on rescan. */
    private volatile List<String> cachedListing = Collections.emptyList();

    /** Listeners notified when the available list or active selection changes. */
    private final List<Consumer<SoundFontManager>> listeners = new CopyOnWriteArrayList<>();

    private final WatchService watchService;
    private final Thread watchThread;
    private volatile boolean closed = false;

    private SoundFontManager(Path directory, @Nullable PolyphonySynthesizer synth) throws IOException {
        this.directory = directory;
        this.selectionFile = directory.resolve(SELECTION_FILE);
        this.synth = synth;
        this.watchService = directory.getFileSystem().newWatchService();
        directory.register(watchService,
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_DELETE,
            StandardWatchEventKinds.ENTRY_MODIFY);

        this.watchThread = new Thread(this::watchLoop, "CreatePolyphony-SoundFontWatcher");
        this.watchThread.setDaemon(true);
        this.watchThread.start();

        rescanInternal();
    }

    /**
     * Acquire (and lazily build) the singleton. The first call may take a few
     * milliseconds because it boots Gervill; subsequent calls are O(1).
     *
     * @return the manager, or {@code null} if construction failed (e.g. no
     *         filesystem access or no MIDI subsystem). Failure is logged.
     */
    @Nullable
    public static synchronized SoundFontManager get() {
        if (INSTANCE != null) return INSTANCE;
        try {
            Path dir = resolveSoundFontDir();
            Files.createDirectories(dir);
            PolyphonySynthesizer synth = null;
            try {
                SynthSettings settings = Config.synthSettings();
                synth = new PolyphonySynthesizer(settings);
            } catch (Throwable synthError) {
                CreatePolyphony.LOGGER.error(
                    "Sound synthesis backend unavailable; UI and soundfont selection will stay available but note playback is disabled until startup flags are fixed.",
                    synthError);
            }
            SoundFontManager mgr = new SoundFontManager(dir, synth);

            // Wire the note handler to query us for the synth.
            PolyphonyClientNoteHandler.setSynthSupplier(mgr::activeSynth);

            // Restore last selection, if any.
            mgr.loadSelectionFromDisk();

            INSTANCE = mgr;
            CreatePolyphony.LOGGER.info("SoundFontManager initialised at {}", dir);
            return mgr;
        } catch (Throwable t) {
            CreatePolyphony.LOGGER.error("Failed to initialise SoundFontManager", t);
            return null;
        }
    }

    /**
     * Resolve the soundfont directory under Minecraft's gameDir. Falls back
     * to the working dir if MC isn't initialised yet (defensive - shouldn't
     * happen on the client, but the test harness might).
     */
    private static Path resolveSoundFontDir() {
        try {
            return Minecraft.getInstance().gameDirectory.toPath().resolve(DIR_NAME);
        } catch (Throwable t) {
            return Path.of(".").resolve(DIR_NAME);
        }
    }

    public Path directory() { return directory; }

    /** Snapshot of {@code .sf2} filenames in the directory, alphabetically sorted. */
    public List<String> available() {
        return cachedListing;
    }

    /** {@code null} when "None" is selected. */
    @Nullable
    public String active() {
        return activeName;
    }

    /** The synth currently bound to the active soundfont, or {@code null} for "None". */
    @Nullable
    public PolyphonySynthesizer activeSynth() {
        return activeName == null ? null : synth;
    }

    public boolean synthesisAvailable() {
        return synth != null;
    }

    public void addListener(Consumer<SoundFontManager> listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    public void removeListener(Consumer<SoundFontManager> listener) {
        listeners.remove(listener);
    }

    /**
     * Set the active soundfont. Pass {@code null} to select "None / No Sound".
     *
     * @param fileName the filename relative to {@link #directory()}, or
     *                 {@code null} to deactivate.
     * @return {@code true} on success, {@code false} if the filename was
     *         non-null but absent from the cached listing or failed to load.
     */
    public boolean setActive(@Nullable String fileName) {
        if (fileName == null) {
            // "None" - retire any currently-loaded patches and stop playing voices.
            if (synth != null) synth.unloadSoundFont();
            activeName = null;
            persistSelection();
            notifyListeners();
            CreatePolyphony.LOGGER.info("SoundFont selection cleared (None)");
            return true;
        }

        // Don't let callers pass arbitrary paths - we only accept basenames inside our dir.
        if (fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
            CreatePolyphony.LOGGER.warn("Refusing suspicious soundfont path: {}", fileName);
            return false;
        }
        Path target = directory.resolve(fileName);
        if (!Files.isRegularFile(target) || !fileName.toLowerCase(Locale.ROOT).endsWith(SF2_EXT)) {
            CreatePolyphony.LOGGER.warn("Soundfont not found or not an .sf2: {}", target);
            return false;
        }
        try {
            if (synth != null) {
                synth.loadSoundFont(target.toFile());
            } else {
                CreatePolyphony.LOGGER.warn(
                    "Selected soundfont {}, but synth backend is unavailable so playback remains muted.",
                    fileName);
            }
            activeName = fileName;
            persistSelection();
            notifyListeners();
            return true;
        } catch (IOException ex) {
            CreatePolyphony.LOGGER.error("Failed to load soundfont {}", target, ex);
            return false;
        }
    }

    /** Force a directory rescan and notify listeners. Cheap (just a directory listing). */
    public void rescan() {
        rescanInternal();
        notifyListeners();
    }

    private void rescanInternal() {
        List<String> found = new ArrayList<>();
        try (Stream<Path> stream = Files.list(directory)) {
            stream.filter(Files::isRegularFile)
                .map(p -> p.getFileName().toString())
                .filter(name -> name.toLowerCase(Locale.ROOT).endsWith(SF2_EXT))
                .forEach(found::add);
        } catch (IOException ex) {
            CreatePolyphony.LOGGER.warn("Failed to list soundfont directory {}", directory, ex);
        }
        Collections.sort(found, String.CASE_INSENSITIVE_ORDER);
        cachedListing = Collections.unmodifiableList(found);

        // If our active selection was deleted underneath us, drop it.
        String cur = activeName;
        if (cur != null && !found.contains(cur)) {
            CreatePolyphony.LOGGER.info("Active soundfont {} was removed; reverting to None", cur);
            if (synth != null) synth.unloadSoundFont();
            activeName = null;
            persistSelection();
        }
    }

    private void notifyListeners() {
        for (Consumer<SoundFontManager> l : listeners) {
            try { l.accept(this); }
            catch (Throwable t) { CreatePolyphony.LOGGER.warn("SoundFont listener threw", t); }
        }
    }

    private void persistSelection() {
        try {
            String content = activeName == null ? "" : activeName;
            Files.writeString(selectionFile, content, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            CreatePolyphony.LOGGER.warn("Failed to persist soundfont selection", ex);
        }
    }

    private void loadSelectionFromDisk() {
        if (!Files.isRegularFile(selectionFile)) return;
        try {
            String stored = Files.readString(selectionFile, StandardCharsets.UTF_8).trim();
            if (stored.isEmpty()) return;
            if (cachedListing.contains(stored)) {
                // Best-effort restore. If load fails we silently fall back to "None".
                if (!setActive(stored)) {
                    CreatePolyphony.LOGGER.warn("Persisted soundfont {} failed to load; reverting to None", stored);
                }
            } else {
                CreatePolyphony.LOGGER.info("Persisted soundfont {} not present in directory", stored);
            }
        } catch (IOException ex) {
            CreatePolyphony.LOGGER.warn("Failed to read selection file", ex);
        }
    }

    /**
     * The watch loop: blocks on {@link WatchService#take()} and triggers a
     * debounced rescan whenever the directory changes. Debouncing is just a
     * brief sleep that absorbs rapid follow-up events from the same edit
     * (most file copy operations fire 3-5 events on Windows).
     */
    private void watchLoop() {
        while (!closed) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                if (!closed) CreatePolyphony.LOGGER.warn("Soundfont watcher aborted", t);
                return;
            }

            // Debounce: drain any further events that arrive in the next 150 ms before rescanning.
            try { Thread.sleep(150); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt(); return;
            }
            // Ignore the actual events - we always do a full rescan, which is cheap.
            key.pollEvents();
            key.reset();

            try {
                rescanInternal();
                notifyListeners();
            } catch (Throwable t) {
                CreatePolyphony.LOGGER.warn("Soundfont rescan failed", t);
            }
        }
    }

    /**
     * Tear everything down. Called on game shutdown. Idempotent.
     */
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try { watchService.close(); } catch (IOException ignored) { }
        try { watchThread.interrupt(); } catch (Throwable ignored) { }
        if (synth != null) {
            try { synth.close(); } catch (Throwable ignored) { }
        }
        if (INSTANCE == this) INSTANCE = null;
    }
}
