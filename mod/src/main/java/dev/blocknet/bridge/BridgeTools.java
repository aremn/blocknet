package dev.blocknet.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.blocknet.body.BotPlayer;
import dev.blocknet.command.BlocknetCommands;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.commands.CommandSource;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** One tool registry; the command dispatcher owns parsing, actions and their existing reports. */
public final class BridgeTools {
    public record Tool(String name, String description, boolean task, JsonObject params) {}
    private static final Map<String, Tool> TOOLS = new LinkedHashMap<>();

    static {
        add("status", "Read health, position and current task.", false);
        add("inv", "Read inventory and tool, food and space hints.", false);
        add("changes", "Read state changes since this connection's last changes call.", false);
        add("memory", "Read remembered container contents; optionally filter items.", false, "filter?");
        add("look", "Look around and remember visible stations and containers.", true);
        add("plan", "Plan recipes without acting; mode craft makes new items, get counts owned items.", false,
            "item", "count?", "mode?");
        add("come", "Walk near an online player in the same dimension.", true, "player");
        add("follow", "Follow a player until stopped or replaced by another task.", true, "player");
        add("goto", "Walk to exact block coordinates.", true, "x", "y", "z");
        add("mine", "Walk to and mine one block, then collect its drops; needs a suitable tool.", true, "x", "y", "z");
        add("use", "Walk to and right-click one block through player interaction.", true, "x", "y", "z");
        add("craft", "Craft a count of NEW items; look for stations first and read missing-material hints.", true, "item", "count?");
        add("get", "Obtain at least count items, counting inventory and remembered sources.", true, "item", "count?");
        add("look-in", "Open a container, remember its visible menu slots and close it.", true, "x", "y", "z");
        add("deposit", "Put items into a container; filter is item id, #tag, all or junk; omitted count means all.", true,
            "x", "y", "z", "filter", "count?");
        add("withdraw", "Take items from a container through menu clicks; omitted count means all matching items.", true,
            "x", "y", "z", "filter", "count?");
        add("stop", "Cancel the current action immediately.", false);
        add("say", "Say one short message to all players as this bot.", false, "text");
    }

    private BridgeTools() {}

    private static void add(String name, String description, boolean task, String... fields) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject properties = new JsonObject();
        JsonArray required = new JsonArray();
        for (String field : fields) {
            boolean optional = field.endsWith("?");
            String key = optional ? field.substring(0, field.length() - 1) : field;
            JsonObject property = new JsonObject();
            boolean number = List.of("x", "y", "z", "count").contains(key);
            property.addProperty("type", number ? "integer" : "string");
            if (key.equals("count")) {
                property.addProperty("minimum", 1);
                property.addProperty("maximum", 1000000);
            }
            if (key.equals("mode")) {
                JsonArray values = new JsonArray();
                values.add("craft");
                values.add("get");
                property.add("enum", values);
            }
            properties.add(key, property);
            if (!optional) required.add(key);
        }
        schema.add("properties", properties);
        schema.add("required", required);
        schema.addProperty("additionalProperties", false);
        TOOLS.put(name, new Tool(name, description, task, schema));
    }

    public static Tool get(String name) {
        Tool tool = TOOLS.get(name);
        if (tool == null) throw new IllegalArgumentException("Unknown tool: " + name);
        return tool;
    }

    public static JsonArray list() {
        JsonArray result = new JsonArray();
        TOOLS.values().forEach(tool -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", tool.name());
            entry.addProperty("description", tool.description());
            entry.add("params", tool.params());
            result.add(entry);
        });
        return result;
    }

    public static void run(BotPlayer bot, Tool tool, JsonObject args,
            Map<String, Map<String, Long>> baselines, Consumer<String> report) {
        for (var key : tool.params().getAsJsonArray("required")) {
            if (!args.has(key.getAsString())) throw new IllegalArgumentException("Missing " + key.getAsString());
        }
        JsonObject properties = tool.params().getAsJsonObject("properties");
        args.entrySet().forEach(entry -> {
            String key = entry.getKey();
            var value = entry.getValue();
            if (!properties.has(key) || !value.isJsonPrimitive()) {
                throw new IllegalArgumentException("Invalid parameter: " + key);
            }
            var schema = properties.getAsJsonObject(key);
            var primitive = value.getAsJsonPrimitive();
            if (schema.get("type").getAsString().equals("integer")) {
                if (!primitive.isNumber() || !value.getAsString().matches("-?[0-9]+")) {
                    throw new IllegalArgumentException(key + " must be an integer");
                }
                long number = value.getAsLong();
                if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE
                        || key.equals("count") && (number < 1 || number > 1000000)) {
                    throw new IllegalArgumentException(key + " is out of range");
                }
            } else if (!primitive.isString()) {
                throw new IllegalArgumentException(key + " must be a string");
            }
            if (key.equals("mode") && !List.of("craft", "get").contains(value.getAsString())) {
                throw new IllegalArgumentException("mode must be craft or get");
            }
        });
        String name = bot.getGameProfile().getName();
        if (tool.name().equals("say")) {
            String text = args.get("text").getAsString().replaceAll("[\\r\\n]+", " ");
            bot.getServer().getPlayerList().broadcastSystemMessage(Component.literal("<" + name + "> " + text), false);
            report.accept("said");
            return;
        }
        if (tool.name().equals("changes")) {
            var delta = bot.bodyState().since(baselines.getOrDefault(name, Map.of()));
            baselines.put(name, bot.bodyState().snapshot().revisions());
            BlocknetCommands.reportChanges(delta, report);
            return;
        }
        List<String> words = new ArrayList<>(List.of("blocknet", tool.name()));
        if (tool.name().equals("plan")) words.add(word(args, "mode", "craft"));
        words.add(name);
        var source = bot.getServer().createCommandSourceStack().withEntity(bot)
            .withLevel(bot.serverLevel()).withPosition(bot.position()).withPermission(2);
        if (tool.name().equals("come") || tool.name().equals("follow")) {
            ServerPlayer player = bot.getServer().getPlayerList().getPlayerByName(word(args, "player", null));
            if (player == null || player instanceof BotPlayer) throw new IllegalArgumentException("Player not online");
            if (player.serverLevel() != bot.serverLevel()) throw new IllegalArgumentException("Player is in another dimension");
            source = source.withEntity(player).withPosition(player.position());
        }
        if (args.has("x")) {
            for (String axis : List.of("x", "y", "z")) words.add(word(args, axis, null));
        }
        for (String key : List.of("item", "filter", "count")) {
            if (args.has(key)) words.add(word(args, key, null));
        }
        CommandSource output = new CommandSource() {
            @Override public void sendSystemMessage(Component message) { report.accept(message.getString()); }
            @Override public boolean acceptsSuccess() { return true; }
            @Override public boolean acceptsFailure() { return true; }
            @Override public boolean shouldInformAdmins() { return false; }
        };
        bot.getServer().getCommands().performPrefixedCommand(source.withSource(output), String.join(" ", words));
        if (tool.name().equals("stop")) report.accept("stopped");
    }

    private static String word(JsonObject args, String key, String fallback) {
        String value = args.has(key) ? args.get(key).getAsString() : fallback;
        if (value == null || !value.matches("[#a-zA-Z0-9_:.+/-]+")) {
            throw new IllegalArgumentException("Invalid " + key + "; use a single id, name or integer");
        }
        return value;
    }
}
