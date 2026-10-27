package dev.blocknet.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.blocknet.body.BotManager;
import dev.blocknet.body.BotPlayer;
import dev.blocknet.crafting.CraftPlan;
import dev.blocknet.crafting.CraftingCalculation;
import dev.blocknet.control.CraftStations;
import dev.blocknet.control.ContainerFilter;
import dev.blocknet.state.BodyState;
import dev.blocknet.state.BodyStateTracker;
import java.util.ArrayList;
import java.util.function.Consumer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceOrTagKeyArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Coordinates;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Debug commands for driving bots by hand; also reused by the bridge tool registry.
 *
 * <pre>
 * /blocknet spawn &lt;name&gt;
 * /blocknet despawn &lt;name&gt;
 * /blocknet list
 * /blocknet status [name]
 * /blocknet inv [name]
 * /blocknet plan [craft|get] [name] &lt;item&gt; [count]
 * /blocknet craft [name] &lt;item&gt; [count]
 * /blocknet get [name] &lt;item&gt; [count]
 * /blocknet changes [name]
 * /blocknet goto [name] &lt;pos&gt;
 * /blocknet come [name]
 * /blocknet follow [name]
 * /blocknet mine [name] &lt;pos&gt;
 * /blocknet use [name] &lt;pos&gt;
 * /blocknet look [name]
 * /blocknet close [name]
 * /blocknet stop [name]
 * </pre>
 *
 * <p>Without a name, a command goes to the bot nearest to whoever runs it.
 */
public final class BlocknetCommands {
    private static final SimpleCommandExceptionType NO_SUCH_BOT =
        new SimpleCommandExceptionType(Component.literal("No bot with that name"));
    private static final SimpleCommandExceptionType NO_BOT_NEARBY =
        new SimpleCommandExceptionType(Component.literal("No bot in this dimension"));
    private static final SimpleCommandExceptionType NO_FOLLOW_ENTITY =
        new SimpleCommandExceptionType(Component.literal("Follow requires a command source with an entity"));

    private static final SuggestionProvider<CommandSourceStack> BOT_NAMES = (context, builder) ->
        SharedSuggestionProvider.suggest(BotManager.all().stream().map(bot -> bot.getGameProfile().getName()), builder);

    private BlocknetCommands() {}

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher(), event.getBuildContext());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher, net.minecraft.commands.CommandBuildContext buildContext) {
        dispatcher.register(Commands.literal("blocknet")
            .requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
            .then(Commands.literal("spawn")
                .then(Commands.argument("name", StringArgumentType.word())
                    .executes(BlocknetCommands::spawn)))
            .then(Commands.literal("despawn")
                .then(Commands.argument("name", StringArgumentType.word()).suggests(BOT_NAMES)
                    .executes(BlocknetCommands::despawn)))
            .then(Commands.literal("list")
                .executes(BlocknetCommands::list))
            .then(planCommand(buildContext))
            .then(craftCommand(buildContext, CraftPlan.Mode.CRAFT))
            .then(craftCommand(buildContext, CraftPlan.Mode.OBTAIN))
            .then(gatherCommand(buildContext))
            .then(Commands.literal("attack").then(Commands.argument("query", StringArgumentType.greedyString()).executes(c -> {
                String[] words = StringArgumentType.getString(c, "query").trim().split("\\s+", 2);
                BotPlayer selected = BotManager.get(words[0]);
                if (selected == null) selected = nearestBot(c.getSource());
                selected.controller().attack(words.length == 2 ? words[1] : words[0], reporter(c.getSource(), selected));
                return 1;
            })))
            .then(miningCommand("tunnel"))
            .then(miningCommand("descend"))
            .then(eatCommand(buildContext))
            .then(placeCommand(buildContext))
            .then(containerCommand("deposit"))
            .then(containerCommand("withdraw"))
            .then(memoryCommand())
            .then(withBot("see", false, (context, bot) -> {
                bot.controller().look(reporter(context.getSource(), bot));
                return 1;
            }))
            .then(visionCommand("look"))
            .then(visionCommand("scene"))
            .then(visionCommand("explore"))
            .then(withBot("look-in", true, (context, bot) -> {
                bot.controller().lookIn(BlockPosArgument.getLoadedBlockPos(context, "pos"), reporter(context.getSource(), bot));
                return 1;
            }))
            .then(withBot("status", false, (context, bot) -> {
                var snapshot = bot.bodyState().snapshot();
                sendSection(context.getSource(), "body", snapshot.sections().get("body"));
                sendSection(context.getSource(), "task", snapshot.sections().get("task"));
                return 1;
            }))
            .then(withBot("inv", false, (context, bot) -> {
                sendSection(context.getSource(), "inventory", bot.bodyState().snapshot().sections().get("inventory"));
                return 1;
            }))
            .then(withBot("changes", false, (context, bot) -> {
                reportChanges(bot.bodyState().changes(), line ->
                    context.getSource().sendSuccess(() -> Component.literal(line), false));
                return 1;
            }))
            .then(withBot("goto", true, (context, bot) -> {
                bot.controller().goTo(BlockPosArgument.getLoadedBlockPos(context, "pos"), reporter(context.getSource(), bot));
                return 1;
            }))
            .then(withBot("come", false, (context, bot) -> {
                Entity sender = context.getSource().getEntity();
                boolean onGround = sender != null && sender.onGround();
                bot.controller().come(context.getSource().getPosition(), onGround, reporter(context.getSource(), bot));
                return 1;
            }))
            .then(withBot("follow", false, (context, bot) -> {
                Entity sender = context.getSource().getEntity();
                if (sender == null) {
                    throw NO_FOLLOW_ENTITY.create();
                }
                bot.controller().follow(sender, reporter(context.getSource(), bot));
                return 1;
            }))
            .then(withBot("mine", true, (context, bot) -> {
                bot.controller().mine(BlockPosArgument.getLoadedBlockPos(context, "pos"), reporter(context.getSource(), bot));
                return 1;
            }))
            .then(withBot("use", true, (context, bot) -> {
                bot.controller().use(BlockPosArgument.getLoadedBlockPos(context, "pos"), reporter(context.getSource(), bot));
                return 1;
            }))
            .then(withBot("close", false, (context, bot) -> {
                bot.closeContainer();
                return 1;
            }))
            .then(withBot("stop", false, (context, bot) -> {
                bot.controller().stop();
                return 1;
            })));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> eatCommand(net.minecraft.commands.CommandBuildContext buildContext) {
        var root = Commands.literal("eat").executes(c -> eat(c, false, false));
        root.then(Commands.argument("item", ItemArgument.item(buildContext)).executes(c -> eat(c, false, true)));
        root.then(Commands.argument("name", StringArgumentType.word()).suggests(BOT_NAMES)
            .executes(c -> eat(c, true, false))
            .then(Commands.argument("item", ItemArgument.item(buildContext)).executes(c -> eat(c, true, true))));
        return root;
    }

    private static int eat(CommandContext<CommandSourceStack> c, boolean named, boolean item) throws CommandSyntaxException {
        BotPlayer bot = named ? bot(c) : nearestBot(c.getSource());
        bot.controller().eat(item ? ItemArgument.getItem(c, "item").createItemStack(1, false) : ItemStack.EMPTY,
            reporter(c.getSource(), bot));
        return 1;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> placeCommand(net.minecraft.commands.CommandBuildContext buildContext) {
        return Commands.literal("place").then(placeItem(buildContext, false))
            .then(Commands.argument("name", StringArgumentType.word()).suggests(BOT_NAMES).then(placeItem(buildContext, true)));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, net.minecraft.commands.arguments.item.ItemInput> placeItem(
            net.minecraft.commands.CommandBuildContext buildContext, boolean named) {
        var pos = Commands.argument("pos", BlockPosArgument.blockPos()).executes(c -> place(c, named, null));
        for (var face : net.minecraft.core.Direction.values()) {
            pos.then(Commands.literal(face.getName()).executes(c -> place(c, named, face)));
        }
        return Commands.argument("item", ItemArgument.item(buildContext)).then(pos);
    }

    private static int place(CommandContext<CommandSourceStack> c, boolean named, net.minecraft.core.Direction face)
            throws CommandSyntaxException {
        BotPlayer bot = named ? bot(c) : nearestBot(c.getSource());
        bot.controller().place(ItemArgument.getItem(c, "item").createItemStack(1, false),
            BlockPosArgument.getLoadedBlockPos(c, "pos"), face, reporter(c.getSource(), bot));
        return 1;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> visionCommand(String action) {
        return Commands.literal(action).executes(c -> vision(c, action, ""))
            .then(Commands.argument("query", StringArgumentType.greedyString()).suggests(BOT_NAMES)
                .executes(c -> vision(c, action, StringArgumentType.getString(c, "query"))));
    }

    private static int vision(CommandContext<CommandSourceStack> c, String action, String query) throws CommandSyntaxException {
        var words = new java.util.ArrayList<>(java.util.Arrays.asList(query.trim().split("\\s+")));
        BotPlayer bot = words.isEmpty() ? null : BotManager.get(words.getFirst());
        if (bot != null) words.removeFirst();
        else bot = nearestBot(c.getSource());
        String argument = words.isEmpty() ? "" : words.getFirst();
        var report = reporter(c.getSource(), bot);
        try {
            if (action.equals("look")) {
                boolean fresh = words.remove("fresh");
                argument = words.isEmpty() ? "" : words.getFirst();
                if (!java.util.List.of("", "trees", "ores", "water", "danger", "built", "stations", "food").contains(argument) || words.size() > 1)
                    throw new IllegalArgumentException("use look [trees|ores|water|danger|built|stations|food]");
                bot.controller().look(argument, fresh, report);
            } else if (action.equals("scene")) {
                if (words.size() > 1 || !argument.isEmpty() && !argument.equals("delta")) throw new IllegalArgumentException("use scene [delta]");
                report.accept(argument.equals("delta") ? bot.controller().scene().delta() : bot.controller().scene().text(""));
            } else {
                String direction = argument.isEmpty() ? "auto" : argument;
                if (!java.util.List.of("AUTO", "N", "NE", "E", "SE", "S", "SW", "W", "NW").contains(direction.toUpperCase(java.util.Locale.ROOT)) || words.size() > 2)
                    throw new IllegalArgumentException("use explore [auto|N|NE|E|SE|S|SW|W|NW] [distance]");
                int distance = words.size() > 1 ? Integer.parseInt(words.get(1)) : 32;
                if (distance < 1 || distance > 64) throw new IllegalArgumentException("distance must be 1..64");
                bot.controller().explore(direction, distance, report);
            }
        } catch (IllegalArgumentException error) { report.accept("blocked: " + error.getMessage()); }
        return 1;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> miningCommand(String action) {
        return Commands.literal(action).then(Commands.argument("query", StringArgumentType.greedyString()).executes(c -> {
            var words = new java.util.ArrayList<>(java.util.Arrays.asList(StringArgumentType.getString(c, "query").trim().split("\\s+")));
            BotPlayer bot = BotManager.get(words.getFirst());
            if (bot != null) words.removeFirst(); else bot = nearestBot(c.getSource());
            var report = reporter(c.getSource(), bot);
            try {
                boolean down = action.equals("descend");
                if (words.size() < 1 || words.size() > 2 || !down && words.size() != 2)
                    throw new IllegalArgumentException("use descend <y> [N|E|S|W] or tunnel <N|E|S|W> <length>");
                String dir = down ? words.size() == 2 ? words.get(1) : bot.getDirection().getName() : words.getFirst();
                Direction direction = switch (dir.toUpperCase(java.util.Locale.ROOT)) {
                    case "N", "NORTH" -> Direction.NORTH;
                    case "E", "EAST" -> Direction.EAST;
                    case "S", "SOUTH" -> Direction.SOUTH;
                    case "W", "WEST" -> Direction.WEST;
                    default -> throw new IllegalArgumentException("direction must be N, E, S or W");
                };
                int value = Integer.parseInt(words.get(down ? 0 : 1));
                if (down) {
                    if (value < bot.level().getMinBuildHeight() || value >= bot.getBlockY()) throw new IllegalArgumentException("y must be below your feet and inside the world");
                    bot.controller().descend(value, direction, report);
                } else {
                    if (value < 1 || value > 64) throw new IllegalArgumentException("length must be 1..64");
                    bot.controller().tunnel(direction, value, report);
                }
            } catch (IllegalArgumentException error) { report.accept("blocked: " + error.getMessage()); }
            return 1;
        }));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> gatherCommand(net.minecraft.commands.CommandBuildContext buildContext) {
        return Commands.literal("gather").then(Commands.argument("query", StringArgumentType.greedyString())
            .executes(c -> {
                var words = new java.util.ArrayList<>(java.util.Arrays.asList(StringArgumentType.getString(c, "query").trim().split("\\s+")));
                BotPlayer bot = BotManager.get(words.getFirst());
                if (bot != null) words.removeFirst(); else bot = nearestBot(c.getSource());
                var report = reporter(c.getSource(), bot);
                try {
                    if (words.isEmpty() || words.size() > 2) throw new IllegalArgumentException("use gather <ref|category|item> [count]");
                    int count = words.size() > 1 ? Integer.parseInt(words.get(1)) : 1;
                    if (count < 1 || count > 1000000) throw new IllegalArgumentException("count must be 1..1000000");
                    bot.controller().gather(words.getFirst(), count, report);
                } catch (IllegalArgumentException error) { report.accept("blocked: " + error.getMessage()); }
                return 1;
            }));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> planCommand(net.minecraft.commands.CommandBuildContext buildContext) {
        var root = Commands.literal("plan");
        root.then(planItem(buildContext, false));
        root.then(Commands.argument("name", StringArgumentType.word()).suggests(BOT_NAMES).then(planItem(buildContext, true)));
        for (CraftPlan.Mode mode : CraftPlan.Mode.values()) {
            var option = Commands.literal(mode == CraftPlan.Mode.CRAFT ? "craft" : "get");
            option.then(planItem(buildContext, false, mode));
            option.then(Commands.argument("name", StringArgumentType.word()).suggests(BOT_NAMES)
                .then(planItem(buildContext, true, mode)));
            root.then(option);
        }
        return root;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> craftCommand(
            net.minecraft.commands.CommandBuildContext buildContext, CraftPlan.Mode mode) {
        var root = Commands.literal(mode == CraftPlan.Mode.CRAFT ? "craft" : "get");
        root.then(craftItem(buildContext, false, mode));
        root.then(Commands.argument("name", StringArgumentType.word()).suggests(BOT_NAMES).then(craftItem(buildContext, true, mode)));
        return root;
    }

    private static RequiredArgumentBuilder<CommandSourceStack, net.minecraft.commands.arguments.item.ItemInput> craftItem(
            net.minecraft.commands.CommandBuildContext buildContext, boolean named, CraftPlan.Mode mode) {
        return Commands.argument("item", ItemArgument.item(buildContext))
            .executes(c -> craft(c, named, 1, mode))
            .then(Commands.argument("count", IntegerArgumentType.integer(1, 1000000))
                .executes(c -> craft(c, named, IntegerArgumentType.getInteger(c, "count"), mode)));
    }

    private static int craft(CommandContext<CommandSourceStack> context, boolean named, int count,
            CraftPlan.Mode mode) throws CommandSyntaxException {
        BotPlayer bot = named ? bot(context) : nearestBot(context.getSource());
        var item = ItemArgument.getItem(context, "item").createItemStack(1, false);
        if (mode == CraftPlan.Mode.CRAFT) {
            bot.controller().craft(item, count, reporter(context.getSource(), bot));
        } else {
            bot.controller().get(item, count, reporter(context.getSource(), bot));
        }
        return 1;
    }

    private static RequiredArgumentBuilder<CommandSourceStack, net.minecraft.commands.arguments.item.ItemInput> planItem(
            net.minecraft.commands.CommandBuildContext buildContext, boolean named) {
        return planItem(buildContext, named, CraftPlan.Mode.CRAFT);
    }

    private static RequiredArgumentBuilder<CommandSourceStack, net.minecraft.commands.arguments.item.ItemInput> planItem(
            net.minecraft.commands.CommandBuildContext buildContext, boolean named, CraftPlan.Mode mode) {
        return Commands.argument("item", ItemArgument.item(buildContext))
            .executes(c -> plan(c, named, 1, mode))
            .then(Commands.argument("count", IntegerArgumentType.integer(1, 1000000))
                .executes(c -> plan(c, named, IntegerArgumentType.getInteger(c, "count"), mode)));
    }

    private static int plan(CommandContext<CommandSourceStack> context, boolean named, int count,
            CraftPlan.Mode mode) throws CommandSyntaxException {
        BotPlayer bot = named ? bot(context) : nearestBot(context.getSource());
        var inventory = new ArrayList<ItemStack>();
        bot.getInventory().items.forEach(stack -> inventory.add(stack.copy()));
        var result = new CraftingCalculation(bot.serverLevel(), dev.blocknet.crafting.PlannerCatalogs.get(bot.serverLevel()),
            CraftingCalculation.Limits.DEFAULT, process -> CraftStations.EXECUTABLE.contains(process.station()))
            .plan(ItemArgument.getItem(context, "item").createItemStack(1, false), count, inventory,
                CraftStations.remembered(bot).keySet(), bot.containerMemory().sources(bot), mode);
        for (String line : result.text().split("\\n"))
            context.getSource().sendSuccess(() -> Component.literal(line), false);
        return 1;
    }

    private static String filter(CommandContext<CommandSourceStack> context) {
        String value = context.getArgument("filter", ResourceOrTagKeyArgument.Result.class).asPrintable();
        return value.equals("minecraft:all") ? "all" : value.equals("minecraft:junk") ? "junk" : value;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> containerCommand(String action) {
        return Commands.literal(action).then(containerPosition(action, false))
            .then(Commands.argument("name", StringArgumentType.word()).suggests(BOT_NAMES)
                .then(containerPosition(action, true)));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, Coordinates> containerPosition(String action, boolean named) {
        return Commands.argument("pos", BlockPosArgument.blockPos())
            .then(Commands.argument("filter", ResourceOrTagKeyArgument.resourceOrTagKey(Registries.ITEM))
                .executes(c -> transfer(c, action, named, 0))
                .then(Commands.argument("count", IntegerArgumentType.integer(1, 1000000))
                    .executes(c -> transfer(c, action, named, IntegerArgumentType.getInteger(c, "count")))));
    }

    private static int transfer(CommandContext<CommandSourceStack> context, String action, boolean named, int count)
        throws CommandSyntaxException {
        BotPlayer bot = named ? bot(context) : nearestBot(context.getSource());
        var pos = BlockPosArgument.getLoadedBlockPos(context, "pos");
        String filter = filter(context);
        if (action.equals("deposit")) {
            bot.controller().deposit(pos, filter, count, reporter(context.getSource(), bot));
        } else {
            bot.controller().withdraw(pos, filter, count, reporter(context.getSource(), bot));
        }
        return 1;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> memoryCommand() {
        return Commands.literal("memory").executes(c -> memory(c, nearestBot(c.getSource()), null))
            .then(Commands.argument("query", StringArgumentType.greedyString()).suggests(BOT_NAMES)
                .executes(c -> {
                    String[] parts = StringArgumentType.getString(c, "query").trim().split("\\s+");
                    BotPlayer bot = BotManager.get(parts[0]);
                    if (parts.length > 2 || parts.length == 2 && bot == null) {
                        throw NO_SUCH_BOT.create();
                    }
                    return memory(c, bot == null ? nearestBot(c.getSource()) : bot,
                        parts.length == 2 ? parts[1] : bot == null ? parts[0] : null);
                }));
    }

    private static int memory(CommandContext<CommandSourceStack> context, BotPlayer bot, String filter) {
        try {
            String text = bot.containerMemory().text(bot.getServer().overworld().getGameTime(),
                filter == null ? null : ContainerFilter.parse(bot, filter));
            for (String line : text.split("\\n")) {
                context.getSource().sendSuccess(() -> Component.literal(line), false);
            }
            return 1;
        } catch (IllegalArgumentException exception) {
            context.getSource().sendFailure(Component.literal(exception.getMessage()));
            return 0;
        }
    }

    private static void sendSection(CommandSourceStack source, String name, BodyState.Section section) {
        source.sendSuccess(() -> Component.literal(name + " r" + section.revision() + ": " + section.text()), false);
    }

    /** Shared text for debug commands and independent bridge revision baselines. */
    public static void reportChanges(BodyStateTracker.Delta delta, Consumer<String> report) {
        if (delta.sections().isEmpty()) {
            report.accept("no changes");
        } else {
            delta.sections().forEach((name, section) ->
                report.accept(name + " r" + section.revision() + ": " + section.text()));
            delta.inventoryDiff().forEach(report);
        }
    }

    /** A command aimed at one bot, run on behalf of a command source. */
    @FunctionalInterface
    private interface BotCommand {
        int run(CommandContext<CommandSourceStack> context, BotPlayer bot) throws CommandSyntaxException;
    }

    /**
     * {@code /blocknet <literal> [name] [pos]}: without a name the command goes to the bot nearest to
     * whoever runs it, so a single bot (or the client's radial menu) needs no name.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> withBot(String literal, boolean takesPos, BotCommand command) {
        LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal(literal);
        RequiredArgumentBuilder<CommandSourceStack, String> named = Commands.argument("name", StringArgumentType.word()).suggests(BOT_NAMES);
        if (takesPos) {
            var nearPos = Commands.argument("pos", BlockPosArgument.blockPos())
                .executes(context -> command.run(context, nearestBot(context.getSource())));
            var namedPos = Commands.argument("pos", BlockPosArgument.blockPos())
                .executes(context -> command.run(context, bot(context)));
            if (literal.equals("use")) {
                nearPos.then(Commands.literal("sneak").executes(c -> sneakUse(c, nearestBot(c.getSource()))));
                namedPos.then(Commands.literal("sneak").executes(c -> sneakUse(c, bot(c))));
            }
            node.then(nearPos);
            named.then(namedPos);
            if (literal.equals("goto")) {
                node.then(Commands.argument("ref", StringArgumentType.word()).executes(c -> gotoRef(c, nearestBot(c.getSource()))));
                named.then(Commands.argument("ref", StringArgumentType.word()).executes(c -> gotoRef(c, bot(c))));
                named.executes(c -> {
                    nearestBot(c.getSource()).controller().goTo(StringArgumentType.getString(c, "name"), reporter(c.getSource(), nearestBot(c.getSource())));
                    return 1;
                });
            }
        } else {
            node.executes(context -> command.run(context, nearestBot(context.getSource())));
            named.executes(context -> command.run(context, bot(context)));
        }
        return node.then(named);
    }

    private static int gotoRef(CommandContext<CommandSourceStack> c, BotPlayer bot) {
        bot.controller().goTo(StringArgumentType.getString(c, "ref"), reporter(c.getSource(), bot));
        return 1;
    }

    private static int sneakUse(CommandContext<CommandSourceStack> c, BotPlayer bot) throws CommandSyntaxException {
        bot.controller().use(BlockPosArgument.getLoadedBlockPos(c, "pos"), true, reporter(c.getSource(), bot));
        return 1;
    }

    private static int spawn(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String name = StringArgumentType.getString(context, "name");
        try {
            BotManager.spawn(source.getServer(), name, source.getLevel(), source.getPosition(), source.getRotation().y);
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Spawned bot " + name), true);
        return 1;
    }

    private static int despawn(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String name = StringArgumentType.getString(context, "name");
        if (!BotManager.despawn(name)) {
            throw NO_SUCH_BOT.create();
        }
        context.getSource().sendSuccess(() -> Component.literal("Despawned bot " + name), true);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (BotManager.all().isEmpty()) {
            source.sendSuccess(() -> Component.literal("No bots"), false);
            return 0;
        }
        for (BotPlayer bot : BotManager.all()) {
            String line = bot.getGameProfile().getName() + " at " + bot.blockPosition().toShortString() + ": " + bot.controller().describe();
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return BotManager.all().size();
    }

    private static BotPlayer nearestBot(CommandSourceStack source) throws CommandSyntaxException {
        return BotManager.nearest(source.getLevel(), source.getPosition()).orElseThrow(NO_BOT_NEARBY::create);
    }

    private static BotPlayer bot(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        BotPlayer bot = BotManager.get(StringArgumentType.getString(context, "name"));
        if (bot == null) {
            throw NO_SUCH_BOT.create();
        }
        return bot;
    }

    /** Reports task results back to whoever gave the command. */
    private static Consumer<String> reporter(CommandSourceStack source, BotPlayer bot) {
        String name = bot.getGameProfile().getName();
        return message -> source.sendSuccess(() -> Component.literal("[" + name + "] " + message), false);
    }
}
