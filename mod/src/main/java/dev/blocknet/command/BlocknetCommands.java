package dev.blocknet.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
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
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Debug commands for driving bots by hand until the brain bridge exists.
 *
 * <pre>
 * /blocknet spawn &lt;name&gt;
 * /blocknet despawn &lt;name&gt;
 * /blocknet list
 * /blocknet goto &lt;name&gt; &lt;pos&gt;
 * /blocknet come &lt;name&gt;
 * /blocknet mine &lt;name&gt; &lt;pos&gt;
 * /blocknet stop &lt;name&gt;
 * </pre>
 */
public final class BlocknetCommands {
    private static final SimpleCommandExceptionType NO_SUCH_BOT =
        new SimpleCommandExceptionType(Component.literal("No bot with that name"));

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
            .then(Commands.literal("goto")
                .then(Commands.argument("name", StringArgumentType.word()).suggests(BOT_NAMES)
                    .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(context -> {
                            BotPlayer bot = bot(context);
                            BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, "pos");
                            bot.controller().goTo(pos, reporter(context.getSource(), bot));
                            return 1;
                        }))))
            .then(Commands.literal("come")
                .then(Commands.argument("name", StringArgumentType.word()).suggests(BOT_NAMES)
                    .executes(context -> {
                        BotPlayer bot = bot(context);
                        BlockPos pos = BlockPos.containing(context.getSource().getPosition());
                        bot.controller().goTo(pos, reporter(context.getSource(), bot));
                        return 1;
                    })))
            .then(Commands.literal("mine")
                .then(Commands.argument("name", StringArgumentType.word()).suggests(BOT_NAMES)
                    .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(context -> {
                            BotPlayer bot = bot(context);
                            BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, "pos");
                            bot.controller().mine(pos, reporter(context.getSource(), bot));
                            return 1;
                        }))))
            .then(Commands.literal("stop")
                .then(Commands.argument("name", StringArgumentType.word()).suggests(BOT_NAMES)
                    .executes(context -> {
                        bot(context).controller().stop();
                        return 1;
                    }))));
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
