package com.cti.ctirecipeassistant.integration.ae2;

import appeng.client.gui.me.patternaccess.PatternAccessTermScreen;
import com.cti.ctirecipeassistant.client.gui.CompactUi;
import com.cti.ctirecipeassistant.client.operation.*;
import com.cti.ctirecipeassistant.recipe.RecipePreferences;
import com.google.gson.JsonParseException;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.*;
import org.lwjgl.glfw.GLFW;
import java.util.*;

/** Keep the native terminal active so provider packets continue reaching AE2. */
public final class DistributionPreview {
    private static final int BUTTON_SIZE = 12;
    private static DistributionPreview open;
    private final PatternAccessTermScreen<?> terminal;
    private final RecipePreferences preferences = new RecipePreferences();
    private final Map<String, Long> confirmed = new HashMap<>();
    private List<PatternDistribution.Row> rows;
    private List<PatternDistribution.Provider> providers;
    private int offset, choosing = -1, choiceOffset, inventoryState;
    private boolean categories, dirty;
    private String error = "";
    private int mouseX, mouseY;
    private List<Component> tooltip = List.of();
    private DistributionPreview(PatternAccessTermScreen<?> terminal) {
        this.terminal = terminal; rows = PatternDistribution.rows(terminal, preferences); refresh();
    }
    public static boolean active() { return open != null; }
    public static void updated(PatternAccessTermScreen<?> terminal) {
        if (open != null && open.terminal == terminal) open.dirty = true;
    }
    private void refresh() {
        providers = PatternDistribution.providers(terminal);
        PatternDistribution.plan(rows, providers, preferences, confirmed);
        inventoryState = terminal.getMenu().getStateId(); dirty = false;
    }
    private void updateRows() {
        var next = PatternDistribution.rows(terminal, preferences);
        for (var row : next) for (var old : rows) if (row.content.equals(old.content)) {
            row.category = old.category; row.confirmedDuplicate = old.confirmedDuplicate;
        }
        rows = next; choosing = -1; refresh(); offset = Math.min(offset, Math.max(0, rows.size() - visible()));
    }
    private int visible() { return Math.max(1, (terminal.height - 130) / 40); }
    private int span() { return Math.min(520, terminal.width - 16); }
    private int left() { return (terminal.width - span()) / 2; }
    // AE2 scrollbar is 12px wide at x=175/y=18. Keep the same column and a 6px gap.
    private static int buttonX(PatternAccessTermScreen<?> terminal) { return terminal.getGuiLeft() + 175; }
    private static int buttonY(PatternAccessTermScreen<?> terminal) { return terminal.getGuiTop(); }
    private static boolean available() { return !CraftExecutor.busy() && !PatternEncoder.busy() && !SingleRecipeTransfer.busy(); }
    private void button(PoseStack pose, String text, int x, int y, int w) {
        CompactUi.button(pose, x, y, w, 18, CompactUi.hit(mouseX, mouseY, x, y, w, 18), true);
        Screen.drawCenteredString(pose, Minecraft.getInstance().font, text, x + w / 2, y + 5, 0xFFFFFF);
    }
    private void text(PoseStack pose, String value, int x, int y, int width, int color) {
        var font = Minecraft.getInstance().font;
        font.draw(pose, font.plainSubstrByWidth(value, Math.max(1, width)), x, y, color);
    }
    @SubscribeEvent public static void render(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof PatternAccessTermScreen<?> terminal) || PatternDistributor.busy()) return;
        var pose = event.getPoseStack(); pose.pushPose(); pose.translate(0, 0, 700);
        if (open == null) {
            int x = buttonX(terminal), y = buttonY(terminal);
            boolean hover = CompactUi.hit(event.getMouseX(), event.getMouseY(), x, y, BUTTON_SIZE, BUTTON_SIZE), enabled = available();
            CompactUi.button(pose, x, y, BUTTON_SIZE, BUTTON_SIZE, hover, enabled);
            int color = enabled ? 0xFFF0F0F0 : 0xFF999999;
            for (int i = 0; i < 4; i++) Screen.fill(pose, x + 4 + i, y + 3 + i, x + 5 + i, y + 10 - i, color);
            if (hover) renderTooltip(terminal, pose, List.of(Component.literal("自动分发")), event.getMouseX(), event.getMouseY());
        } else if (open.terminal == terminal) {
            open.mouseX = event.getMouseX(); open.mouseY = event.getMouseY();
            if (open.dirty || open.inventoryState != terminal.getMenu().getStateId()) open.updateRows();
            open.draw(pose);
        }
        pose.popPose();
    }
    private static void renderTooltip(Screen screen, PoseStack pose, List<Component> lines, int x, int y) {
        // Overlay z=700; vanilla tooltip adds 400. Use z=350+400=750, inside GUI clipping.
        pose.pushPose(); pose.translate(0, 0, -350);
        screen.renderComponentTooltip(pose, lines, x, y);
        pose.popPose();
    }
    private void draw(PoseStack pose) {
        int l = left(), w = span(), bottom = terminal.height - 12; tooltip = List.of();
        Screen.fill(pose, 0, 0, terminal.width, terminal.height, 0xA0000000);
        CompactUi.panel(pose, l, 12, w, bottom - 12);
        text(pose, choosing < 0 ? "自动分发" : categories ? "配方类别" : "目标供应器", l + 10, 22, w - 20, 0x404040);
        if (choosing < 0) {
            int upload = rows.stream().mapToInt(r -> r.upload).sum(), existing = rows.stream().mapToInt(r -> r.existing).sum();
            int unmatched = rows.stream().filter(r -> r.provider == null).mapToInt(r -> r.quantity).sum();
            text(pose, "待上传 " + upload + "  重复 " + existing + "  未匹配 " + unmatched, l + 10, 39, w - 20, 0x555555);
            for (int n = 0; n < visible(); n++) {
                int index = offset + n; if (index >= rows.size()) break;
                var row = rows.get(index); int y = 58 + n * 40;
                var renderer = Minecraft.getInstance().getItemRenderer();
                float depth = renderer.blitOffset; renderer.blitOffset = 710;
                renderer.renderAndDecorateItem(row.pattern, l + 10, y + 2); renderer.blitOffset = depth;
                text(pose, row.machine() + " ×" + row.quantity, l + 32, y, w - 168, 0x303030);
                String target = row.provider == null ? "未匹配供应器" : row.provider.name() + " / " + row.provider.type() + " / 空位 " + row.provider.free();
                text(pose, target, l + 32, y + 12, w - 168, 0x555555);
                text(pose, row.reason, l + 32, y + 24, w - 148, 0x77512D);
                button(pose, "类别", l + w - 118, y, 44); button(pose, "供应器", l + w - 68, y, 58);
                if (needsDuplicateConfirmation(row)) button(pose, "确认重复类别", l + w - 106, y + 20, 96);
                if (CompactUi.hit(mouseX, mouseY, l + 10, y, w - 140, 36))
                    tooltip = List.of(Component.literal(row.machine()), Component.literal(target), Component.literal(row.reason));
            }
            if (rows.isEmpty()) text(pose, "没有待分发样板", l + 10, 62, w - 20, 0x555555);
            text(pose, error.isEmpty() ? preferences.error : error, l + 10, bottom - 44, w - 20, 0xAA3333);
            button(pose, "开始分发", l + w - 86, bottom - 27, 76);
        } else {
            var row = rows.get(choosing); int count = categories ? row.records.size() : providers.size();
            for (int n = 0; n < visible(); n++) {
                int index = choiceOffset + n; if (index >= count) break; int y = 58 + n * 40;
                Screen.fill(pose, l + 8, y, l + w - 8, y + 34, CompactUi.hit(mouseX, mouseY, l + 8, y, w - 16, 34) ? 0xFFAAAAAA : 0xFFB8B8B8);
                if (categories) {
                    var record = row.records.get(index);
                    text(pose, record.get("machine").getAsString() + " / " + record.get("type").getAsString(), l + 12, y + 4, w - 24, 0x303030);
                    text(pose, record.get("categoryUid").getAsString(), l + 12, y + 18, w - 24, 0x555555);
                } else {
                    var provider = providers.get(index);
                    text(pose, provider.name() + " / " + provider.type() + " / 空位 " + provider.free(), l + 12, y + 4, w - 24, 0x303030);
                    text(pose, "#" + provider.id(), l + 12, y + 18, w - 24, 0x555555);
                }
            }
            button(pose, "返回", l + 10, bottom - 27, 44);
        }
        int count = choosing < 0 ? rows.size() : categories ? rows.get(choosing).records.size() : providers.size();
        int start = choosing < 0 ? offset : choiceOffset;
        if (count > visible()) {
            int track = visible() * 40, thumb = Math.max(8, track * visible() / count);
            Screen.fill(pose, l + w - 5, 58, l + w - 4, 58 + track, 0xFF888888);
            int y = 58 + (track - thumb) * start / (count - visible());
            Screen.fill(pose, l + w - 6, y, l + w - 3, y + thumb, 0xFF555555);
        }
        if (!tooltip.isEmpty()) renderTooltip(terminal, pose, tooltip, mouseX, mouseY);
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST) public static void mouse(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!(event.getScreen() instanceof PatternAccessTermScreen<?> terminal) || PatternDistributor.busy()) return;
        if (open == null) {
            if (event.getButton() == 0 && CompactUi.hit(event.getMouseX(), event.getMouseY(), buttonX(terminal), buttonY(terminal), BUTTON_SIZE, BUTTON_SIZE)) {
                if (available()) {
                    try { open = new DistributionPreview(terminal); }
                    catch (JsonParseException error) { Minecraft.getInstance().gui.getChat().addMessage(Component.literal("无法读取本地样板记录：" + error.getMessage())); }
                }
                event.setCanceled(true);
            }
        } else if (open.terminal == terminal) {
            if (open.dirty || open.inventoryState != terminal.getMenu().getStateId()) open.updateRows();
            if (event.getButton() == 0) open.click(event.getMouseX(), event.getMouseY());
            event.setCanceled(true);
        }
    }
    private boolean needsDuplicateConfirmation(PatternDistribution.Row row) {
        return row.provider != null && row.records.size() > 1 && row.category >= 0
                && PatternDistribution.contains(row.provider, row.pattern)
                && !row.confirmedDuplicate.equals(row.route() + "|" + row.provider.id());
    }
    private void click(double x, double y) {
        int l = left(), w = span(), bottom = terminal.height - 12;
        if (choosing >= 0 && CompactUi.hit(x, y, l + 10, bottom - 27, 44, 18)) { choosing = -1; return; }
        if (choosing < 0 && CompactUi.hit(x, y, l + w - 86, bottom - 27, 76, 18)) {
            refresh(); error = PatternDistributor.start(terminal, rows); if (error.isEmpty()) open = null; return;
        }
        if (!CompactUi.hit(x, y, l + 8, 58, w - 16, visible() * 40)) return;
        int n = (int)(y - 58) / 40, index = (choosing < 0 ? offset : choiceOffset) + n, rowY = 58 + n * 40;
        if (choosing < 0) {
            if (index >= rows.size()) return; var row = rows.get(index);
            if (needsDuplicateConfirmation(row) && CompactUi.hit(x, y, l + w - 106, rowY + 20, 96, 18)) {
                row.confirmedDuplicate = row.route() + "|" + row.provider.id(); refresh(); return;
            }
            if (CompactUi.hit(x, y, l + w - 118, rowY, 44, 18) && !row.records.isEmpty()) { choosing = index; categories = true; choiceOffset = 0; }
            else if (CompactUi.hit(x, y, l + w - 68, rowY, 58, 18) && row.category >= 0) { refresh(); choosing = index; categories = false; choiceOffset = 0; }
        } else {
            if (y - rowY >= 34) return; var row = rows.get(choosing);
            if (categories) { if (index >= row.records.size()) return; row.category = index; }
            else {
                if (index >= providers.size()) return; var provider = providers.get(index);
                preferences.put("provider-group|" + row.route(), provider.key()); confirmed.put(row.route(), provider.id());
            }
            choosing = -1; refresh();
        }
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST) public static void scroll(ScreenEvent.MouseScrolled.Pre event) {
        if (open == null || event.getScreen() != open.terminal) return;
        if (CompactUi.hit(event.getMouseX(), event.getMouseY(), open.left() + 8, 58, open.span() - 16, open.visible() * 40)) {
            int count = open.choosing < 0 ? open.rows.size() : open.categories ? open.rows.get(open.choosing).records.size() : open.providers.size();
            int start = open.choosing < 0 ? open.offset : open.choiceOffset;
            start = Math.max(0, Math.min(Math.max(0, count - open.visible()), start + (event.getScrollDelta() > 0 ? -1 : 1)));
            if (open.choosing < 0) open.offset = start; else open.choiceOffset = start;
        }
        event.setCanceled(true);
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST) public static void key(ScreenEvent.KeyPressed.Pre event) {
        if (open == null || event.getScreen() != open.terminal) return;
        if (event.getKeyCode() == GLFW.GLFW_KEY_ESCAPE) { if (open.choosing >= 0) open.choosing = -1; else open = null; }
        event.setCanceled(true);
    }
    @SubscribeEvent public static void closing(ScreenEvent.Closing event) { if (open != null && event.getScreen() == open.terminal) open = null; }
    @SubscribeEvent(priority = EventPriority.HIGHEST) public static void typed(ScreenEvent.CharacterTyped.Pre event) { if (open != null && event.getScreen() == open.terminal) event.setCanceled(true); }
    @SubscribeEvent(priority = EventPriority.HIGHEST) public static void drag(ScreenEvent.MouseDragged.Pre event) { if (open != null && event.getScreen() == open.terminal) event.setCanceled(true); }
    @SubscribeEvent(priority = EventPriority.HIGHEST) public static void release(ScreenEvent.MouseButtonReleased.Pre event) { if (open != null && event.getScreen() == open.terminal) event.setCanceled(true); }
}
