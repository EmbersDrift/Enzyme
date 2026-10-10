package com.embers.packmod;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Font;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.locale.Language;
import org.joml.Matrix3x2fStack;
import java.util.HashMap;
import java.util.Map;

public final class AeroUi {
    public static final int WHITE = 0xFFF8FBFF;
    public static final int MUTED = 0xFFB8C3D0;
    public static final int BLUE = 0xFFE4F4FF;
    public static final int BORDER = 0xA8E8F0F8;
    public static final int GLASS = 0x95141A24;
    private static final Map<Integer, int[]> CORNER_COVERAGE = new HashMap<>();
    private static final BoundedCache<String, Component> LABELS = new BoundedCache<>(1024);
    private static Language language;
    private record GeometryKey(int width, int height, int radius, boolean outline) {}
    private static final BoundedCache<GeometryKey, int[]> GEOMETRY = new BoundedCache<>(256);

    private AeroUi() {}

    public static Component ui(String text) {
        Language current = Language.getInstance();
        if (language != current) {
            clearTextCache();
            language = current;
        }
        return LABELS.computeIfAbsent(text,
                value -> Component.literal(ClientText.get(value)));
    }

    public static Component ui(Component text) {
        String value = text.getString();
        // Native translated labels can carry a resource-pack font, color or other formatting.
        // Preserve those components when no client-specific label translation is necessary.
        return value.equals(ClientText.get(value)) ? text : ui(value);
    }

    static void clearTextCache() { LABELS.clear(); }

    public static void text(GuiGraphicsExtractor graphics, Font font, String value,
                            int x, int y, int color, boolean shadow) {
        graphics.text(font, ui(value), x, y, color, shadow);
    }

    public static void centeredText(GuiGraphicsExtractor graphics, Font font, String value,
                                    int x, int y, int color) {
        graphics.centeredText(font, ui(value), x, y, color);
    }

    public static void rounded(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int radius, int color) {
        if (width <= 0 || height <= 0) return;
        drawRounded(graphics, x, y, width, height, radius, color, false);
    }

    private static void drawRounded(GuiGraphicsExtractor graphics, int x, int y, int width, int height,
                                    int radius, int color, boolean outline) {
        if ((color >>> 24) == 0) return;
        if(RoundedTextures.draw(graphics,x,y,width,height,radius,color,outline))return;
        Matrix3x2fStack pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(x, y);
        pose.scale(0.5f, 0.5f);
        int w = width * 2, h = height * 2;
        int r = Math.max(0, Math.min(radius * 2, Math.min(w, h) / 2));
        GeometryKey key = new GeometryKey(w, h, r, outline);
        int[] geometry = GEOMETRY.get(key);
        if (geometry == null) {
            geometry = RoundedGeometry.create(w, h, r, outline, AeroUi::coverage);
            GEOMETRY.put(key, geometry);
        }
        for (int i = 0; i < geometry.length; i += 5) {
            int tint = withCoverage(color, geometry[i + 4]);
            if ((tint >>> 24) != 0)
                graphics.fill(geometry[i], geometry[i + 1], geometry[i + 2], geometry[i + 3], tint);
        }
        pose.popMatrix();
    }

    private static int coverage(int width, int height, int radius, int col, int row) {
        if (col < 0 || col >= width || row < 0 || row >= height) return 0;
        if (radius <= 0) return col >= 0 && col < width && row >= 0 && row < height ? 16 : 0;
        int cornerX = Math.min(col, width - col - 1);
        int cornerY = Math.min(row, height - row - 1);
        if (cornerX >= radius || cornerY >= radius) return 16;
        if (radius <= 128) {
            int[] mask = CORNER_COVERAGE.computeIfAbsent(radius, AeroUi::createCornerMask);
            return mask[cornerY * radius + cornerX];
        }
        return sampleCoverage(width, height, radius, col, row);
    }

    private static int[] createCornerMask(int radius) {
        int[] result = new int[radius * radius];
        for (int y = 0; y < radius; y++) for (int x = 0; x < radius; x++)
            result[y * radius + x] = sampleCoverage(radius * 2, radius * 2, radius, x, y);
        return result;
    }

    private static int sampleCoverage(int width, int height, int radius, int col, int row) {
        int count = 0;
        for (int sy = 0; sy < 4; sy++) for (int sx = 0; sx < 4; sx++) {
            double px = col + (sx + 0.5) / 4.0;
            double py = row + (sy + 0.5) / 4.0;
            if (px < 0 || px >= width || py < 0 || py >= height) continue;
            double cx = px < radius ? radius : px > width - radius ? width - radius : px;
            double cy = py < radius ? radius : py > height - radius ? height - radius : py;
            double dx = px - cx, dy = py - cy;
            if (dx * dx + dy * dy <= radius * radius) count++;
        }
        return count;
    }

    private static int withCoverage(int color, int coverage) {
        int alpha = ((color >>> 24) * Math.clamp(coverage, 0, 16) + 8) / 16;
        return (alpha << 24) | (color & 0xFFFFFF);
    }

    public static void roundedOutline(GuiGraphicsExtractor graphics, int x, int y, int width, int height,
                                      int radius, int color) {
        if (width <= 1 || height <= 1) return;
        drawRounded(graphics, x, y, width, height, radius, color, true);
    }

    public static void glass(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int radius) {
        rounded(graphics, x + 2, y + 4, width, height, radius, 0x39000000);
        rounded(graphics, x + 1, y + 1, width - 2, height - 2, Math.max(0, radius - 1), GLASS);
        roundedOutline(graphics, x, y, width, height, radius, BORDER);
    }

    public static void window(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        glass(graphics, x, y, width, height, 15);
        graphics.fill(x + 14, y + 28, x + width - 14, y + 29, 0x60F7FBFF);
        graphics.fill(x + 16, y + height - 42, x + width - 16, y + height - 41, 0x38F7FBFF);
    }

    public static void modalBackdrop(GuiGraphicsExtractor graphics, int width, int height,
                                     int x, int y, int windowWidth, int windowHeight) {
        if (Minecraft.getInstance().level != null) graphics.blurBeforeThisStratum();
        int dim = 0x49090D14;
        graphics.fill(0, 0, width, Math.max(0, y), dim);
        graphics.fill(0, Math.max(0, y), Math.max(0, x), Math.min(height, y + windowHeight), dim);
        graphics.fill(Math.min(width, x + windowWidth), Math.max(0, y), width,
                Math.min(height, y + windowHeight), dim);
        graphics.fill(0, Math.min(height, y + windowHeight), width, height, dim);
    }

    public static void background(GuiGraphicsExtractor graphics, int width, int height) {
        graphics.blurBeforeThisStratum();
        graphics.fillGradient(0, 0, width, height, 0x42060A10, 0x5D0C111B);
    }
}
