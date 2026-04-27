package org.neonalig.createpolyphony.client.screen;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.neonalig.createpolyphony.client.PolyphonyClientNoteHandler;
import org.neonalig.createpolyphony.client.sound.SoundFontManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Simple screen for selecting the active .sf2 file from the local soundfonts directory.
 */
public final class SoundFontPickerScreen extends Screen {

    private static final Component TITLE = Component.translatable("screen.createpolyphony.soundfont.title");
    private static final Component NONE_LABEL = Component.translatable("screen.createpolyphony.soundfont.none");
    private static final Component LOADING_LABEL = Component.translatable("screen.createpolyphony.soundfont.loading");
    private static final Component SEARCH_HINT = Component.translatable("screen.createpolyphony.soundfont.search");
    private static final String WARNING_PREFIX = "[!] ";

    /** Pixel height of the dark header panel (title + search bar). */
    private static final int HEADER_H = 54;
    /** Pixel height of the dark footer panel (loading info + buttons). */
    private static final int FOOTER_H = 90;

    private final SoundFontManager manager;
    private final Consumer<SoundFontManager> managerListener;

    private int listTop;
    private int listBottom;

    private SoundFontList list;
    private EditBox searchBox;
    private Button setActiveButton;
    private Button openFolderButton;
    private Button refreshButton;
    private Button panicButton;
    private Button cancelLoadButton;

    public SoundFontPickerScreen(SoundFontManager manager) {
        super(TITLE);
        this.manager = Objects.requireNonNull(manager);
        this.managerListener = ignored -> Minecraft.getInstance().execute(this::reloadFromManager);
    }

    @Override
    protected void init() {
        super.init();

        this.listTop = HEADER_H;
        this.listBottom = this.height - FOOTER_H;
        this.list = this.addRenderableWidget(new SoundFontList(this.minecraft, this.width, this.listBottom, this.listTop, 20));

        // Search bar in the header panel
        int searchW = Math.min(300, this.width - 20);
        this.searchBox = this.addRenderableWidget(new EditBox(
            this.font,
            (this.width - searchW) / 2, 28,
            searchW, 20,
            SEARCH_HINT));
        this.searchBox.setHint(SEARCH_HINT);
        this.searchBox.setMaxLength(64);
        this.searchBox.setResponder(text -> {
            if (list != null) list.setFilter(text);
            updateControlState();
        });

        int buttonY = this.height - 28;
        int pad = 6;
        int buttonW = 72;
        int totalW = buttonW * 5 + pad * 4;
        int startX = (this.width - totalW) / 2;

        this.setActiveButton = this.addRenderableWidget(Button.builder(
                Component.translatable("screen.createpolyphony.soundfont.set_active"),
                b -> onSetActive())
            .bounds(startX, buttonY, buttonW, 20)
            .build());

        this.openFolderButton = this.addRenderableWidget(Button.builder(
                Component.translatable("screen.createpolyphony.soundfont.open_folder"),
                b -> Util.getPlatform().openUri(manager.directory().toUri()))
            .bounds(startX + buttonW + pad, buttonY, buttonW, 20)
            .build());

        this.refreshButton = this.addRenderableWidget(Button.builder(
                Component.translatable("screen.createpolyphony.soundfont.refresh"),
                b -> manager.rescan())
            .bounds(startX + (buttonW + pad) * 2, buttonY, buttonW, 20)
            .build());

        this.panicButton = this.addRenderableWidget(Button.builder(
                Component.translatable("screen.createpolyphony.soundfont.panic"),
                b -> PolyphonyClientNoteHandler.panic())
            .bounds(startX + (buttonW + pad) * 3, buttonY, buttonW, 20)
            .build());

        this.cancelLoadButton = this.addRenderableWidget(Button.builder(
                Component.translatable("screen.createpolyphony.soundfont.cancel"),
                b -> manager.cancelLoadToNone())
            .bounds(startX + (buttonW + pad) * 4, buttonY, buttonW, 20)
            .build());

        manager.addListener(managerListener);
        reloadFromManager();
    }

    @Override
    public void removed() {
        manager.removeListener(managerListener);
        super.removed();
    }

    private void reloadFromManager() {
        if (list == null) return;
        @Nullable String selectedName = list.selectedName();
        String currentFilter = searchBox != null ? searchBox.getValue() : "";
        list.rebuildAll(manager.available(), currentFilter);

        SoundFontList.Entry toSelect = null;

        // Prioritize the currently active soundfont
        String active = manager.active();
        for (SoundFontList.Entry entry : list.children()) {
            if (Objects.equals(entry.fileName, active)) {
                toSelect = entry;
                break;
            }
        }

        // If no active match, try pending (loading)
        if (toSelect == null) {
            String pending = manager.pending();
            for (SoundFontList.Entry entry : list.children()) {
                if (Objects.equals(entry.fileName, pending)) {
                    toSelect = entry;
                    break;
                }
            }
        }

        // If no pending match, try previously selected
        if (toSelect == null) {
            for (SoundFontList.Entry entry : list.children()) {
                if (Objects.equals(entry.fileName, selectedName)) {
                    toSelect = entry;
                    break;
                }
            }
        }

        // If still nothing, select first entry
        if (toSelect == null && !list.children().isEmpty()) {
            toSelect = list.children().get(0);
        }
        list.setSelected(toSelect);
        updateControlState();
    }

    private void updateControlState() {
        boolean loading = manager.isLoading();
        boolean hasSelection = list != null && list.getSelected() != null;

        // Disable "Set Active" if already active or if the selected entry is the active soundfont
        boolean isAlreadyActive = false;
        if (hasSelection) {
            String selectedFileName = list.getSelected().fileName;
            isAlreadyActive = Objects.equals(selectedFileName, manager.active());
        }

        setActiveButton.active = !loading && hasSelection && !isAlreadyActive;
        openFolderButton.active = !loading;
        refreshButton.active = !loading;
        panicButton.active = !loading;
        cancelLoadButton.active = loading;
        if (searchBox != null) searchBox.active = !loading;
    }

    private void onSetActive() {
        SoundFontList.Entry selected = list.getSelected();
        if (selected == null) return;
        manager.setActive(selected.fileName);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        if (list != null) {
            list.render(guiGraphics, mouseX, mouseY, partialTick);
        }

        // Draw solid header/footer panels so title and buttons sit inside a panel,
        // not floating over the blurred world background.
        guiGraphics.fill(0, 0, this.width, this.listTop, 0xFF1A1A1A);
        guiGraphics.fill(0, this.listBottom, this.width, this.height, 0xFF1A1A1A);

        // Loading progress bar shown in the footer above the buttons
        if (manager.isLoading()) {
            String pending = manager.pending();
            Component loadingText = pending == null
                ? LOADING_LABEL
                : Component.translatable("screen.createpolyphony.soundfont.loading_name", pending);
            int loadY = this.listBottom + 4;
            guiGraphics.drawCenteredString(this.font, loadingText, this.width / 2, loadY, 0xFFE37C);

            int barW = Math.min(240, this.width - 40);
            int barH = 8;
            int barX = (this.width - barW) / 2;
            int barY = loadY + this.font.lineHeight + 4;
            guiGraphics.fill(barX, barY, barX + barW, barY + barH, 0xFF2A2A2A);
            int fill = Math.max(1, Math.min(barW, Math.round(barW * manager.loadingProgress01())));
            guiGraphics.fill(barX + 1, barY + 1, barX + fill - 1, barY + barH - 1, 0xFF7CFF7C);
        }

        super.render(guiGraphics, mouseX, mouseY, partialTick);

        // Draw title last so it appears on top of everything, not blurred by background
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 10, 0xFFFFFF);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private final class SoundFontList extends ObjectSelectionList<SoundFontList.Entry> {

        /** Full, unfiltered list of soundfont file names from the manager. */
        private final List<String> allFileNames = new ArrayList<>();
        private String currentFilter = "";

        private SoundFontList(Minecraft minecraft, int width, int bottom, int top, int itemHeight) {
            super(minecraft, width, bottom, top, itemHeight);
        }

        /** Replaces the stored file-name list and rebuilds visible entries with the given filter. */
        private void rebuildAll(Iterable<String> fileNames, String filter) {
            allFileNames.clear();
            for (String name : fileNames) allFileNames.add(name);
            currentFilter = filter == null ? "" : filter;
            applyFilter();
        }

        /** Re-filters the visible entries without re-fetching from the manager. Preserves selection. */
        void setFilter(String filter) {
            @Nullable String prevSelected = selectedName();
            currentFilter = filter == null ? "" : filter;
            applyFilter();
            // Reset scroll to top so filtered results are visible
            this.setScrollAmount(0.0);
            // Restore the previously selected entry if it is still visible
            for (SoundFontList.Entry entry : children()) {
                if (Objects.equals(entry.fileName, prevSelected)) {
                    setSelected(entry);
                    return;
                }
            }
        }

        private void applyFilter() {
            String lower = currentFilter.toLowerCase();
            clearEntries();
            addEntry(new Entry(null)); // "(none)" is always present
            for (String name : allFileNames) {
                if (lower.isEmpty() || name.toLowerCase().contains(lower)) {
                    addEntry(new Entry(name));
                }
            }
        }

        @Nullable
        private String selectedName() {
            Entry selected = getSelected();
            return selected == null ? null : selected.fileName;
        }

        @Override
        public void setSelected(@Nullable SoundFontList.Entry entry) {
            super.setSelected(entry);
            updateControlState();
        }

        private final class Entry extends ObjectSelectionList.Entry<SoundFontList.Entry> {
            @Nullable
            private final String fileName;
            /** Timestamp of the last left-click on this entry, used to detect double-clicks. */
            private long lastClickTime = Long.MIN_VALUE;

            private Entry(@Nullable String fileName) {
                this.fileName = fileName;
            }

            @Override
            public void render(GuiGraphics guiGraphics,
                               int index,
                               int top,
                               int left,
                               int width,
                               int height,
                               int mouseX,
                               int mouseY,
                               boolean hovering,
                               float partialTick) {
                String active = manager.active();
                boolean isActive = Objects.equals(fileName, active);
                String pending = manager.pending();
                boolean isPending = manager.isLoading() && Objects.equals(fileName, pending);
                boolean hasWarning = manager.hasSessionLoadFailure(fileName);
                Component text = fileName == null ? NONE_LABEL : Component.literal(fileName);
                int color = isActive ? 0x7CFF7C : (isPending ? 0xFFE37C : (hasWarning ? 0xFFD37C : 0xFFFFFF));
                int x = left + 6;
                if (hasWarning) {
                    guiGraphics.drawString(SoundFontPickerScreen.this.font, WARNING_PREFIX, x, top + 6, 0xFFE37C, false);
                    x += SoundFontPickerScreen.this.font.width(WARNING_PREFIX);
                }
                guiGraphics.drawString(SoundFontPickerScreen.this.font, text, x, top + 6, color, false);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                if (manager.isLoading()) {
                    return false;
                }
                if (button == 0) {
                    long now = Util.getMillis();
                    boolean isDoubleClick = SoundFontList.this.getSelected() == this
                        && now - lastClickTime < 500L;
                    lastClickTime = now;
                    SoundFontList.this.setSelected(this);
                    if (isDoubleClick) {
                        onSetActive();
                    }
                    return true;
                }
                return false;
            }

            @Override
            public Component getNarration() {
                if (fileName == null) return NONE_LABEL;
                if (manager.hasSessionLoadFailure(fileName)) {
                    return Component.translatable("screen.createpolyphony.soundfont.warning_narration", fileName);
                }
                return Component.literal(fileName);
            }
        }
    }
}

