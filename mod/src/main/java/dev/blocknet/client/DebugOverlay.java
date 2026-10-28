// SPDX-License-Identifier: MIT
package dev.blocknet.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.blocknet.Blocknet;
import dev.blocknet.debug.DebugPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4fStack;

/**
 * Draws what a watched bot sees and intends ({@code /blocknet debug}): everything is drawn through
 * walls, because the interesting part (a route through a tunnel, ore behind stone) usually is.
 *
 * <ul>
 *   <li>red box: the block it acts on now; orange: an obstacle it clears first</li>
 *   <li>yellow: the navigation goal; green line: the route (dim = walked), grey: route being planned</li>
 *   <li>red/blue small boxes: blocks the route will break/place</li>
 *   <li>cyan: head direction and field of view; dots: blocks its eye rays reached recently</li>
 *   <li>labelled boxes: scene objects with the refs the brain uses (t3, o1, ...), seen mobs, frontiers</li>
 * </ul>
 */
@EventBusSubscriber(modid = Blocknet.MOD_ID, value = Dist.CLIENT)
public final class DebugOverlay {
    private static final int PANEL_WIDTH = 360;

    private DebugOverlay() {}

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        DebugClient.clear();
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        // The stage NeoForge's own debug renderers use in 1.21.1; drawing ignores depth anyway.
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        List<DebugClient.View> views = DebugClient.views();
        if (views.isEmpty()) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;
        Camera camera = event.getCamera();
        Vec3 cam = camera.getPosition();
        float partial = minecraft.getTimer().getGameTimeDeltaPartialTick(true);

        // Set the camera rotation explicitly rather than relying on what the level renderer left there.
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.set(event.getModelViewMatrix());
        RenderSystem.applyModelViewMatrix();
        try {
            List<Label> labels = new ArrayList<>();
            RenderSystem.disableDepthTest();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableCull();
            RenderSystem.setShader(GameRenderer::getRendertypeLinesShader);
            RenderSystem.lineWidth(2.0F);
            BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL);
            Lines lines = new Lines(buffer, cam);
            for (DebugClient.View view : views) draw(view.frame(), lines, labels, minecraft, partial);
            MeshData mesh = buffer.build();
            if (mesh != null) BufferUploader.drawWithShader(mesh);
            RenderSystem.lineWidth(1.0F);
            RenderSystem.enableCull();
            RenderSystem.enableDepthTest();
            labels(labels, minecraft, camera);
        } finally {
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
        }
    }

    private record Label(Vec3 at, String text, int color) {}

    private static void draw(DebugPayload frame, Lines lines, List<Label> labels, Minecraft minecraft, float partial) {
        // Recently seen blocks: small markers, coloured by what the bot recognised.
        for (DebugPayload.Seen seen : frame.seen()) {
            int color = color(DebugPayload.CATEGORIES.get(Math.min(seen.category(), DebugPayload.CATEGORIES.size() - 1)));
            Vec3 center = Vec3.atCenterOf(seen.pos());
            lines.box(new AABB(center, center).inflate(0.12), alpha(color, 150));
        }
        for (DebugPayload.Thing thing : frame.objects()) {
            int color = alpha(color(DebugPayload.CATEGORIES.get(Math.min(thing.category(), DebugPayload.CATEGORIES.size() - 1))),
                thing.remembered() ? 120 : 230);
            AABB box = new AABB(thing.minX(), thing.minY(), thing.minZ(), thing.maxX(), thing.maxY(), thing.maxZ());
            lines.box(box, color);
            labels.add(new Label(new Vec3((box.minX + box.maxX) / 2, box.maxY + 0.3, (box.minZ + box.maxZ) / 2),
                thing.label() + (thing.remembered() ? " (remembered)" : ""), color));
        }
        for (DebugPayload.Mob mob : frame.entities()) {
            int color = alpha(color(DebugPayload.CATEGORIES.get(Math.min(mob.category(), DebugPayload.CATEGORIES.size() - 1))), 230);
            lines.box(new AABB(mob.x() - 0.3, mob.y(), mob.z() - 0.3, mob.x() + 0.3, mob.y() + 1.8, mob.z() + 0.3), color);
            labels.add(new Label(new Vec3(mob.x(), mob.y() + 2.1, mob.z()), mob.label(), color));
        }
        for (DebugPayload.Spot spot : frame.frontiers()) {
            Vec3 base = Vec3.atBottomCenterOf(spot.pos());
            lines.line(base, base.add(0, 2, 0), 0xC0FFFFFF);
            labels.add(new Label(base.add(0, 2.3, 0), spot.label(), 0xFFFFFFFF));
        }

        // Where it is going: goal, planned route, the route it walks, and what that route changes.
        for (DebugPayload.Goal goal : frame.goals()) {
            int color = 0xF0FFE020;
            if (goal.radius() > 0) lines.sphere(Vec3.atCenterOf(goal.pos()), goal.radius(), color);
            else if (goal.radius() < 0) lines.box(new AABB(goal.pos()).inflate(1.0), alpha(color, 140));
            else lines.box(new AABB(goal.pos()), color);
        }
        if (!frame.goals().isEmpty()) {
            labels.add(new Label(Vec3.atCenterOf(frame.goals().getFirst().pos()).add(0, 1.2, 0),
                frame.goals().size() == 1 ? "goal" : "goal (" + frame.goals().size() + " spots)", 0xFFFFE020));
        }
        path(lines, frame.planning(), 0, 0x90A0A0A0, 0x90A0A0A0);
        path(lines, frame.path(), frame.pathIndex(), 0x7020A040, 0xFF30FF50);
        for (BlockPos pos : frame.toBreak()) lines.box(new AABB(pos).deflate(0.08), 0xE0FF3030);
        for (BlockPos pos : frame.toPlace()) lines.box(new AABB(pos).deflate(0.08), 0xE03060FF);
        if (frame.clearing() != null) {
            lines.box(new AABB(frame.clearing()).inflate(0.03), 0xFFFF9020);
            labels.add(new Label(Vec3.atCenterOf(frame.clearing()).add(0, 0.9, 0), "clearing", 0xFFFF9020));
        }
        if (frame.target() != null) {
            lines.box(new AABB(frame.target()).inflate(0.03), 0xFFFF2020);
            lines.box(new AABB(frame.target()).inflate(0.06), 0xFFFF2020);
            labels.add(new Label(Vec3.atCenterOf(frame.target()).add(0, 0.9, 0), "target", 0xFFFF4040));
        }

        // The head: where it looks, its field of view, and the threat it reacts to.
        Entity body = minecraft.level.getEntity(frame.entityId());
        if (body != null) {
            Vec3 eye = body.getEyePosition(partial);
            Vec3 look = body.getViewVector(partial);
            lines.line(eye, eye.add(look.scale(6)), 0xFFFFFFFF);
            fieldOfView(lines, eye, body.getViewXRot(partial), body.getViewYRot(partial),
                frame.viewYawHalf(), frame.viewPitchHalf(), Math.min(frame.viewRange(), 10));
            if (frame.dangerId() >= 0) {
                Entity danger = minecraft.level.getEntity(frame.dangerId());
                if (danger != null) {
                    lines.line(eye, danger.getEyePosition(partial), 0xFFFF2020);
                    labels.add(new Label(danger.getEyePosition(partial).add(0, 0.8, 0), "THREAT", 0xFFFF2020));
                }
            }
            labels.add(new Label(eye.add(0, 0.9, 0), frame.name() + ": " + shorten(frame.status().size() > 1 ? frame.status().get(1) : "", 60), 0xFFFFFFFF));
        }
    }

    private static void path(Lines lines, List<BlockPos> path, int index, int walked, int ahead) {
        for (int i = 0; i + 1 < path.size(); i++) {
            Vec3 from = Vec3.atBottomCenterOf(path.get(i)).add(0, 0.1, 0);
            Vec3 to = Vec3.atBottomCenterOf(path.get(i + 1)).add(0, 0.1, 0);
            lines.line(from, to, i < index ? walked : ahead);
        }
        for (int i = Math.max(0, index); i < path.size(); i++) {
            Vec3 node = Vec3.atBottomCenterOf(path.get(i)).add(0, 0.1, 0);
            lines.box(new AABB(node, node).inflate(0.08, 0.02, 0.08), ahead);
        }
    }

    /** The same frustum the vision sensor samples: yaw/pitch half-angles around the head direction. */
    private static void fieldOfView(Lines lines, Vec3 eye, float pitch, float yaw, float halfYaw, float halfPitch, double length) {
        Vec3 forward = Vec3.directionFromRotation(pitch, yaw);
        Vec3 right = Vec3.directionFromRotation(0, yaw + 90);
        Vec3 up = right.cross(forward);
        double u = Math.tan(Math.toRadians(halfYaw)), v = Math.tan(Math.toRadians(halfPitch));
        Vec3[] corners = new Vec3[4];
        int index = 0;
        for (int sy : new int[] {-1, 1}) for (int sx : new int[] {-1, 1}) {
            Vec3 direction = forward.add(right.scale(sx * u)).add(up.scale(sy * v)).normalize();
            corners[index++] = eye.add(direction.scale(length));
        }
        int color = 0x8040E0FF;
        for (Vec3 corner : corners) lines.line(eye, corner, color);
        lines.line(corners[0], corners[1], color);
        lines.line(corners[1], corners[3], color);
        lines.line(corners[3], corners[2], color);
        lines.line(corners[2], corners[0], color);
    }

    private static void labels(List<Label> labels, Minecraft minecraft, Camera camera) {
        Font font = minecraft.font;
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        Vec3 cam = camera.getPosition();
        PoseStack pose = new PoseStack();
        for (Label label : labels) {
            double distance = label.at().distanceTo(cam);
            if (distance > 96) continue;
            pose.pushPose();
            pose.translate(label.at().x - cam.x, label.at().y - cam.y, label.at().z - cam.z);
            pose.mulPose(camera.rotation());
            // Grow with distance so far labels stay readable.
            float scale = 0.025F * (float) Math.max(1.0, distance / 12.0);
            pose.scale(scale, -scale, scale);
            Component text = Component.literal(label.text());
            font.drawInBatch(text, -font.width(text) / 2.0F, 0, label.color() | 0xFF000000, false, pose.last().pose(), buffers,
                Font.DisplayMode.SEE_THROUGH, 0x60000000, LightTexture.FULL_BRIGHT);
            pose.popPose();
        }
        buffers.endBatch();
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui) return;
        List<DebugClient.View> views = DebugClient.views();
        if (views.isEmpty()) return;
        DebugPayload frame = views.getLast().frame();
        GuiGraphics graphics = event.getGuiGraphics();
        Font font = minecraft.font;
        List<Row> rows = new ArrayList<>();
        for (String line : frame.status()) rows.add(new Row(line, 0xFFFFFFFF));
        rows.add(new Row("why (newest last):", 0xFFAAAAAA));
        for (DebugPayload.Line line : frame.trace()) {
            String age = line.ageTicks() < 20 ? "now" : line.ageTicks() / 20 + "s";
            rows.add(new Row(age + " " + line.source() + ": " + line.text() + (line.repeats() > 1 ? " x" + line.repeats() : ""),
                sourceColor(line.source())));
        }
        int x = 4, y = 4, width = Math.min(PANEL_WIDTH, graphics.guiWidth() - 8);
        List<Row> wrapped = new ArrayList<>();
        for (Row row : rows) {
            String rest = row.text();
            int guard = 0;
            while (!rest.isEmpty() && guard++ < 4) {
                String part = font.plainSubstrByWidth(rest, width - 6);
                if (part.isEmpty()) break;
                wrapped.add(new Row(guard == 1 ? part : "  " + part, row.color()));
                rest = rest.substring(part.length()).stripLeading();
            }
        }
        int height = wrapped.size() * (font.lineHeight + 1) + 6;
        graphics.fill(x, y, x + width, y + height, 0x90000000);
        int line = y + 3;
        for (Row row : wrapped) {
            graphics.drawString(font, row.text(), x + 3, line, row.color(), false);
            line += font.lineHeight + 1;
        }
    }

    private record Row(String text, int color) {}

    private static String shorten(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 3) + "...";
    }

    private static int alpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private static final Map<String, Integer> COLORS = Map.ofEntries(
        Map.entry("log", 0xFFA0703C), Map.entry("tree", 0xFFA0703C), Map.entry("leaves", 0xFF3CB371),
        Map.entry("ore", 0xFFFF4040), Map.entry("station", 0xFFC080FF), Map.entry("container", 0xFFC080FF),
        Map.entry("machine", 0xFFC080FF), Map.entry("stone", 0xFFA8A8A8), Map.entry("cliff", 0xFFA8A8A8),
        Map.entry("dirt", 0xFF8B5E3C), Map.entry("sand", 0xFFE8D898), Map.entry("gravel", 0xFF909090),
        Map.entry("crop", 0xFFC8E040), Map.entry("built", 0xFFFFA500), Map.entry("water", 0xFF3080FF),
        Map.entry("lava", 0xFFFF8000), Map.entry("dark", 0xFF7050B0), Map.entry("cave_mouth", 0xFF9060E0),
        Map.entry("hostile", 0xFFFF2020), Map.entry("passive", 0xFF40FF60), Map.entry("player", 0xFF40C0FF),
        Map.entry("item", 0xFFFFFF40));

    private static int color(String category) {
        return COLORS.getOrDefault(category, 0xFFDDDDDD);
    }

    private static int sourceColor(String source) {
        return switch (source) {
            case "task" -> 0xFFFFFFFF;
            case "nav", "path" -> 0xFF80D0FF;
            case "aim", "mine", "dig" -> 0xFFFFD060;
            case "gather", "obtain", "mine_ore", "explore", "skill", "craft" -> 0xFF90FF90;
            case "danger", "lag" -> 0xFFFF6060;
            case "tool" -> 0xFFB0B0B0;
            default -> 0xFFFF90FF; // brain: planner, actor, plan, step, critic, say
        };
    }

    /** Line segments in camera-relative coordinates, with the per-segment normal the line shader needs. */
    private record Lines(BufferBuilder buffer, Vec3 cam) {
        void line(Vec3 from, Vec3 to, int argb) {
            Vec3 normal = to.subtract(from);
            if (normal.lengthSqr() < 1.0E-8) return;
            normal = normal.normalize();
            vertex(from, normal, argb);
            vertex(to, normal, argb);
        }

        private void vertex(Vec3 at, Vec3 normal, int argb) {
            buffer.addVertex((float) (at.x - cam.x), (float) (at.y - cam.y), (float) (at.z - cam.z))
                .setColor(argb >> 16 & 255, argb >> 8 & 255, argb & 255, argb >>> 24)
                .setNormal((float) normal.x, (float) normal.y, (float) normal.z);
        }

        void box(AABB box, int argb) {
            Vec3[] c = {
                new Vec3(box.minX, box.minY, box.minZ), new Vec3(box.maxX, box.minY, box.minZ),
                new Vec3(box.maxX, box.minY, box.maxZ), new Vec3(box.minX, box.minY, box.maxZ),
                new Vec3(box.minX, box.maxY, box.minZ), new Vec3(box.maxX, box.maxY, box.minZ),
                new Vec3(box.maxX, box.maxY, box.maxZ), new Vec3(box.minX, box.maxY, box.maxZ)};
            for (int i = 0; i < 4; i++) {
                line(c[i], c[(i + 1) % 4], argb);
                line(c[i + 4], c[(i + 1) % 4 + 4], argb);
                line(c[i], c[i + 4], argb);
            }
        }

        void sphere(Vec3 center, double radius, int argb) {
            int segments = 32;
            for (int i = 0; i < segments; i++) {
                double a = Math.PI * 2 * i / segments, b = Math.PI * 2 * (i + 1) / segments;
                line(center.add(Math.cos(a) * radius, 0, Math.sin(a) * radius), center.add(Math.cos(b) * radius, 0, Math.sin(b) * radius), argb);
                line(center.add(Math.cos(a) * radius, Math.sin(a) * radius, 0), center.add(Math.cos(b) * radius, Math.sin(b) * radius, 0), argb);
                line(center.add(0, Math.cos(a) * radius, Math.sin(a) * radius), center.add(0, Math.cos(b) * radius, Math.sin(b) * radius), argb);
            }
        }
    }
}
