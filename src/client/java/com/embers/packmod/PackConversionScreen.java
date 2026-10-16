package com.embers.packmod;

import com.embers.packmod.mixin.PackSelectionRefresh;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A single scrollable checklist for a complete detection/conversion batch. */
final class PackConversionScreen extends FadingScreen {
    private enum Phase { CHECKING, SELECTING, CONVERTING, FINISHED }
    private record Row(Path path, PackConversionManager.Candidate candidate, Component detail, boolean eligible, boolean error, boolean warning, String tooltip) {}
    private Screen parent;
    private final List<Row> rows = new ArrayList<>();
    private final Set<Path> selected = new HashSet<>();
    private final java.util.Map<Path,Path> reportJobs = new java.util.HashMap<>();
    private Phase phase = Phase.CHECKING;
    private Component status = Component.translatable("packmod.pack_conversion.checking");
    private NativeButton convertButton;
    private NativeButton previewButton;
    private int scroll;
    private boolean hasOutput;
    private Component taskStatus=Component.empty();

    PackConversionScreen(Screen parent) {
        super(Component.translatable("packmod.pack_conversion.title")); this.parent = parent;
    }
    void reparent(Screen next) { parent=next; }
    Screen parentScreen(){return parent;}
    void retryStarted(){phase=Phase.CONVERTING;status=Component.translatable("packmod.pack_conversion.preparing");rebuild();}
    private int pw() { return Math.min(520, width - 16); }
    private int ph() { return Math.min(phase == Phase.SELECTING && rows.isEmpty() ? 200 : 348, height - 16); }
    private int left() { return (width - pw()) / 2; }
    private int top() { return (height - ph()) / 2; }
    private int listTop() { return top() + 91; }
    private int visibleRows() { return Math.max(1, (ph() - 170) / 34); }
    private void rebuild() { if (minecraft != null && minecraft.gui.screen() == this) { clearWidgets(); init(); } }
    @Override protected void init() {
        int x = left(), y = top(), bottom = y + ph() - 30, half = (pw() - 30) / 2, quarter=(pw()-44)/4;
        scroll = Math.clamp(scroll, 0, Math.max(0, rows.size() - visibleRows()));
        for (int index = scroll; index < Math.min(rows.size(), scroll + visibleRows()); index++)
            addRenderableWidget(new PackRow(x + 13, listTop() + (index - scroll) * 34, pw() - 35, rows.get(index)));
        convertButton = null;
        previewButton = null;
        if (phase == Phase.CONVERTING) {
            addRenderableWidget(new NativeButton(x + 13, bottom, half, 20, "Continue in Background", b -> onClose()));
            addRenderableWidget(new NativeButton(x + 19 + half, bottom, half, 20, "Cancel Conversion", b -> { PackConversionManager.cancel(); b.active=false; status=Component.translatable("packmod.pack_conversion.cancelling"); }));
        } else if (phase == Phase.SELECTING && rows.stream().anyMatch(Row::eligible)) {
            addRenderableWidget(new NativeButton(x + 13, bottom - 29, quarter, 18, "Select All", b -> {
                rows.stream().filter(Row::eligible).map(Row::path).forEach(selected::add); updateConvertButton();
            }));
            addRenderableWidget(new NativeButton(x + 19 + quarter, bottom - 29, quarter, 18, "Clear Selection", b -> { selected.clear(); updateConvertButton(); }));
            addRenderableWidget(new NativeButton(x + 25 + quarter*2, bottom - 29, quarter, 18, "Conversion Settings", b -> minecraft.gui.setScreen(new ConversionOptionsScreen(this))));
            previewButton=addRenderableWidget(new NativeButton(x+31+quarter*3,bottom-29,quarter,18,"Preview Conversion",b->minecraft.gui.setScreen(new ConversionPlanScreen(this,
                    rows.stream().filter(Row::eligible).filter(row->selected.contains(row.path)).map(Row::candidate).toList()))));
            previewButton.active=!selected.isEmpty();
            convertButton = addRenderableWidget(new NativeButton(x + 13, bottom, half, 20, "Convert Selected", b -> start()));
            updateConvertButton();
            addRenderableWidget(new NativeButton(x + 19 + half, bottom, half, 20, "Cancel", b -> onClose()));
        } else if (phase == Phase.FINISHED) {
            int third = (pw() - 38) / 3;
            var folder = addRenderableWidget(new NativeButton(x + 13, bottom, third, 20, "Open Pack Folder", b -> Util.getPlatform().openPath(PackConversionManager.packDirectory())));
            folder.active = hasOutput;
            addRenderableWidget(new NativeButton(x+13,bottom-29,half,20,"Conversion History",b->minecraft.gui.setScreen(new PackHistoryScreen(this))));
            addRenderableWidget(new NativeButton(x+19+half,bottom-29,half,20,"Conversion Settings",b->minecraft.gui.setScreen(new ConversionOptionsScreen(this))));
            addRenderableWidget(new NativeButton(x + 19 + third, bottom, third, 20, "Open Conversion Reports", b -> Util.getPlatform().openPath(PackConversionManager.reportDirectory())));
            addRenderableWidget(new NativeButton(x + 25 + third * 2, bottom, third, 20, "Done", b -> onClose()));
        } else {
            if (phase != Phase.CHECKING)
                addRenderableWidget(new NativeButton(x+13,bottom-29,pw()-26,20,"Conversion Settings",b->minecraft.gui.setScreen(new ConversionOptionsScreen(this))));
            addRenderableWidget(new NativeButton(x + 13, bottom, pw() - 26, 20, phase == Phase.CHECKING ? "Cancel" : "Done", b -> onClose()));
        }
    }

    private void updateConvertButton() {
        if (convertButton == null) return;
        if (previewButton != null) previewButton.active = !selected.isEmpty();
        convertButton.setMessage(Component.translatable("packmod.pack_conversion.convert_selected", selected.size()));
        boolean runner;
        try { runner=NativePlatform.current().bundled(); }catch(Exception error){runner=false;}
        convertButton.active = !selected.isEmpty() && !PackConversionManager.busy() && runner;
        if(!runner) convertButton.setTooltip(Tooltip.create(Component.translatable("packmod.pack_conversion.runner_missing")));
    }
    private void start() {
        if (phase != Phase.SELECTING || selected.isEmpty() || PackConversionManager.busy()) return;
        Set<String> protectedIds = PackConversionManager.protectedIds(parent);
        List<PackConversionManager.Candidate> batch = rows.stream().filter(Row::eligible)
                .filter(row -> selected.contains(row.path) && !PackConversionManager.inUse(row.candidate, protectedIds))
                .map(Row::candidate).toList();
        if (batch.isEmpty()) { selected.clear(); updateConvertButton(); return; }
        phase = Phase.CONVERTING;
        status = Component.translatable("packmod.pack_conversion.preparing");
        rebuild(); PackConversionManager.convert(batch, this, parent);
    }

    void selectOnly(Path path) {
        if (path == null) return;
        selected.clear();
        for (int i=0; i<rows.size(); i++) {
            Row row=rows.get(i);
            if (row.eligible && row.path.equals(path)) {
                selected.add(path);
                scroll=Math.min(i, Math.max(0, rows.size()-visibleRows()));
                break;
            }
        }
        rebuild();
    }

    void detected(PackConversionManager.Report report, Set<String> protectedIds) {
        rows.clear(); selected.clear(); reportJobs.clear(); scroll = 0; phase = Phase.SELECTING;
        for (var candidate : report.incompatible()) {
            boolean enabled = PackConversionManager.inUse(candidate, protectedIds);
            boolean warning = !candidate.info().warnings().isEmpty();
            Component detail = Component.translatable(enabled ? "packmod.pack_conversion.enabled"
                    : warning ? "packmod.pack_conversion.available_warning" : "packmod.pack_conversion.available", candidate.info().range());
            String tooltip = detail.getString();
            for (String code : candidate.info().warnings()) tooltip += "\n" + warningText(new ConversionReport.Warning(code, ""));
            rows.add(new Row(candidate.info().path(), candidate, detail, !enabled, false, warning, tooltip));
        }
        for (var problem : report.problems()) rows.add(new Row(problem.path(), null,
                Component.translatable("packmod.pack_conversion.unreadable"), false, true, false, problem.message()));
        status = rows.isEmpty() ? Component.translatable("packmod.pack_conversion.all_compatible")
                : Component.translatable("packmod.pack_conversion.batch_detected", report.incompatible().size(), report.problems().size());
        rebuild();
    }

    void progress(int index, int total, PackConversionManager.Candidate candidate, String key) {
        Component step;
        if(key.startsWith("@")) {String[] parts=key.substring(1).split("\\|",4);
            step=parts.length==4&&parts[0].equals("tasks")?Component.translatable("packmod.pack_conversion.task_progress",parts[1],parts[2],parts[3])
                    :Component.translatable("packmod.pack_conversion.stage."+parts[0]);
        }else step=Component.translatable("packmod."+key);
        taskStatus=step;
        status = Component.translatable("packmod.pack_conversion.batch_progress", index, total,
                candidate.info().path().getFileName().toString(), step);
    }

    void completed(List<PackConversionManager.Outcome> outcomes) {
        phase = Phase.FINISHED; rows.clear(); selected.clear(); reportJobs.clear(); scroll = 0;
        for (var outcome : outcomes) {
            boolean success = outcome.output() != null && outcome.archived() != null && outcome.error() == null;
            boolean needsReview = outcome.output() != null && outcome.error() == null
                    && outcome.conversion() != null && outcome.conversion().needsReview();
            hasOutput |= outcome.output() != null;
            Component detail = Component.translatable(outcome.output()!=null&&outcome.error()!=null?"packmod.pack_conversion.archive_failed":outcome.cancelled()?"packmod.pack_conversion.cancelled":success ? "packmod.pack_conversion.archived"
                    : needsReview ? "packmod.pack_conversion.review_required"
                    : outcome.output() != null ? "packmod.pack_conversion.archive_failed" : "packmod.pack_conversion.conversion_failed");
            String tooltip = success ? outcome.output().getFileName() + "\nOld Pack/" + outcome.archived().getFileName()
                    : needsReview ? detail.getString() + "\n" + outcome.output().getFileName() : String.valueOf(outcome.error());
            if (outcome.conversion() != null) {
                var converted = outcome.conversion();
                reportJobs.put(outcome.candidate().info().path(),converted.reportFile().getParent());
                var counts = converted.report().changes();
                tooltip += "\n" + Component.translatable("packmod.pack_conversion.changes", counts.added(), counts.modified(), counts.removed()).getString();
                for (var warning : converted.report().warnings()) tooltip += "\n" + warningText(warning);
                for (var repair : converted.report().repairs()) tooltip += "\n" + ReportText.repair(repair.code(),repair.path(),repair.detail());
                tooltip += "\n" + Component.translatable("packmod.pack_conversion.report_location", converted.reportFile().toString()).getString();
            }
            rows.add(new Row(outcome.candidate().info().path(), null, detail, false, outcome.output()==null&&!outcome.cancelled(), needsReview || outcome.cancelled() || outcome.output()!=null&&outcome.error()!=null, tooltip));
        }
        var counts=PackConversionManager.OutcomeCounts.of(outcomes);
        status = Component.translatable("packmod.pack_conversion.batch_finished", counts.completed(),counts.review(),counts.failed(),counts.cancelled(),counts.archiveFailed());
        var packs = PackConversionManager.packScreen(parent);
        if (packs != null) ((PackSelectionRefresh) packs).packmod$reloadPacks();
        if (minecraft.gui.screen() == this) rebuild();
        else minecraft.gui.toastManager().addToast(new net.minecraft.client.gui.components.toasts.SystemToast(
                net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId.PERIODIC_NOTIFICATION, getTitle(), status));
    }
    private static String warningText(ConversionReport.Warning warning) {
        return ReportText.warning(warning);
    }
    @Override public void onClose() { transitionTo(parent); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (isClosing()) return true;
        if (x >= left() && x < left() + pw() && y >= listTop() && y < listTop() + visibleRows() * 34) {
            int next = Math.clamp(scroll - (int) Math.signum(dy) * Math.max(1, (int) Math.abs(dy)), 0, Math.max(0, rows.size() - visibleRows()));
            if (next != scroll) { scroll = next; rebuild(); }
            return true;
        }
        return super.mouseScrolled(x, y, dx, dy);
    }
    @Override public boolean keyPressed(KeyEvent event) {
        if (!isClosing() && (event.key() == GLFW.GLFW_KEY_PAGE_DOWN || event.key() == GLFW.GLFW_KEY_PAGE_UP)) {
            scroll = Math.clamp(scroll + (event.key() == GLFW.GLFW_KEY_PAGE_DOWN ? visibleRows() : -visibleRows()), 0, Math.max(0, rows.size() - visibleRows()));
            rebuild(); return true;
        }
        return super.keyPressed(event);
    }
    @Override public void extractBackground(GuiGraphicsExtractor g,int mx,int my,float delta) {
        super.extractBackground(g, mx, my, delta);
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        int x = left() + 13, y = top();
        g.centeredText(font, getTitle(), width / 2, y + 12, VanillaUi.WHITE);
        boolean allCompatible = rows.isEmpty() && phase == Phase.SELECTING;
        if (!allCompatible) g.text(font, font.plainSubstrByWidth(status.getString(), pw() - 26), x, y + 40, VanillaUi.WHITE, false);
        g.text(font, Component.translatable("packmod.pack_conversion.current", net.minecraft.SharedConstants.getCurrentVersion().name(), PackConversionManager.target().toString()), x, y + (allCompatible ? 43 : 55), VanillaUi.MUTED, false);
        if (!allCompatible) {
            String note = phase==Phase.CONVERTING ? taskStatus.getString() : Component.translatable("packmod.pack_conversion.batch_note").getString();
            g.text(font, font.plainSubstrByWidth(note, pw() - 26), x, y + 71, VanillaUi.MUTED, false);
        }
        if (phase == Phase.CHECKING || (rows.isEmpty() && phase == Phase.SELECTING))
            g.centeredText(font, status, width / 2, listTop() + 20, phase == Phase.CHECKING ? VanillaUi.WHITE : 0xFF72DFA3);
        if (rows.size() > visibleRows()) {
            int track = visibleRows() * 34 - 3, thumb = Math.max(12, track * visibleRows() / rows.size());
            int offset = (track - thumb) * scroll / (rows.size() - visibleRows());
            g.fill(left()+pw()-16,listTop(),left()+pw()-13,listTop()+track,0xFF000000);
            g.fill(left()+pw()-16,listTop()+offset,left()+pw()-13,listTop()+offset+thumb,0xFF808080);
        }
        if (phase == Phase.SELECTING && !rows.isEmpty()) {
            String count = Component.translatable("packmod.pack_conversion.selected", selected.size()).getString();
            g.text(font, count, left() + pw() - 14 - font.width(count), y + ph() - 78, VanillaUi.MUTED, false);
        }
        super.extractRenderState(g, mx, my, delta);
    }

    private final class PackRow extends AbstractWidget {
        private final Row row;
        PackRow(int x, int y, int width, Row row) {
            super(x, y, width, 30, Component.literal(row.path.getFileName().toString())); this.row = row;
            active = phase == Phase.SELECTING && row.eligible || phase == Phase.FINISHED && reportJobs.containsKey(row.path);
            setTooltip(Tooltip.create(Component.literal(getMessage().getString() + "\n" + row.tooltip)));
        }
        @Override protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
            g.fill(getX(), getY(), getX()+width, getY()+height, 0x60000000);
            if (isHoveredOrFocused() && active) {
                g.fill(getX(),getY(),getX()+width,getY()+1,0xFFFFFFFF);
                g.fill(getX(),getY()+height-1,getX()+width,getY()+height,0xFFFFFFFF);
                g.fill(getX(),getY(),getX()+1,getY()+height,0xFFFFFFFF);
                g.fill(getX()+width-1,getY(),getX()+width,getY()+height,0xFFFFFFFF);
            }
            int color = row.error ? 0xFFFFA3A3 : row.warning ? 0xFFFFD080 : active ? VanillaUi.WHITE : VanillaUi.MUTED;
            int textX = getX() + 24;
            if (row.candidate != null && phase == Phase.SELECTING) {
                g.fill(getX()+6,getY()+9,getX()+18,getY()+21,active?0xFFFFFFFF:0xFF808080);
                g.fill(getX()+7,getY()+10,getX()+17,getY()+20,0xFF000000);
                if (selected.contains(row.path)) {
                    g.fill(getX() + 9, getY() + 14, getX() + 11, getY() + 17, VanillaUi.WHITE);
                    g.fill(getX() + 11, getY() + 12, getX() + 14, getY() + 15, VanillaUi.WHITE);
                }
            }
            g.text(font, font.plainSubstrByWidth(getMessage().getString(), width - 31), textX, getY() + 4, color, false);
            g.text(font, font.plainSubstrByWidth(row.detail.getString(), width - 31), textX, getY() + 17, color, false);
        }
        private void toggle() {
            if (!active) return;
            if(phase==Phase.FINISHED){minecraft.gui.setScreen(new PackReportScreen(PackConversionScreen.this,reportJobs.get(row.path)));return;}
            if (!selected.remove(row.path)) selected.add(row.path); updateConvertButton();
        }
        @Override public void onClick(MouseButtonEvent event, boolean doubled) { toggle(); }
        @Override public boolean keyPressed(KeyEvent event) {
            if (active && isFocused() && (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_SPACE)) { toggle(); return true; }
            return super.keyPressed(event);
        }
        @Override protected void updateWidgetNarration(NarrationElementOutput output) {
            output.add(NarratedElementType.TITLE, getMessage()); output.add(NarratedElementType.USAGE, row.detail);
        }
    }
}
