package dev.blocknet.bridge;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;

/** Local bridge credentials, created once and never logged. */
public record BridgeConfig(int port, String token) {
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
        BridgeConfig config = new BridgeConfig(25599, HexFormat.of().formatHex(secret));
        Files.createDirectories(path.toAbsolutePath().getParent());
        Files.writeString(path, gson.toJson(config) + "\n");
        return config;
    }
}
