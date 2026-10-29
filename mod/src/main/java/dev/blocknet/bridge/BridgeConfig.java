package dev.blocknet.bridge;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import javax.annotation.Nullable;
import net.neoforged.fml.loading.FMLPaths;

/**
 * Local bridge credentials, created once and never logged. {@code brain} (the brain directory with
 * brain.js) and {@code node} (the Node executable, default "node") are optional: with {@code brain}
 * set, {@code /blocknet spawn} starts a brain for the new bot. {@code perception} is "full" (the
 * default: the bot knows blocks through walls, ADR-0012) or "assisted" (ores only by sight, ADR-0011).
 */
public record BridgeConfig(int port, String token, @Nullable String brain, @Nullable String node, @Nullable String perception) {
    /** Full perception unless the config asks for "assisted"; a broken config keeps the default. */
    public static boolean fullPerception() {
        try {
            return !"assisted".equalsIgnoreCase(load(path()).perception());
        } catch (IOException | RuntimeException exception) {
            return true;
        }
    }

    public static Path path() {
        return FMLPaths.CONFIGDIR.get().resolve("blocknet_ai-bridge.json");
    }

    public static BridgeConfig load(Path path) throws IOException {
        Gson gson = new Gson();
        if (Files.exists(path)) {
            BridgeConfig config = gson.fromJson(Files.readString(path), BridgeConfig.class);
            if (config == null || config.port < 1 || config.port > 65535
                    || config.token == null || config.token.isBlank()) {
                throw new IOException("Invalid bridge config: " + path);
            }
            return config;
        }
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        BridgeConfig config = new BridgeConfig(25599, HexFormat.of().formatHex(secret), null, null, null);
        Files.createDirectories(path.toAbsolutePath().getParent());
        Files.writeString(path, gson.toJson(config) + "\n");
        return config;
    }
}
