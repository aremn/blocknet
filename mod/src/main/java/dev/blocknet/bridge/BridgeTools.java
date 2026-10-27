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
        add("look", "Return the scene; recent nearby looks use one frontal frame, fresh forces a 360 degree turn; optional focus: trees, ores, water, danger, built, stations.", true, "focus?", "fresh?");
        add("scene", "Read the remembered scene instantly, without turning; delta gives one line of changes.", false, "delta?", "goal?");
        add("explore", "Walk through observed safe space toward a frontier, then look and report new objects.", true, "direction?", "distance?");
        add("plan", "Plan recipes without acting; mode craft makes new items, get counts owned items.", false,
            "item", "count?", "mode?");
        add("come", "Walk near an online player in the same dimension.", true, "player");
        add("follow", "Follow a player until stopped or replaced by another task.", true, "player");
        add("goto", "Walk to an observed object ref, or supplied exact block coordinates; never invent coordinates.", true, "ref?", "x?", "y?", "z?");
        JsonArray choices = new JsonArray();
        for (String[] keys : List.of(new String[] {"ref"}, new String[] {"x", "y", "z"})) {
            JsonObject choice = new JsonObject(); JsonArray required = new JsonArray();
            for (String key : keys) required.add(key);
            choice.add("required", required); choices.add(choice);
        }
        TOOLS.get("goto").params().add("oneOf", choices);
        add("descend", "Dig a safe 1-wide, 2-high staircase down to y; optional cardinal direction, otherwise facing. Requires a pickaxe for stone.", true, "y", "direction?");
        add("tunnel", "Dig a safe 1x2 tunnel at this level and report uncovered ores with refs. Requires a pickaxe for stone.", true, "direction", "length");
        add("mine", "Walk to and mine one block, then collect its drops; needs a suitable tool.", true, "x", "y", "z");
        add("gather", "Gather MORE items from an observed ref or logs/wood, stone/cobblestone, coal, iron, dirt, sand or exact item id; requires the right tool.", true, "item?", "ref?", "target?", "count?");
        add("use", "Walk to and right-click one block; sneak bypasses block interactions.", true, "x", "y", "z", "sneak?");
        add("eat", "Eat one inventory food through player use; omitted item selects safe nutritious food.", true, "item?");
        add("place", "Place an inventory block at x y z; optional clicked face: up/down/north/south/east/west. Sneaks automatically.", true, "item", "x", "y", "z", "face?");
        add("craft", "Craft a count of NEW items; look for stations first and read missing-material hints.", true, "item", "count?");
        add("get", "Obtain at least count items, counting inventory and remembered sources.", true, "item", "count?");
        add("look-in", "Open a container, remember its visible menu slots and close it.", true, "x", "y", "z");
        add("deposit", "Put items into a container; filter is item id, #tag, all or junk; omitted count means all.", true,
            "x", "y", "z", "filter", "count?");
        add("withdraw", "Take items from a container through menu clicks; omitted count means all matching items.", true,
            "x", "y", "z", "filter", "count?");
        add("stop", "Cancel the current action immediately.", false);
        add("say", "Say one short message to all players as this bot.", false, "text", "player?");
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
            boolean number = List.of("x", "y", "z", "count", "distance", "length").contains(key);
            property.addProperty("type", number ? "integer" : List.of("sneak", "delta", "fresh").contains(key) ? "boolean" : "string");
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
            } else if (List.of("sneak", "delta", "fresh").contains(key)) {
                if (!primitive.isBoolean()) throw new IllegalArgumentException("sneak must be boolean");
            } else if (!primitive.isString()) {
                throw new IllegalArgumentException(key + " must be a string");
            }
            if (key.equals("mode") && !List.of("craft", "get").contains(value.getAsString())) {
                throw new IllegalArgumentException("mode must be craft or get");
            }
        });
        if (tool.name().equals("goto") && (args.has("ref") == (args.has("x") || args.has("y") || args.has("z"))
                || !args.has("ref") && !(args.has("x") && args.has("y") && args.has("z")))) {
            throw new IllegalArgumentException("goto needs ref OR x, y, z");
        }
        if (tool.name().equals("scene") && args.has("goal")) bot.controller().scene().goal(args.get("goal").getAsString());
        String name = bot.getGameProfile().getName();
        if (tool.name().equals("say")) {
            String text = args.get("text").getAsString().replaceAll("[\\r\\n]+", " ");
            bot.getServer().getPlayerList().broadcastSystemMessage(Component.literal("<" + name + "> " + text), false);
            BridgeServer.botSpoke(bot, args.has("player") ? word(args, "player", null) : "");
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
        if (tool.name().equals("place")) words.add(word(args, "item", null));
        if (tool.name().equals("gather")) {
            String target = null;
            for (var entry : args.entrySet()) {
                if (entry.getValue().getAsString().matches("[a-z][0-9]+")) target = entry.getValue().getAsString();
            }
            if (target == null) {
                for (String key : List.of("item", "target", "ref")) if (args.has(key)) { target = word(args, key, null); break; }
            }
            if (target == null || target.isEmpty()) throw new IllegalArgumentException("Missing item or ref");
            words.add(target);
            if (args.has("count")) words.add(word(args, "count", null));
        }
        if (tool.name().equals("descend")) words.add(word(args, "y", null));
        if (args.has("x")) {
            for (String axis : List.of("x", "y", "z")) words.add(word(args, axis, null));
        }
        for (String key : List.of("item", "filter", "count")) {
            if (!tool.name().equals("gather") && args.has(key) && !(key.equals("item") && tool.name().equals("place"))) words.add(word(args, key, null));
        }
        for (String key : List.of("ref", "focus", "direction", "distance", "length")) {
            if (args.has(key) && !tool.name().equals("gather")) words.add(word(args, key, null));
        }
        if (tool.name().equals("explore") && args.has("distance") && !args.has("direction")) words.add(words.size() - 1, "auto");
        if (args.has("fresh") && args.get("fresh").getAsBoolean()) words.add("fresh");
        if (args.has("delta") && args.get("delta").getAsBoolean()) words.add("delta");
        if (args.has("face")) words.add(word(args, "face", null));
        if (args.has("sneak") && args.get("sneak").getAsBoolean()) words.add("sneak");
        bot.moments().tool(tool.name(), args);
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
