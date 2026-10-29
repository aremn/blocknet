package dev.blocknet;

import com.mojang.logging.LogUtils;
import dev.blocknet.body.BotManager;
import dev.blocknet.bridge.BridgeServer;
import dev.blocknet.command.BlocknetCommands;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * Entry point of the Blocknet body mod.
 *
 * <p>The body is a server-side mod: the bot is a real {@code ServerPlayer}, so clients do not need
 * this mod installed. The "brain" (LLM agent) runs as a separate process, see docs/ARCHITECTURE.md.
 */
@Mod(Blocknet.MOD_ID)
public final class Blocknet {
    public static final String MOD_ID = "blocknet_ai";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Blocknet(IEventBus modEventBus) {
        NeoForge.EVENT_BUS.addListener(BlocknetCommands::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(BotManager::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(BotManager::onServerStopping);
        NeoForge.EVENT_BUS.addListener(dev.blocknet.knowledge.Knowledge::started);
        NeoForge.EVENT_BUS.addListener(dev.blocknet.knowledge.Knowledge::reload);
        NeoForge.EVENT_BUS.addListener(dev.blocknet.knowledge.Wiki::started);
        NeoForge.EVENT_BUS.addListener(dev.blocknet.knowledge.Wiki::reload);
        NeoForge.EVENT_BUS.addListener(BridgeServer::onServerStarted);
        NeoForge.EVENT_BUS.addListener(BridgeServer::onServerStopping);
        NeoForge.EVENT_BUS.addListener(BridgeServer::onChat);
        NeoForge.EVENT_BUS.addListener(dev.blocknet.vision.OpacityTable::reload);
        NeoForge.EVENT_BUS.addListener(dev.blocknet.debug.DebugWatch::onServerTick);
        NeoForge.EVENT_BUS.addListener(dev.blocknet.map.MapScanner::onServerTick);
        NeoForge.EVENT_BUS.addListener(dev.blocknet.voice.VoiceInput::onServerTick);
        NeoForge.EVENT_BUS.addListener(dev.blocknet.debug.DebugWatch::onLoggedOut);
        modEventBus.addListener(dev.blocknet.debug.DebugWatch::register);
        dev.blocknet.debug.PathTrace.install();
        dev.blocknet.compat.Compat.init();
        LOGGER.info("Blocknet body loaded");
    }
}
