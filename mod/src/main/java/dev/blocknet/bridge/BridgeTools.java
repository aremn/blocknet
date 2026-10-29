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
        add("inspect", "Resolve an observed object ref to its exact block and supported actions, without moving or opening it.", false, "ref");
        add("menu", "Read the currently open player menu: id, slots, cursor and synchronized data values (progress/energy where supplied by the menu). Open through use ref first. An open storage-network terminal adds a network section: exact stored amounts (craftable-only keys listed apart), or pending/disconnected/empty; filter narrows it, page continues a long list and fails when the network changed. Does not reveal closed storage.", false, "filter?", "page?");
        add("encode_pattern", "On an open pattern encoding terminal (use its ref first), encode a crafting pattern for an item from the game's own recipe, using a blank pattern from the inventory or terminal, and take the encoded pattern into the inventory.", false, "item");
        add("speak", "Internal (brain voice): play a 16-bit mono WAV, base64, from the bot through voice chat.", false, "wav");
        add("heard", "Internal (brain voice): show a player what the bot understood from their voice.", false, "player", "text");
        add("job", "Read a saved unfinished transfer job. action forget explicitly abandons its bookkeeping; real cargo stays in inventory. Only forget when the player asks to abandon/reconcile it.", false, "action?");
        add("menu_click", "Click a slot in the currently open menu through player code; menu_id guards stale screens. click: pickup, quick_move or button. Mouse button 0/1; button mode uses the menu button id. Wait three ticks between clicks; inspect afterward.", false, "menu_id", "slot", "button?", "click?");
        add("transfer", "Move ALL contents from source ref to destination ref through menu clicks and repeated trips. Keeps owned items. Success confirms source empty; failures report partial delivery. Use look stations or inspect to identify refs.", true, "source", "destination");
        add("look", "Return the scene; recent nearby looks use one frontal frame, fresh forces a 360 degree turn; optional focus: trees, ores, water, danger, built, stations, food.", true, "focus?", "fresh?");
        add("scene", "Read the remembered scene instantly, without turning; delta gives one line of changes.", false, "delta?", "goal?");
        add("map", "Read the map: a 9x9 grid of 16-block cells around you, known places (structures v#, caves k# with entrances) and creatures within 48 blocks (e#) at their live positions, even behind walls; optional focus: a type, cave, structure or ref. Ores are not on the map.", false, "focus?");
        add("locate", "Full perception: the nearest blocks of an id or #tag (ores, logs, chests, machines, anything) through walls in loaded chunks, and which containers or machines around hold an item, with coordinates. Read-only; radius default 96, max 192.", false, "what", "radius?");
        add("explore", "Walk through observed safe space toward a frontier, then look and report new objects.", true, "direction?", "distance?");
        add("knowledge", "Read ore heights, frequency, biome restrictions, harvest tool and loot from server data.", false, "item");
        add("wiki", "Read in-game guides/quests/tooltips; use before crafting unfamiliar modded items.", false, "query");
        add("explore_caves", "Explore a seen cave mouth or dark opening, follow safe observed frontiers, gather target wall ore and place owned torches in darkness; return along breadcrumbs after five minutes or enough drops. Without cave, a fresh look then the nearest map cave entrance; cave k# (from map) walks into that cave first.", true, "item", "count?", "cave?");
        add("mine_ore", "Descend to a known ore peak, bypass observed fluids, then mine a main tunnel and side branches until count or a ten minute budget; progress reports depth and actual drops.", true, "item", "count?");
        add("obtain", "Acquire a TOTAL count, including owned: inventory, remembered containers, supported crafting/smelting and survival sources; prepares needed tools. source world = mine/smelt/craft it yourself and never take from chests (use it for find/mine/добудь/накопай requests); any (default) may withdraw from remembered chests. Unsupported machine/network recipes return a blocker; plan and wiki explain dependencies.", true, "item", "count?", "source?");
        add("plan", "Plan recipes without acting; mode craft makes new items, get counts owned items.", false,
            "item", "count?", "mode?");
        add("come", "Walk near an online player in the same dimension.", true, "player");
        add("follow", "Follow a player until stopped or replaced by another task.", true, "player");
        add("give", "Walk next to a player and toss owned items to them; item or all, omitted count means all matching items. Does not obtain items.", true, "player", "item", "count?");
        add("drop", "Toss owned items where you stand; item or all, omitted count means all matching items.", true, "item", "count?");
        add("goto", "Walk to a ref: observed object (to the ground beside a building), map place v#/k# (cave entrance), creature e#, item i#, a player name; or exact block coordinates; never invent coordinates.", true, "ref?", "x?", "y?", "z?");
        JsonArray choices = new JsonArray();
        for (String[] keys : List.of(new String[] {"ref"}, new String[] {"x", "y", "z"})) {
            JsonObject choice = new JsonObject(); JsonArray required = new JsonArray();
            for (String key : keys) required.add(key);
            choice.add("required", required); choices.add(choice);
        }
        TOOLS.get("goto").params().add("oneOf", choices);
        add("descend", "Dig a safe 1-wide, 2-high staircase down to y (a player's mine shaft); optional cardinal direction, otherwise facing. Use when asked to dig down or make a mine; to get a resource use obtain. Requires a pickaxe for stone.", true, "y", "direction?");
        add("tunnel", "Dig a safe 1x2 tunnel at this level and report uncovered ores with refs. Use when asked to dig a tunnel or branch mine; to get a resource use obtain. Requires a pickaxe for stone.", true, "direction", "length");
        add("mine", "Walk to and mine one block, then collect its drops; needs a suitable tool.", true, "x", "y", "z");
        add("gather", "Harvest ONE observed thing: gather {ref} with a t#/s#/o# ref (or the player's pointed p#) and no count chops that whole tree or object, then stops. With count, or with logs/wood, stone/cobblestone, coal, iron, dirt, sand or an item id, gathers that many more. Remembered refs are revisited and reobserved; requires the right tool.", true, "item?", "ref?", "target?", "count?");
        add("use", "Walk to and right-click an observed ref or exact block; sneak bypasses block interactions.", true, "x?", "y?", "z?", "sneak?", "ref?");
        add("attack", "Hunt a creature by ref e# (from map or look), type or player name: chase its live position, fight with the best weapon, retreat when overwhelmed, then pick up its drops; reports killed (with drops) or lost.", true, "target");
        add("pickup", "Pick up dropped items within 24 blocks: an item ref i# (from map or look), an item id, or all when omitted. Kills already collect their own drops.", true, "item?", "ref?");
        add("eat", "Eat one inventory food through player use; omitted item selects safe nutritious food.", true, "item?");
        add("place", "Place an inventory block, or any item that goes on by right-click (cables, pipes, parts), at x y z; optional face: up/down/north/south/east/west. If x y z is already occupied (a cable) and the item is a part (terminal, bus), it is attached to that block's given face. Sneaks automatically.", true, "item", "x", "y", "z", "face?");
        add("craft", "Craft a count of NEW items; look for stations first and read missing-material hints.", true, "item", "count?");
        add("get", "Obtain at least count items, counting inventory and remembered sources.", true, "item", "count?");
        add("look-in", "Open a container by ref or coordinates, report its visible contents and close it. A storage-network terminal reports the network contents.", true, "x?", "y?", "z?", "ref?");
        add("deposit", "Put items into a container; filter is item id, #tag, loot (everything except your tools, weapons, armor and food: use it for 'put everything away'), all (literally everything) or junk; omitted count means all. A storage-network terminal takes them into the network; a full network reports what was moved.", true,
            "x?", "y?", "z?", "filter", "count?", "ref?");
        add("withdraw", "Take items from a container through menu clicks; omitted count means all matching items. Works on a storage-network terminal (exact count, network items only).", true,
            "x?", "y?", "z?", "filter", "count?", "ref?");
        add("autocraft", "Ask a storage network to craft items it has a pattern for, through its terminal (ref or x, y, z) like a player: the network plans and runs the job. Reports submitted or what the network is short of; withdraw the result later.", true,
            "x?", "y?", "z?", "item", "count?", "ref?");
        for (String name : List.of("use", "look-in", "deposit", "withdraw", "autocraft"))
            TOOLS.get(name).params().add("oneOf", choices.deepCopy());
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
            boolean number = List.of("x", "y", "z", "count", "distance", "length", "menu_id", "slot", "button").contains(key);
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
        args = args.deepCopy();
        // Models often name goto's ref "target"; accept it rather than failing the step.
        if (tool.name().equals("goto") && args.has("target") && !args.has("ref")) args.add("ref", args.remove("target"));
        for (String key : List.of("ref", "item", "target")) {
            if (args.has(key) && (tool.name().equals("goto") || tool.name().equals("gather"))) {
                var value = args.get(key);
                if (value.isJsonObject() && value.getAsJsonObject().has("ref")) value = value.getAsJsonObject().get("ref");
                if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString())
                    args.addProperty(key, normalizeRef(value.getAsString()));
            }
        }
        final JsonObject normalizedArgs = args;
        for (var key : tool.params().getAsJsonArray("required")) {
            if (!args.has(key.getAsString())) throw new IllegalArgumentException("Missing " + key.getAsString());
        }
        JsonObject properties = tool.params().getAsJsonObject("properties");
        normalizedArgs.entrySet().forEach(entry -> {
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
        if (tool.name().equals("menu")) { report.accept(dev.blocknet.control.MenuAccess.snapshot(bot,
            args.has("filter") ? word(args, "filter", null) : null, args.has("page") ? word(args, "page", null) : null)); return; }
        if (tool.name().equals("speak")) {
            report.accept(dev.blocknet.voice.VoiceOutput.speak(bot, java.util.Base64.getDecoder().decode(args.get("wav").getAsString())));
            return;
        }
        if (tool.name().equals("heard")) {
            var listener = bot.getServer().getPlayerList().getPlayerByName(args.get("player").getAsString());
            if (listener != null) listener.displayClientMessage(net.minecraft.network.chat.Component.literal(
                "[" + bot.getGameProfile().getName() + " heard] " + args.get("text").getAsString()).withStyle(net.minecraft.ChatFormatting.GRAY), false);
            report.accept(listener == null ? "player offline" : "shown");
            return;
        }
        if (tool.name().equals("encode_pattern")) { report.accept(dev.blocknet.control.MenuAccess.encodePattern(bot, word(args, "item", null))); return; }
        if (tool.name().equals("job")) {
            var memory=dev.blocknet.memory.JobMemory.of(bot);
            if(args.has("action")) {
                if(!args.get("action").getAsString().equals("forget"))throw new IllegalArgumentException("job action must be forget");
                memory.clearTransfer();report.accept("abandoned transfer bookkeeping; any carried cargo remains in inventory");
            } else report.accept(memory.transfer().isEmpty()?"no unfinished transfer":memory.transfer().toString());
            return;
        }
        if (tool.name().equals("menu_click")) {
            report.accept(dev.blocknet.control.MenuAccess.click(bot, args.get("menu_id").getAsInt(), args.get("slot").getAsInt(),
                args.has("button") ? args.get("button").getAsInt() : 0, args.has("click") ? args.get("click").getAsString() : "pickup")); return;
        }
        if (tool.name().equals("inspect")) {
            var hint=HintTargets.find(bot.getServer(),word(args,"ref",null));
            if(hint!=null) {
                report.accept("player hint from "+hint.player()+"; dimension "+hint.dimension()+"; block "+hint.position().toShortString()
                    +"; not eye evidence; use/look-in/withdraw/deposit/transfer can approach and verify it through player interactions");return;
            }
            var obj = bot.vision().resolve(word(args, "ref", null));
            if (obj == null) throw new IllegalArgumentException("Unknown observed ref; use look stations");
            report.accept(bot.controller().scene().object(obj) + "; block " + obj.nearest().toShortString()
                + (dev.blocknet.control.BlockParts.of(bot.level(), obj.nearest()).isEmpty() ? ""
                    : "; parts are on this one block: use/look-in it to open a terminal part; a click tries each part")
                + "; actions: goto, use" + (List.of("container", "machine", "station").contains(obj.category())
                    ? "; may open a menu: look-in (confirms slots), withdraw, deposit, transfer; menu support not yet verified" : ""));
            return;
        }
        if (tool.name().equals("transfer")) {
            String from = word(args, "source", null), to = word(args, "destination", null);
            bot.controller().transferContainers(refPosition(bot, from), refPosition(bot, to), from + " " + to, report);
            return;
        }
        if (List.of("use", "look-in", "deposit", "withdraw", "autocraft").contains(tool.name())) {
            boolean coords = args.has("x") && args.has("y") && args.has("z");
            if (args.has("ref") ? args.has("x") || args.has("y") || args.has("z") : !coords)
                throw new IllegalArgumentException("Expected ref OR x, y, z");
            var pos = args.has("ref") ? refPosition(bot, word(args, "ref", null))
                : new net.minecraft.core.BlockPos(args.get("x").getAsInt(), args.get("y").getAsInt(), args.get("z").getAsInt());
            switch (tool.name()) {
                case "use" -> bot.controller().use(pos, args.has("sneak") && args.get("sneak").getAsBoolean(), report);
                case "look-in" -> bot.controller().lookIn(pos, result -> report.accept(result + (result.startsWith("failed:") ? ""
                    : "; " + bot.containerMemory().text(bot.getServer().overworld().getGameTime(), null,
                        new dev.blocknet.memory.ContainerMemory.Location(bot.level().dimension().location().toString(), pos.asLong())))));
                case "deposit" -> bot.controller().deposit(pos, word(args, "filter", null), args.has("count") ? args.get("count").getAsInt() : 0, report);
                case "withdraw" -> bot.controller().withdraw(pos, word(args, "filter", null), args.has("count") ? args.get("count").getAsInt() : 0, report);
                case "autocraft" -> bot.controller().autocraft(pos, word(args, "item", null), args.has("count") ? args.get("count").getAsInt() : 1, report);
            }
            return;
        }
        if (tool.name().equals("attack")) { bot.controller().attack(word(args, "target", null), report); return; }
        if (tool.name().equals("pickup")) {
            bot.controller().pickup(args.has("ref") ? word(args, "ref", null) : args.has("item") ? word(args, "item", null) : "", report);
            return;
        }
        if (tool.name().equals("locate")) {
            report.accept(dev.blocknet.map.WorldFind.find(bot, word(args, "what", null), args.has("radius") ? args.get("radius").getAsInt() : 96));
            return;
        }
        if (tool.name().equals("map")) {
            report.accept(dev.blocknet.map.WorldMap.text(bot, args.has("focus") ? args.get("focus").getAsString() : ""));
            return;
        }
        if (tool.name().equals("wiki")) {
            report.accept(dev.blocknet.knowledge.Wiki.get(bot.getServer()).search(args.get("query").getAsString()));
            return;
        }
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
        if (tool.name().equals("give")) words.add(word(args, "player", null));
        if (tool.name().equals("gather")) {
            String target = null;
            for (var entry : args.entrySet()) {
                if (entry.getValue().getAsString().matches("[a-z][0-9]+")) target = entry.getValue().getAsString();
            }
            if (target == null) {
                for (String key : List.of("item", "target", "ref")) if (args.has(key)) { target = word(args, key, null); break; }
            }
            if (target == null || target.isEmpty()) throw new IllegalArgumentException("Missing item or ref");
            // "This tree" said while pointing: the player's hint becomes the observed object around that block.
            var hint = target.startsWith("p") ? HintTargets.find(bot.getServer(), target) : null;
            if (hint != null) {
                var around = bot.vision().objects().stream().filter(o -> o.bounds().inflate(0.5).contains(net.minecraft.world.phys.Vec3.atCenterOf(hint.position()))).findFirst();
                if (around.isEmpty()) throw new IllegalArgumentException("I have not seen what " + target + " points at yet; look at it first");
                target = around.get().ref();
            }
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
        for (String key : List.of("ref", "focus", "direction", "distance", "length", "cave", "source")) {
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

    public static String normalizeRef(String value) {
        String first = value.trim().replace("\"", "").split(",", 2)[0].trim();
        return first.matches("[a-z][0-9]+") ? first : value;
    }

    private static net.minecraft.core.BlockPos refPosition(BotPlayer bot, String ref) {
        var hint=HintTargets.find(bot.getServer(),ref);
        if(hint!=null) {
            if(!hint.dimension().equals(bot.level().dimension().location().toString()))throw new IllegalArgumentException("Player hint is in another dimension");
            return hint.position();
        }
        var object = bot.vision().resolve(ref);
        if (object == null) throw new IllegalArgumentException("Unknown observed ref " + ref + "; use look stations");
        var evidence = bot.worldMemory().observed().cell(bot.level().dimension().location().toString(), object.nearest());
        if (evidence == null || evidence.stateId() < 0)
            throw new IllegalArgumentException("Retired or unconfirmed ref " + ref + "; reobserve with look stations");
        return object.nearest();
    }

    private static String word(JsonObject args, String key, String fallback) {
        String value = args.has(key) ? args.get(key).getAsString() : fallback;
        if (value == null || !value.matches("[#a-zA-Z0-9_:.+/-]+")) {
            throw new IllegalArgumentException("Invalid " + key + "; use a single id, name or integer");
        }
        return value;
    }
}
