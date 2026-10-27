package dev.blocknet.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.List;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

/**
 * A ring of actions around the screen center. Hold the menu key, move the mouse toward an action and
 * release the key (or left-click) to run it; releasing in the middle cancels.
 */
public final class RadialMenuScreen extends Screen {
    /** One slice of the ring. */
    public record Action(Component label, Runnable run) {}

    private static final float INNER_RADIUS = 28.0F;
    private static final float OUTER_RADIUS = 86.0F;
    private static final int SEGMENTS_PER_SLICE = 24;
    private static final int COLOR = 0xB0101418;
    private static final int COLOR_HOVERED = 0xD02E7D32;

    private final List<Action> actions;
    private final KeyMapping openKey;
    private int hovered = -1;

    /**
     * @param openKey the key (or mouse button) holding the menu open; releasing it picks the hovered action
     */
    public RadialMenuScreen(List<Action> actions, KeyMapping openKey) {
        super(Component.translatable("blocknet_ai.radial.title"));
        this.actions = List.copyOf(actions);
        this.openKey = openKey;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // No blur or dimming: the world stays visible behind the ring.
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        hovered = sliceAt(mouseX, mouseY);
        float centerX = width / 2.0F;
        float centerY = height / 2.0F;
        Matrix4f pose = graphics.pose().last().pose();
        for (int i = 0; i < actions.size(); i++) {
            drawSlice(pose, centerX, centerY, i, i == hovered ? COLOR_HOVERED : COLOR);
        }
        float labelRadius = (INNER_RADIUS + OUTER_RADIUS) / 2.0F;
        for (int i = 0; i < actions.size(); i++) {
            float angle = sliceCenter(i);
            int x = Math.round(centerX + Mth.cos(angle) * labelRadius);
            int y = Math.round(centerY + Mth.sin(angle) * labelRadius) - font.lineHeight / 2;
            graphics.drawCenteredString(font, actions.get(i).label(), x, y, i == hovered ? 0xFFFFFF : 0xD0D0D0);
        }
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (openKey.matchesMouse(button)) {
            choose(sliceAt(mouseX, mouseY));
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (openKey.matches(keyCode, scanCode)) {
            choose(hovered);
            return true;
        }
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            choose(sliceAt(mouseX, mouseY));
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void choose(int slice) {
        onClose();
        if (slice >= 0) {
            actions.get(slice).run().run();
        }
    }

    /** Index of the slice under the cursor, or -1 inside the dead zone in the middle. */
    private int sliceAt(double mouseX, double mouseY) {
        double dx = mouseX - width / 2.0;
        double dy = mouseY - height / 2.0;
        if (actions.isEmpty() || dx * dx + dy * dy < INNER_RADIUS * INNER_RADIUS) {
            return -1;
        }
        double fromStart = Math.atan2(dy, dx) - startAngle();
        double turn = Math.PI * 2.0;
        fromStart = ((fromStart % turn) + turn) % turn;
        return Math.min(actions.size() - 1, (int) (fromStart / sliceSize()));
    }

    /** The first slice is centered at the top of the ring. */
    private float startAngle() {
        return (float) (-Math.PI / 2.0 - sliceSize() / 2.0);
    }

    private float sliceSize() {
        return (float) (Math.PI * 2.0 / actions.size());
    }

    private float sliceCenter(int slice) {
        return startAngle() + sliceSize() * (slice + 0.5F);
    }

    private void drawSlice(Matrix4f pose, float centerX, float centerY, int slice, int argb) {
        // A small gap between slices keeps them readable.
        float gap = 0.04F;
        float from = startAngle() + sliceSize() * slice + gap;
        float to = startAngle() + sliceSize() * (slice + 1) - gap;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i <= SEGMENTS_PER_SLICE; i++) {
            float angle = from + (to - from) * i / SEGMENTS_PER_SLICE;
            float cos = Mth.cos(angle);
            float sin = Mth.sin(angle);
            buffer.addVertex(pose, centerX + cos * OUTER_RADIUS, centerY + sin * OUTER_RADIUS, 0.0F).setColor(argb);
            buffer.addVertex(pose, centerX + cos * INNER_RADIUS, centerY + sin * INNER_RADIUS, 0.0F).setColor(argb);
        }
        BufferUploader.drawWithShader(buffer.buildOrThrow());
        RenderSystem.disableBlend();
    }
}
