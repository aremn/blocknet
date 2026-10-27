package dev.blocknet.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.blocknet.body.BotManager;
import dev.blocknet.body.BotPlayer;
import java.util.function.Consumer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Debug commands for driving bots by hand until the brain bridge exists.
 *
 * <pre>
 * /blocknet spawn &lt;name&gt;
 * /blocknet despawn &lt;name&gt;
 * /blocknet list
 * /blocknet goto [name] &lt;pos&gt;
 * /blocknet come [name]
 * /blocknet mine [name] &lt;pos&gt;
 * /blocknet use [name] &lt;pos&gt;
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

    private static final SuggestionProvider<CommandSourceStack> BOT_NAMES = (context, builder) ->
        SharedSuggestionProvider.suggest(BotManager.all().stream().map(bot -> bot.getGameProfile().getName()), builder);

    private BlocknetCommands() {}

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
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
            node.then(Commands.argument("pos", BlockPosArgument.blockPos())
                .executes(context -> command.run(context, nearestBot(context.getSource()))));
            named.then(Commands.argument("pos", BlockPosArgument.blockPos())
                .executes(context -> command.run(context, bot(context))));
        } else {
            node.executes(context -> command.run(context, nearestBot(context.getSource())));
            named.executes(context -> command.run(context, bot(context)));
        }
        return node.then(named);
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
