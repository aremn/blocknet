package dev.blocknet.control;

import dev.blocknet.body.BotPlayer;
import dev.blocknet.map.Radar;
import dev.blocknet.vision.ObjectGraph;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Small useful jobs a companion takes on by itself while its player is busy in one place, chosen by
 * what the bag lacks, never far from the player: a torch where it is dark, torches from coal, wood
 * from a tree it has seen, coal from a seam it has seen, meat when there is no food. Each runs
 * through the ordinary controller actions, says what it is doing (a "chore" moment the brain may
 * voice), and ends the moment the player walks off or anything else takes the body.
 */
final class Chores {
    /** How far from the player a chore may take the bot. */
    static final double NEAR_PLAYER = 16;
    private static final int RETRY_AFTER_FAIL = 20 * 120, AGAIN_AFTER = 20 * 20;

    private final BotPlayer bot;
    private final BotController controller;
    private final Map<String, Long> notBefore = new HashMap<>();
    private String running;

    Chores(BotPlayer bot, BotController controller) {
        this.bot = bot;
        this.controller = controller;
    }

    boolean running() {
        return running != null;
    }

    /** Starts the first chore that is needed and possible next to {@code player}; false when none is. */
    boolean start(ServerPlayer player) {
        return torchHere() || craftTorches() || chopWood(player) || mineCoal(player) || huntMeat(player);
    }

    /** Ends a running chore quietly (its player walked off, or the body went idle without a report). */
    void abandon(String why) {
        if (running == null) return;
        String kind = running;
        running = null;
        notBefore.put(kind, now() + AGAIN_AFTER);
        controller.trace("idle", "leaving the " + kind + " chore: " + why);
        controller.stop();
    }

    // ---- the chores, most urgent first ----

    /** It is dark where the bot stands (night, a cave) and it carries torches: light the spot. */
    private boolean torchHere() {
        if (!ready("light") || count(Items.TORCH) == 0 || bot.level().getMaxLocalRawBrightness(bot.blockPosition()) >= 7) return false;
        BlockPos spot = null;
        for (BlockPos pos : BlockPos.betweenClosed(bot.blockPosition().offset(-2, -1, -2), bot.blockPosition().offset(2, 1, 2))) {
            if (pos.equals(bot.blockPosition()) || pos.equals(bot.blockPosition().above()) || !bot.level().getBlockState(pos).isAir()
                    || !bot.level().getBlockState(pos.below()).isFaceSturdy(bot.level(), pos.below(), Direction.UP)
                    || bot.level().getMaxLocalRawBrightness(pos) >= 7) continue;
            spot = pos.immutable();
            break;
        }
        if (spot == null) return false;
        BlockPos at = spot;
        return begin("light", "it is dark here; placing a torch at " + at.toShortString(),
            report -> controller.place(new ItemStack(Items.TORCH), at, Direction.UP, report));
    }

    /** No torches, but coal and wood in the bag: craft a few (a 2x2 recipe, no table needed). */
    private boolean craftTorches() {
        int coal = count(Items.COAL) + count(Items.CHARCOAL);
        boolean wood = count(Items.STICK) > 0 || countTag(ItemTags.PLANKS) > 0 || countTag(ItemTags.LOGS) > 0;
        if (!ready("torches") || count(Items.TORCH) >= 4 || coal == 0 || !wood) return false;
        int torches = 4 * Math.min(coal, 2);
        return begin("torches", "no torches left; crafting " + torches + " from coal and sticks",
            report -> controller.craft(new ItemStack(Items.TORCH), torches, report));
    }

    /** Little wood in the bag and a tree in sight near the player: chop that whole tree. */
    private boolean chopWood(ServerPlayer player) {
        int wood = countTag(ItemTags.LOGS) + countTag(ItemTags.PLANKS) / 4;
        if (!ready("wood") || wood >= 16) return false;
        ObjectGraph.ObjectView tree = seen("tree", null, player);
        if (tree == null) return false;
        return begin("wood", "low on wood (" + wood + " logs); chopping the " + tree.kind() + " tree " + tree.ref() + " nearby",
            report -> controller.gather(tree.ref(), 0, report));
    }

    /** Little coal, a pickaxe, and a coal seam in sight near the player: mine the seam. */
    private boolean mineCoal(ServerPlayer player) {
        int coal = count(Items.COAL) + count(Items.CHARCOAL);
        boolean pickaxe = bot.getInventory().items.stream().anyMatch(stack -> stack.is(ItemTags.PICKAXES));
        if (!ready("coal") || coal >= 8 || !pickaxe) return false;
        ObjectGraph.ObjectView seam = seen("ore", "coal", player);
        if (seam == null) return false;
        return begin("coal", "low on coal (" + coal + "); mining the coal seam " + seam.ref() + " nearby",
            report -> controller.gather(seam.ref(), 0, report));
    }

    /**
     * No food in the bag and an animal grazing near the player: hunt it. Never a named, leashed or
     * young animal, which are somebody's.
     */
    private boolean huntMeat(ServerPlayer player) {
        if (!ready("food") || bot.getInventory().items.stream().anyMatch(stack -> stack.getFoodProperties(bot) != null && !stack.is(Items.ROTTEN_FLESH))) {
            return false;
        }
        Radar.Contact prey = bot.radar().contacts().stream()
            .filter(c -> c.live() && c.category().equals("passive") && c.position().distanceTo(player.position()) <= NEAR_PLAYER)
            .filter(c -> bot.radar().entity(c) instanceof Animal animal && !animal.isBaby() && !animal.hasCustomName() && !animal.isLeashed()
                && dropsFood(animal))
            .findFirst().orElse(null);
        if (prey == null) return false;
        return begin("food", "no food left; hunting the " + prey.type() + " " + prey.ref() + " nearby",
            report -> controller.attack(prey.uuid().toString(), report));
    }

    private boolean dropsFood(LivingEntity animal) {
        return dev.blocknet.knowledge.Knowledge.get(bot.serverLevel()).drops(animal).stream()
            .anyMatch(item -> new ItemStack(item).getFoodProperties(bot) != null);
    }

    // ---- helpers ----

    /** The nearest observed object of a category (and kind) within reach of both the bot and its player. */
    @Nullable
    private ObjectGraph.ObjectView seen(String category, @Nullable String kind, ServerPlayer player) {
        return bot.vision().objects().stream()
            .filter(o -> o.category().equals(category) && (kind == null || o.kind().equals(kind)))
            .filter(o -> o.center().distanceTo(player.position()) <= NEAR_PLAYER && o.center().distanceTo(bot.position()) <= NEAR_PLAYER + 8)
            .min(Comparator.comparingDouble(o -> o.center().distanceTo(bot.position())))
            .orElse(null);
    }

    private boolean begin(String kind, String note, Consumer<Consumer<String>> action) {
        running = kind;
        controller.trace("idle", note);
        bot.moments().emit("chore", note);
        action.accept(message -> {
            if (!kind.equals(running)) return;
            running = null;
            boolean failed = message.matches("(?is)^(?:\\[[^\\]]+\\] )?(?:failed|blocked|cancelled|stopped|unsupported|none).*");
            notBefore.put(kind, now() + (failed ? RETRY_AFTER_FAIL : AGAIN_AFTER));
            controller.trace("idle", kind + " chore " + (failed ? "failed: " : "done: ") + message);
        });
        return true;
    }

    private boolean ready(String kind) {
        return now() >= notBefore.getOrDefault(kind, 0L);
    }

    private int count(Item item) {
        return bot.getInventory().countItem(item);
    }

    private int countTag(net.minecraft.tags.TagKey<Item> tag) {
        return bot.getInventory().items.stream().filter(stack -> stack.is(tag)).mapToInt(ItemStack::getCount).sum();
    }

    private long now() {
        return bot.tickCount;
    }
}
