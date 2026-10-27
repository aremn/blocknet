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
        NeoForge.EVENT_BUS.addListener(BridgeServer::onServerStarted);
        NeoForge.EVENT_BUS.addListener(BridgeServer::onServerStopping);
        NeoForge.EVENT_BUS.addListener(BridgeServer::onChat);
        LOGGER.info("Blocknet body loaded");
    }
}
