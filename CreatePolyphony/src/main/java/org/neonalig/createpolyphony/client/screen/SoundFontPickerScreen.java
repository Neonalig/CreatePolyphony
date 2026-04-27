package org.neonalig.createpolyphony.client.screen;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.neonalig.createpolyphony.client.sound.SoundFontManager;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Simple screen for selecting the active .sf2 file from the local soundfonts directory.
 */
public final class SoundFontPickerScreen extends Screen {

    private static final Component TITLE = Component.translatable("screen.createpolyphony.soundfont.title");
    private static final Component NONE_LABEL = Component.translatable("screen.createpolyphony.soundfont.none");

    private final SoundFontManager manager;
    private final Consumer<SoundFontManager> managerListener;

    private SoundFontList list;
    private Button setActiveButton;

    public SoundFontPickerScreen(SoundFontManager manager) {
        super(TITLE);
        this.manager = Objects.requireNonNull(manager);
        this.managerListener = ignored -> Minecraft.getInstance().execute(this::reloadFromManager);
    }

    @Override
    protected void init() {
        super.init();

        int listTop = 32;
        int listBottom = this.height - 60;
        this.list = this.addRenderableWidget(new SoundFontList(this.minecraft, this.width, listBottom, listTop, 20));

        int buttonY = this.height - 28;
        int pad = 6;
        int buttonW = 90;
        int totalW = buttonW * 3 + pad * 2;
        int startX = (this.width - totalW) / 2;

        this.setActiveButton = this.addRenderableWidget(Button.builder(
                Component.translatable("screen.createpolyphony.soundfont.set_active"),
                b -> onSetActive())
            .bounds(startX, buttonY, buttonW, 20)
            .build());

        this.addRenderableWidget(Button.builder(
                Component.translatable("screen.createpolyphony.soundfont.open_folder"),
                b -> Util.getPlatform().openUri(manager.directory().toUri()))
            .bounds(startX + buttonW + pad, buttonY, buttonW, 20)
            .build());

        this.addRenderableWidget(Button.builder(
                Component.translatable("screen.createpolyphony.soundfont.refresh"),
                b -> manager.rescan())
            .bounds(startX + (buttonW + pad) * 2, buttonY, buttonW, 20)
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
        setActiveButton.active = toSelect != null;
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

        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);
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
            setActiveButton.active = entry != null;
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
                Component text = fileName == null ? NONE_LABEL : Component.literal(fileName);
                int color = isActive ? 0x7CFF7C : 0xFFFFFF;
                guiGraphics.drawString(SoundFontPickerScreen.this.font, text, left + 6, top + 6, color, false);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                if (button == 0) {
                    SoundFontList.this.setSelected(this);
                    return true;
                }
                return false;
            }

            @Override
            public Component getNarration() {
                return fileName == null ? NONE_LABEL : Component.literal(fileName);
            }
        }
    }
}



