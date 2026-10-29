package dev.blocknet.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.blocknet.Blocknet;
import java.util.List;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

/**
 * Optional client side: a radial menu for giving the nearest bot orders. The body works without it;
 * the menu only sends the same {@code /blocknet} commands a player could type.
 */
public final class BlocknetClient {
    /** Hold to open the radial menu; the mouse wheel button by default. */
    public static final KeyMapping RADIAL_MENU = new KeyMapping(
        "key.blocknet_ai.radial_menu",
        KeyConflictContext.IN_GAME,
        InputConstants.Type.MOUSE,
        GLFW.GLFW_MOUSE_BUTTON_MIDDLE,
        "key.categories.blocknet_ai"
    );

    private BlocknetClient() {}

    @EventBusSubscriber(modid = Blocknet.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ModEvents {
        private ModEvents() {}

        @SubscribeEvent
        public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
            event.register(RADIAL_MENU);
        }
    }

    @EventBusSubscriber(modid = Blocknet.MOD_ID, value = Dist.CLIENT)
    public static final class GameEvents {
        private GameEvents() {}

        @SubscribeEvent
        public static void onClientTick(ClientTickEvent.Post event) {
            Minecraft minecraft = Minecraft.getInstance();
            while (RADIAL_MENU.consumeClick()) {
                if (minecraft.screen == null && minecraft.player != null) {
                    minecraft.setScreen(new RadialMenuScreen(botActions(minecraft), RADIAL_MENU));
                }
            }
        }

        /** The wheel button is also vanilla's pick block; while the menu owns it, pick block stays off. */
        @SubscribeEvent
        public static void onInteractionKey(InputEvent.InteractionKeyMappingTriggered event) {
            if (event.isPickBlock() && RADIAL_MENU.same(Minecraft.getInstance().options.keyPickItem)) {
                event.setCanceled(true);
                event.setSwingHand(false);
            }
        }
    }

    /** Orders for the bot nearest to the player. */
    private static List<RadialMenuScreen.Action> botActions(Minecraft minecraft) {
        // The block under the crosshair is taken when the menu opens, before the cursor moves.
        HitResult hit = minecraft.hitResult;
        BlockPos lookedAt = hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK ? blockHit.getBlockPos() : null;
        return List.of(
            new RadialMenuScreen.Action(Component.translatable("blocknet_ai.radial.come"), () -> sendCommand(minecraft, "blocknet come")),
            new RadialMenuScreen.Action(Component.translatable("blocknet_ai.radial.follow"), () -> sendCommand(minecraft, "blocknet follow")),
            new RadialMenuScreen.Action(Component.translatable("blocknet_ai.radial.stop"), () -> sendCommand(minecraft, "blocknet stop")),
            new RadialMenuScreen.Action(Component.translatable("blocknet_ai.radial.mine_this"), () -> {
                if (lookedAt == null) {
                    minecraft.gui.setOverlayMessage(Component.translatable("blocknet_ai.radial.no_block"), false);
                    return;
                }
                sendCommand(minecraft, "blocknet mine " + lookedAt.getX() + " " + lookedAt.getY() + " " + lookedAt.getZ());
            }),
            new RadialMenuScreen.Action(Component.translatable("blocknet_ai.radial.next_voice"), () -> sendCommand(minecraft, "blocknet voice next"))
        );
    }

    private static void sendCommand(Minecraft minecraft, String command) {
        LocalPlayer player = minecraft.player;
        if (player != null) {
            player.connection.sendCommand(command);
        }
    }
}
