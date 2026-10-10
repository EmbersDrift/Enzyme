package com.embers.packmod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Compiles the supersampled coverage into disjoint rectangles, independent of color or opacity. */
final class RoundedGeometry {
    @FunctionalInterface
    interface Coverage { int at(int width, int height, int radius, int x, int y); }

    private record Rect(int x0, int y0, int x1, int y1, int coverage) {}

    static int[] create(int w, int h, int r, boolean outline, Coverage coverage) {
        List<Rect> rectangles = new ArrayList<>();
        if (r == 0) {
            if (outline) {
                add(rectangles, 0, 0, w, 2, 16);
                add(rectangles, 0, h - 2, w, h, 16);
                add(rectangles, 0, 2, 2, h - 2, 16);
                add(rectangles, w - 2, 2, w, h - 2, 16);
            } else add(rectangles, 0, 0, w, h, 16);
        } else {
            int limit = Math.min(w / 2, r + 2);
            if (outline) {
                if (w > 2 * limit) {
                    add(rectangles, limit, 0, w - limit, 2, 16);
                    add(rectangles, limit, h - 2, w - limit, h, 16);
                }
                if (h > 2 * r) {
                    add(rectangles, 0, r, 2, h - r, 16);
                    add(rectangles, w - 2, r, w, h - r, 16);
                }
            } else if (h > 2 * r) add(rectangles, 0, r, w, h - r, 16);
            for (int row = 0; row < r; row++) {
                row(rectangles, w, h, r, row, limit, outline, coverage);
                row(rectangles, w, h, r, h - row - 1, limit, outline, coverage);
            }
        }
        // Adjacent equal-coverage pixels become one submission; the coverage never changes.
        rectangles = merge(rectangles, false);
        rectangles = merge(rectangles, true);
        int[] result = new int[rectangles.size() * 5];
        int offset = 0;
        for (Rect rect : rectangles) {
            result[offset++] = rect.x0; result[offset++] = rect.y0;
            result[offset++] = rect.x1; result[offset++] = rect.y1;
            result[offset++] = rect.coverage;
        }
        return result;
    }

    private static void row(List<Rect> rectangles, int w, int h, int r, int y, int limit,
                            boolean outline, Coverage coverage) {
        int count = limit;
        if (!outline) {
            count = 0;
            while (count < r && coverage.at(w, h, r, count, y) < 16) count++;
            add(rectangles, count, y, w - count, y + 1, 16);
        }
        for (int col = 0; col < count; col++) {
            int edge = coverage.at(w, h, r, col, y);
            if (outline) edge -= coverage.at(w - 4, h - 4, Math.max(0, r - 2), col - 2, y - 2);
            add(rectangles, col, y, col + 1, y + 1, edge);
            add(rectangles, w - col - 1, y, w - col, y + 1, edge);
        }
    }

    private static void add(List<Rect> rectangles, int x0, int y0, int x1, int y1, int coverage) {
        if (coverage > 0 && x1 > x0 && y1 > y0)
            rectangles.add(new Rect(x0, y0, x1, y1, Math.min(16, coverage)));
    }

    private static List<Rect> merge(List<Rect> rectangles, boolean vertical) {
        rectangles.sort(vertical
                ? Comparator.comparingInt(Rect::x0).thenComparingInt(Rect::x1)
                    .thenComparingInt(Rect::coverage).thenComparingInt(Rect::y0)
                : Comparator.comparingInt(Rect::y0).thenComparingInt(Rect::y1)
                    .thenComparingInt(Rect::coverage).thenComparingInt(Rect::x0));
        List<Rect> result = new ArrayList<>();
        Rect previous = null;
        for (Rect rect : rectangles) {
            if (previous != null && rect.coverage == previous.coverage && (vertical
                    ? rect.x0 == previous.x0 && rect.x1 == previous.x1 && previous.y1 == rect.y0
                    : rect.y0 == previous.y0 && rect.y1 == previous.y1 && previous.x1 == rect.x0)) {
                previous = new Rect(previous.x0, previous.y0, rect.x1, rect.y1, rect.coverage);
                result.set(result.size() - 1, previous);
            } else {
                result.add(rect);
                previous = rect;
            }
        }
        return result;
    }
}
