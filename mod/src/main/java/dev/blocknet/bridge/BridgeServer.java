package dev.blocknet.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.blocknet.Blocknet;
import dev.blocknet.body.BotManager;
import dev.blocknet.body.BotPlayer;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/** Local JSON-RPC lines transport. Only server.execute callbacks touch Minecraft state. */
public final class BridgeServer implements AutoCloseable {
    private static BridgeServer active;
    private static final Gson GSON = new Gson();
    private final MinecraftServer server;
    private final String token;
    private final ServerSocket listener;
    private final Set<Client> clients = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    /** Port zero is supported for isolated socket GameTests. */
    public BridgeServer(MinecraftServer server, int port, String token) throws IOException {
        this.server = server;
        this.token = token;
        listener = new ServerSocket();
        listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
        Thread accept = new Thread(this::accept, "blocknet-bridge-accept");
        accept.setDaemon(true);
        accept.start();
    }

    public int port() { return listener.getLocalPort(); }

    public static void onServerStarted(ServerStartedEvent event) {
        try {
            BridgeConfig config = BridgeConfig.load(FMLPaths.CONFIGDIR.get().resolve("blocknet_ai-bridge.json"));
            active = new BridgeServer(event.getServer(), config.port(), config.token());
            Blocknet.LOGGER.info("Blocknet bridge listening on 127.0.0.1:{}", active.port());
        } catch (java.net.BindException exception) {
            Blocknet.LOGGER.warn("Blocknet bridge port is already in use; continuing with local commands (tests use a separate free port)");
        } catch (IOException | RuntimeException exception) {
            Blocknet.LOGGER.error("Cannot start Blocknet bridge; local commands remain available", exception);
        }
    }

    public static void onServerStopping(ServerStoppingEvent event) {
        if (active != null) active.close();
        active = null;
    }

    public static void onChat(ServerChatEvent event) {
        if (active == null || event.getPlayer() instanceof BotPlayer) return;
        active.broadcast(object("type", "chat", "from", event.getPlayer().getGameProfile().getName(),
            "text", event.getRawText()));
    }

    public static void botsChanged() {
        if (active == null) return;
        JsonObject event = object("type", "bots.changed");
        var bots = new com.google.gson.JsonArray();
        BotManager.all().forEach(bot -> bots.add(bot.getGameProfile().getName()));
        event.add("bots", bots);
        active.broadcast(event);
    }

    public static void botSpoke(BotPlayer bot, String player) {
        if (active != null) active.broadcast(object("type", "bot.spoke", "bot", bot.getGameProfile().getName(), "player", player));
    }

    public static void moment(JsonObject event) {
        if (active != null) active.broadcast(event);
    }

    public static void botDied(BotPlayer bot) {
        if (active != null) active.broadcast(object("type", "bot.died", "bot", bot.getGameProfile().getName()));
    }

    private void accept() {
        while (!closed) {
            try {
                Client client = new Client(listener.accept());
                clients.add(client);
                Thread reader = new Thread(client::read, "blocknet-bridge-reader");
                reader.setDaemon(true);
                reader.start();
            } catch (IOException exception) {
                if (!closed) Blocknet.LOGGER.warn("Bridge accept failed", exception);
            }
        }
    }

    private void broadcast(JsonObject event) {
        clients.forEach(client -> { if (client.authenticated) client.event(event); });
    }

    @Override
    public void close() {
        closed = true;
        try { listener.close(); } catch (IOException ignored) { }
        clients.forEach(Client::close);
    }

    private final class Client {
        private final Socket socket;
        private final BufferedWriter writer;
        private final Map<String, Map<String, Long>> baselines = new HashMap<>();
        private volatile boolean authenticated;

        private Client(Socket socket) throws IOException {
            this.socket = socket;
            writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        }

        private void read() {
            try (var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    try {
                        JsonObject request = JsonParser.parseString(line).getAsJsonObject();
                        server.execute(() -> handle(request));
                    } catch (RuntimeException exception) {
                        error(JsonNull.INSTANCE, -32700, "Invalid JSON object");
                    }
                }
            } catch (IOException ignored) {
                // Reconnection does not cancel the bot's task.
            } finally {
                close();
            }
        }

        private void handle(JsonObject request) {
            JsonElement id = request.has("id") ? request.get("id") : JsonNull.INSTANCE;
            try {
                if (!request.has("jsonrpc") || !request.get("jsonrpc").getAsString().equals("2.0")) {
                    error(id, -32600, "Expected JSON-RPC 2.0");
                    return;
                }
                String method = request.get("method").getAsString();
                JsonObject params = request.has("params") ? request.getAsJsonObject("params") : new JsonObject();
                if (method.equals("hello")) {
                    if (params.get("protocol").getAsInt() != 1 || !token.equals(params.get("token").getAsString())) {
                        error(id, -32001, "Invalid protocol or token");
                        return;
                    }
                    authenticated = true;
                    JsonObject hello = new JsonObject();
                    hello.addProperty("protocol", 1);
                    hello.add("bots", GSON.toJsonTree(BotManager.all().stream().map(b -> b.getGameProfile().getName()).toList()));
                    hello.add("tools", BridgeTools.list());
                    reply(id, hello);
                    return;
                }
                if (!authenticated) {
                    error(id, -32001, "Valid hello required");
                    return;
                }
                if (!method.equals("tool.call")) {
                    error(id, -32601, "Unknown method");
                    return;
                }
                BotPlayer bot = BotManager.get(params.get("bot").getAsString());
                if (bot == null) throw new IllegalArgumentException("No bot with that name; spawn it with /blocknet spawn");
                BridgeTools.Tool tool = BridgeTools.get(params.get("tool").getAsString());
                JsonObject args = params.has("args") ? params.getAsJsonObject("args") : new JsonObject();
                if (tool.task()) {
                    String taskId = UUID.randomUUID().toString();
                    reply(id, object("task_id", taskId));
                    AtomicBoolean reported = new AtomicBoolean();
                    java.util.function.Consumer<String> finish = result -> {
                        if (reported.compareAndSet(false, true)) {
                            // All authenticated connections receive outcomes (main brain and MCP process).
                            JsonObject event = object("type", "task.finished", "bot", bot.getGameProfile().getName(),
                                "task_id", taskId, "result", oneLine(result));
                            broadcast(event);
                        }
                    };
                    try {
                        BridgeTools.run(bot, tool, args, baselines, finish);
                    } catch (RuntimeException exception) {
                        finish.accept("failed: " + exception.getMessage());
                    }
                } else {
                    var lines = new ArrayList<String>();
                    BridgeTools.run(bot, tool, args, baselines, lines::add);
                    reply(id, object("result", oneLine(String.join("; ", lines))));
                }
            } catch (RuntimeException exception) {
                error(id, -32602, "Invalid call: " + exception.getMessage());
            }
        }

        private void event(JsonObject params) {
            JsonObject message = object("jsonrpc", "2.0", "method", "event");
            message.add("params", params);
            send(message);
        }

        private void reply(JsonElement id, JsonObject result) {
            JsonObject message = object("jsonrpc", "2.0");
            message.add("id", id);
            message.add("result", result);
            send(message);
        }

        private void error(JsonElement id, int code, String text) {
            JsonObject message = object("jsonrpc", "2.0");
            message.add("id", id);
            JsonObject error = object("message", text);
            error.addProperty("code", code);
            message.add("error", error);
            send(message);
        }

        private synchronized void send(JsonObject message) {
            try {
                writer.write(GSON.toJson(message));
                writer.newLine();
                writer.flush();
            } catch (IOException exception) {
                close();
            }
        }

        private void close() {
            clients.remove(this);
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    private static String oneLine(String text) { return text.replaceAll("[\\r\\n]+", "; "); }

    private static JsonObject object(String... pairs) {
        JsonObject result = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) result.addProperty(pairs[i], pairs[i + 1]);
        return result;
    }
}
