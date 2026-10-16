import java.nio.file.Path;
import javax.imageio.ImageIO;

/** Read real client screenshots; blurred titles must fail even if mouse input works. */
public final class UiClarityCheck {
    public static void main(String[] args) throws Exception {
        for (String file : args) {
            var image = ImageIO.read(Path.of(file).toFile());
            if (image == null) throw new AssertionError("Unreadable screenshot: " + file);
            String name = Path.of(file).getFileName().toString();
            // The audit fixes GUI scale to 2. Match each actual modal's geometry,
            // excluding its border, horizontal separator, and widgets.
            boolean compatible = name.contains("all-compatible");
            int guiWidth = image.getWidth() / 2, guiHeight = image.getHeight() / 2;
            int desiredWidth = 520, desiredHeight = compatible ? 200 : 348, margin = 16;
            if (name.contains("conversion-options")) { desiredWidth = 440; desiredHeight = 302; margin = 20; }
            else if (name.contains("conversion-preview")) { desiredWidth = 540; desiredHeight = 310; margin = 20; }
            else if (name.contains("conversion-history")) { desiredWidth = 560; desiredHeight = 350; margin = 20; }
            else if (name.contains("report-details")) { desiredWidth = 620; desiredHeight = 420; margin = 20; }
            int width = Math.min(desiredWidth, guiWidth - margin);
            int height = Math.min(desiredHeight, guiHeight - 16);
            int left = (guiWidth - width) / 2, top = (guiHeight - height) / 2;
            int bright = 0, edge = 0;
            for (int y = (top + 12) * 2; y < (top + 24) * 2; y++) {
                for (int x = (left + 13) * 2; x < (left + width - 13) * 2 - 1; x++) {
                    int color = image.getRGB(x, y), next = image.getRGB(x + 1, y);
                    int value = Math.min((color >> 16) & 255, Math.min((color >> 8) & 255, color & 255));
                    int adjacent = Math.min((next >> 16) & 255, Math.min((next >> 8) & 255, next & 255));
                    if (value >= 180) bright++;
                    edge = Math.max(edge, Math.abs(value - adjacent));
                }
            }
            if (bright < 40 || edge < 100) throw new AssertionError("UI title is blurred/covered: " + file + " bright=" + bright + " edge=" + edge);
            System.out.println("UI CLARITY PASS " + file + " bright=" + bright + " edge=" + edge);
        }
    }
}
