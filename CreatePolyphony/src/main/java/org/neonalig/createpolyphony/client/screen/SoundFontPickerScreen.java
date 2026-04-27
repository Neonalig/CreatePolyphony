package org.neonalig.createpolyphony.client.screen;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.neonalig.createpolyphony.client.PolyphonyClientNoteHandler;
import org.neonalig.createpolyphony.client.sound.SoundFontManager;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Simple screen for selecting the active .sf2 file from the local soundfonts directory.
 */
public final class SoundFontPickerScreen extends Screen {

    private static final Component TITLE = Component.translatable("screen.createpolyphony.soundfont.title");
    private static final Component NONE_LABEL = Component.translatable("screen.createpolyphony.soundfont.none");
    private static final Component LOADING_LABEL = Component.translatable("screen.createpolyphony.soundfont.loading");
    private static final String WARNING_PREFIX = "[!] ";

    private final SoundFontManager manager;
    private final Consumer<SoundFontManager> managerListener;

    private int listTop;
    private int listBottom;

    private SoundFontList list;
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

        this.listTop = 32;
        this.listBottom = this.height - 60;
        this.list = this.addRenderableWidget(new SoundFontList(this.minecraft, this.width, this.listBottom, this.listTop, 20));

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
        list.rebuild(manager.available());

        SoundFontList.Entry toSelect = null;
        for (SoundFontList.Entry entry : list.children()) {
            if (Objects.equals(entry.fileName, selectedName)) {
                toSelect = entry;
                break;
            }
        }
        if (toSelect == null) {
            String pending = manager.pending();
            for (SoundFontList.Entry entry : list.children()) {
                if (Objects.equals(entry.fileName, pending)) {
                    toSelect = entry;
                    break;
                }
            }
        }
        if (toSelect == null) {
            String active = manager.active();
            for (SoundFontList.Entry entry : list.children()) {
                if (Objects.equals(entry.fileName, active)) {
                    toSelect = entry;
                    break;
                }
            }
        }
        if (toSelect == null && !list.children().isEmpty()) {
            toSelect = list.children().get(0);
        }
        list.setSelected(toSelect);
        updateControlState();
    }

    private void updateControlState() {
        boolean loading = manager.isLoading();
        boolean hasSelection = list != null && list.getSelected() != null;
        setActiveButton.active = !loading && hasSelection;
        openFolderButton.active = !loading;
        refreshButton.active = !loading;
        panicButton.active = !loading;
        cancelLoadButton.active = loading;
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

        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);
        if (manager.isLoading()) {
            String pending = manager.pending();
            Component loadingText = pending == null
                ? LOADING_LABEL
                : Component.translatable("screen.createpolyphony.soundfont.loading_name", pending);
            guiGraphics.drawCenteredString(this.font, loadingText, this.width / 2, 24, 0xFFE37C);

            int barW = Math.min(240, this.width - 40);
            int barH = 8;
            int barX = (this.width - barW) / 2;
            int barY = 24 + this.font.lineHeight + 4;
            guiGraphics.fill(barX, barY, barX + barW, barY + barH, 0xFF2A2A2A);
            int fill = Math.max(1, Math.min(barW, Math.round(barW * manager.loadingProgress01())));
            guiGraphics.fill(barX + 1, barY + 1, barX + fill - 1, barY + barH - 1, 0xFF7CFF7C);
        }
        guiGraphics.drawString(this.font,
            Component.translatable("screen.createpolyphony.soundfont.folder", manager.directory().toAbsolutePath().toString()),
            10,
            this.height - 48,
            0xA0A0A0,
            false);

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private final class SoundFontList extends ObjectSelectionList<SoundFontList.Entry> {

        private SoundFontList(Minecraft minecraft, int width, int bottom, int top, int itemHeight) {
            super(minecraft, width, bottom, top, itemHeight);
        }

        private void rebuild(Iterable<String> fileNames) {
            clearEntries();
            addEntry(new Entry(null));
            for (String fileName : fileNames) {
                addEntry(new Entry(fileName));
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
                    SoundFontList.this.setSelected(this);
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



