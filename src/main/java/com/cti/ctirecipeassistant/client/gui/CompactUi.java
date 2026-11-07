package com.cti.ctirecipeassistant.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.screens.Screen;

/** Vanilla-style neutral surfaces, shared by custom screens and terminal overlays. */
public final class CompactUi {
    private CompactUi() {}
    public static void panel(PoseStack pose, int x, int y, int w, int h) {
        Screen.fill(pose, x, y, x + w, y + h, 0xFF373737);
        Screen.fill(pose, x + 1, y + 1, x + w - 1, y + h - 1, 0xFFFFFFFF);
        Screen.fill(pose, x + 3, y + 3, x + w - 1, y + h - 1, 0xFF555555);
        Screen.fill(pose, x + 3, y + 3, x + w - 3, y + h - 3, 0xFFC6C6C6);
    }
    public static void slot(PoseStack pose, int x, int y) {
        Screen.fill(pose, x, y, x + 18, y + 18, 0xFF373737);
        Screen.fill(pose, x + 1, y + 1, x + 18, y + 18, 0xFFFFFFFF);
        Screen.fill(pose, x + 1, y + 1, x + 17, y + 17, 0xFF8B8B8B);
    }
    public static void button(PoseStack pose, int x, int y, int w, int h, boolean hover, boolean enabled) {
        Screen.fill(pose, x, y, x + w, y + h, 0xFF373737);
        Screen.fill(pose, x + 1, y + 1, x + w - 1, y + h - 1, enabled && hover ? 0xFFEEEEEE : 0xFFAAAAAA);
        Screen.fill(pose, x + 2, y + 2, x + w - 2, y + h - 2, enabled && hover ? 0xFF999999 : 0xFF707070);
    }
    public static boolean hit(double x, double y, int l, int t, int w, int h) {
        return x >= l && x < l + w && y >= t && y < t + h;
    }
}
