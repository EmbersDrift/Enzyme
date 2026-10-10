package com.embers.packmod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;

public final class AeroButton extends AbstractWidget {
    private final Consumer<AeroButton> action;

    public AeroButton(int x, int y, int width, int height, String label, Consumer<AeroButton> action) {
        super(x - 1, y - 1, width + 2, height + 2, Component.literal(label));
        this.action = action;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        int outline = !active ? 0x42677888 : isHoveredOrFocused() ? 0xD8DAE9F8 : 0x706F8092;
        int fill = !active ? 0x65151B25 : isHoveredOrFocused() ? 0xDC344255 : 0xC0212B38;
        AeroUi.rounded(graphics, getX(), getY(), width, height, 6, fill);
        AeroUi.roundedOutline(graphics, getX(), getY(), width, height, 6, outline);
        var font = Minecraft.getInstance().font;
        var label = AeroUi.ui(getMessage());
        float scale = Math.min(1, (width - 10) / (float) Math.max(1, font.width(label)));
        graphics.pose().pushMatrix();
        graphics.pose().translate(getX() + width / 2f, getY() + (height - 9 * scale) / 2f);
        graphics.pose().scale(scale, scale);
        graphics.centeredText(font, label, 0, 0, active ? AeroUi.WHITE : AeroUi.MUTED);
        graphics.pose().popMatrix();
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubled) {
        if (active && visible) action.accept(this);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (active && visible && isFocused() && (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_SPACE)) {
            action.accept(this);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, getMessage());
    }
}
