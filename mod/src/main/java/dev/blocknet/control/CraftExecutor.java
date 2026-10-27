package dev.blocknet.control;

import dev.blocknet.body.BotPlayer;
import dev.blocknet.crafting.CraftPlan;
import dev.blocknet.crafting.CraftingCalculation;
import dev.blocknet.crafting.ItemKey;
import dev.blocknet.crafting.PlannerCatalogs;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.recipebook.ServerPlaceRecipe;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.RecipeBookMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Tick-driven player actions. The plan is a material forecast, never permission to edit stacks. */
final class CraftExecutor {
    static final int CLICK_DELAY = 3;
    private enum Phase { WITHDRAW, STEP, STATION, OPEN, PLACE, READY, TAKE, LOAD, COOK, DRAIN, CLEANUP, COLLECT }

    private record SourceItem(String dimension, long position, String description, ItemKey item) {}

    private final BotPlayer bot;
    private final BotController controller;
    private final ItemStack target;
    private final int count;
    private final CraftPlan.Mode mode;
    private final long initiallyHeld;
    private long craftedTarget;
    private final Map<SourceItem, Long> taken = new LinkedHashMap<>();
    private final Map<String, BlockPos> stations = new LinkedHashMap<>();
    private final Map<String, BlockPos> placed = new LinkedHashMap<>();
    private final List<String> notes = new ArrayList<>();
    private final Deque<Runnable> clicks = new ArrayDeque<>();
    private CraftPlan plan;
    private Phase phase = Phase.WITHDRAW;
    private int withdrawalIndex;
    private ContainerTask withdrawal;
    private int stepIndex;
    private long repetitions;
    private int delay;
    private int phaseTicks;
    private boolean replanned;
    private boolean finished;
    private BlockPos stationPos;
    private String cleanupStation;
    private long cleanupBefore;
    private RecipeHolder<?> recipe;
    private ItemKey result;
    private long resultBefore;
    private long cookOutput;
    private long cookWait;
    private boolean cookingInitialized;
    private final Map<ItemKey, Long> cookingInputs = new TreeMap<>();
    private final Map<ItemKey, Long> cookingFuel = new TreeMap<>();
    private long batchOperations;
    private long cookedTaken;
    private int cookingOutputCount;
    private Map<ItemKey, Long> beforeStep;
    private AbstractContainerMenu expectedMenu;

    CraftExecutor(BotPlayer bot, BotController controller, ItemStack target, int count, CraftPlan.Mode mode) {
        this.bot = bot;
        this.controller = controller;
        this.target = target.copyWithCount(1);
        this.count = count;
        this.mode = mode;
        initiallyHeld = amount(new ItemKey(target));
    }

    void start() {
        if (mode == CraftPlan.Mode.OBTAIN && initiallyHeld >= count) {
            complete("already have " + initiallyHeld + " " + new ItemKey(target));
            return;
        }
        bot.closeContainer();
        replan();
        if (plan.status() == CraftPlan.Status.NO_RECIPE) {
            complete("blocked: no recipe for " + plan.target()
                + (plan.hints().isEmpty() ? "" : "; " + String.join("; ", plan.hints())));
        } else if (plan.status() == CraftPlan.Status.MISSING) {
            complete("blocked: missing " + plan.missing().entrySet().stream()
                .map(entry -> entry.getValue() + " " + entry.getKey()).collect(Collectors.joining(", ")));
        } else if (plan.status() != CraftPlan.Status.OK) {
            fail("search limit");
        }
    }

    private void replan() {
        stations.putAll(CraftStations.visible(bot));
        var inventory = new ArrayList<ItemStack>();
        // Recipe placement uses the player's main inventory, not equipped armor or offhand.
        bot.getInventory().items.forEach(stack -> inventory.add(stack.copy()));
        long remaining = mode == CraftPlan.Mode.CRAFT ? count - craftedTarget : count;
        if (remaining <= 0) {
            stepIndex = plan.steps().size();
            withdrawalIndex = plan.withdrawals().size();
            return;
        }
        plan = new CraftingCalculation(bot.serverLevel(), PlannerCatalogs.get(bot.serverLevel()),
            CraftingCalculation.Limits.DEFAULT, process -> CraftStations.EXECUTABLE.contains(process.station()))
            .plan(target, remaining, inventory, stations.keySet(), bot.containerMemory().sources(bot), mode);
        stepIndex = 0;
        withdrawalIndex = 0;
    }

    String describe() {
        return "crafting step " + (stepIndex + 1) + " " + phase.name().toLowerCase(java.util.Locale.ROOT);
    }

    void tick() {
        observeFurnace();
        if (withdrawal != null) {
            withdrawal.tick();
            return;
        }
        if (finished || delay-- > 0) {
            return;
        }
        long timeout = phase == Phase.COOK && recipe.value() instanceof AbstractCookingRecipe cooking
            ? Math.max(12_000L, cooking.getCookingTime() * batchOperations * 3 + 2_000) : 12_000;
        if (++phaseTicks > timeout) {
            fail("timed out during " + phase);
            return;
        }
        if (expectedMenu != null && bot.containerMenu != expectedMenu) {
            fail("menu closed during " + phase);
            return;
        }
        if (!clicks.isEmpty()) {
            clicks.removeFirst().run();
            delay = CLICK_DELAY - 1;
            bot.containerMenu.broadcastChanges();
            observeFurnace();
            return;
        }
        switch (phase) {
            case WITHDRAW -> withdraw();
            case STEP -> beginStep();
            case STATION -> station();
            case OPEN -> open();
            case PLACE -> placeRecipe();
            case READY -> readyCraft();
            case TAKE -> takeCraft();
            case LOAD -> loadFurnace();
            case COOK -> cook();
            case DRAIN -> finishStep();
            case CLEANUP -> cleanup();
            case COLLECT -> {}
        }
    }

    boolean isWithdrawing() {
        return phase == Phase.WITHDRAW;
    }

    private void observeFurnace() {
        if (!isWithdrawing() && stationPos != null && bot.containerMenu instanceof AbstractFurnaceMenu) {
            bot.containerMemory().observe(bot, stationPos);
        }
    }

    private void withdraw() {
        if (withdrawalIndex == plan.withdrawals().size()) {
            next(Phase.STEP);
            return;
        }
        var step = plan.withdrawals().get(withdrawalIndex);
        if (!step.source().dimension().equals(bot.level().dimension().location().toString())) {
            fail("container is in another dimension");
            return;
        }
        long beforeWithdrawal = amount(step.item());
        withdrawal = new ContainerTask(bot, controller, BlockPos.of(step.source().position()),
            ContainerTask.Action.WITHDRAW, stack -> !stack.isEmpty() && new ItemKey(stack).equals(step.item()),
            step.count(), report -> {
                withdrawal = null;
                if (finished) {
                    return;
                }
                long moved = amount(step.item()) - beforeWithdrawal;
                if (moved > 0) {
                    var sourceItem = new SourceItem(step.source().dimension(), step.source().position(),
                        step.source().description(), step.item());
                    taken.merge(sourceItem, moved, Long::sum);
                }
                if (report.startsWith("failed:")) {
                    if (replanned) {
                        fail("withdrawal: " + report);
                        return;
                    }
                    replanned = true;
                    replan();
                    if (plan.status() != CraftPlan.Status.OK) {
                        complete("failed: remembered contents changed; " + plan.text());
                        return;
                    }
                } else {
                    withdrawalIndex++;
                }
                next(Phase.WITHDRAW);
            });
    }

    private void next(Phase next) {
        phase = next;
        phaseTicks = 0;
        delay = CLICK_DELAY - 1;
    }

    private CraftPlan.Step step() {
        return plan.steps().get(stepIndex);
    }

    private void beginStep() {
        if (stepIndex == plan.steps().size()) {
            next(Phase.CLEANUP);
            return;
        }
        if (!hasInputs(step())) {
            if (replanned) {
                fail("inputs changed after replanning");
                return;
            }
            replanned = true;
            int interruptedStep = stepIndex + 1;
            var interruptedRecipe = step().recipeId();
            replan();
            if (plan.status() != CraftPlan.Status.OK) {
                complete("failed: step " + interruptedStep + " " + interruptedRecipe + ": inventory changed; "
                    + (plan.status() == CraftPlan.Status.MISSING ? "missing " + plan.missing() : "search limit"));
                return;
            }
            next(Phase.WITHDRAW);
            return;
        }
        recipe = bot.serverLevel().getRecipeManager().byKey(step().recipeId()).orElse(null);
        if (recipe == null) {
            fail("recipe disappeared");
            return;
        }
        String station = step().station();
        if (!CraftStations.EXECUTABLE.contains(station)) {
            fail("unsupported station " + station);
            return;
        }
        repetitions = step().times();
        cookingInitialized = false;
        beforeStep = inventory();
        if (station.equals("inventory")) {
            bot.closeContainer();
            next(Phase.PLACE);
        } else {
            stationPos = stations.get(station);
            next(Phase.STATION);
        }
    }

    private boolean hasInputs(CraftPlan.Step step) {
        var stock = inventory();
        return step.inputs().entrySet().stream()
            .allMatch(entry -> stock.getOrDefault(entry.getKey(), 0L) >= entry.getValue());
    }

    private Map<ItemKey, Long> inventory() {
        Map<ItemKey, Long> stock = new TreeMap<>();
        for (ItemStack stack : bot.getInventory().items) {
            if (!stack.isEmpty()) {
                stock.merge(new ItemKey(stack), (long) stack.getCount(), Long::sum);
            }
        }
        return stock;
    }

    private long amount(ItemKey key) {
        return inventory().getOrDefault(key, 0L);
    }

    private void station() {
        if (stationPos != null) {
            if (!bot.level().getBlockState(stationPos).is(net.minecraft.world.level.block.Block.byItem(CraftStations.item(step().station())))) {
                fail("station disappeared");
                return;
            }
            next(Phase.OPEN);
            return;
        }
        int slot = findInventoryItem(new ItemKey(new ItemStack(CraftStations.item(step().station()))));
        if (slot < 0) {
            fail("station item missing");
            return;
        }
        // Swap through the inventory menu, exactly as pressing a hotbar number over a slot.
        if (slot != bot.getInventory().selected) {
            int menuSlot = inventoryMenuSlot(slot);
            bot.containerMenu.clicked(menuSlot, bot.getInventory().selected, ClickType.SWAP, bot);
            delay = CLICK_DELAY - 1;
            return;
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos spot = bot.blockPosition().relative(direction);
            BlockPos ground = spot.below();
            if (!bot.level().getBlockState(spot).canBeReplaced()
                || !bot.level().getBlockState(ground).isFaceSturdy(bot.level(), ground, Direction.UP)
                || !bot.canInteractWithBlock(ground, -0.5)) {
                continue;
            }
            Vec3 point = Vec3.atCenterOf(ground).add(0, 0.4999, 0);
            var hit = bot.level().clip(new ClipContext(bot.getEyePosition(), point,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, bot));
            if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(ground)) {
                continue;
            }
            var placedHit = controller.craftApproach(ground);
            if (placedHit == null) {
                return;
            }
            bot.gameMode.useItemOn(bot, bot.serverLevel(), bot.getMainHandItem(), InteractionHand.MAIN_HAND,
                new BlockHitResult(point, Direction.UP, ground, false));
            if (bot.level().getBlockState(spot).is(net.minecraft.world.level.block.Block.byItem(CraftStations.item(step().station())))) {
                stationPos = spot.immutable();
                stations.put(step().station(), stationPos);
                placed.put(step().station(), stationPos);
                next(Phase.OPEN);
                return;
            }
        }
        fail("no free place for " + step().station());
    }

    private void open() {
        BlockHitResult hit = controller.craftApproach(stationPos);
        if (hit == null) {
            return;
        }
        bot.gameMode.useItemOn(bot, bot.serverLevel(), bot.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
        expectedMenu = bot.containerMenu;
        if (step().station().equals("crafting_table")) {
            if (!(bot.containerMenu instanceof CraftingMenu)) {
                fail("crafting menu did not open");
                return;
            }
            next(Phase.PLACE);
        } else {
            if (!(bot.containerMenu instanceof AbstractFurnaceMenu)) {
                fail("furnace menu did not open");
                return;
            }
            for (int slot = 0; slot < 3; slot++) {
                if (bot.containerMenu.getSlot(slot).hasItem()) {
                    fail("station slots occupied");
                    return;
                }
            }
            next(Phase.LOAD);
        }
    }

    /** Bypasses only the book's UI unlock gate, keeping vanilla recipe placement and grid checks. */
    private static final class Placement extends ServerPlaceRecipe<CraftingInput, CraftingRecipe> {
        private final CraftExecutor executor;
        private final Map<ItemKey, Long> budget = new TreeMap<>();

        Placement(RecipeBookMenu<CraftingInput, CraftingRecipe> menu, CraftExecutor executor) {
            super(menu);
            this.executor = executor;
            executor.step().inputs().forEach((key, count) -> budget.put(key, count / executor.step().times()));
        }

        void place(BotPlayer bot, RecipeHolder<CraftingRecipe> recipe) {
            inventory = bot.getInventory();
            stackedContents.clear();
            // Restrict vanilla's ingredient picker to this operation's planned identities.
            // This also permits named ingredients a player could move by hand.
            for (var entry : budget.entrySet()) {
                long available = executor.amount(entry.getKey());
                for (int slot = 1; slot <= menu.getGridWidth() * menu.getGridHeight(); slot++) {
                    ItemStack stack = menu.getSlot(slot).getItem();
                    if (!stack.isEmpty() && new ItemKey(stack).equals(entry.getKey())) {
                        available += stack.getCount();
                    }
                }
                int count = (int) Math.min(available, entry.getValue());
                stackedContents.accountStack(entry.getKey().stack().copyWithCount(count), count);
            }
            if (stackedContents.canCraft(recipe.value(), null)) {
                handleRecipeClicked(recipe, false);
            }
        }

        @Override
        protected int getStackSize(boolean placeAll, int maxPossible, boolean recipeMatches) {
            return 1;
        }

        @Override
        protected int moveItemToGrid(Slot slot, ItemStack requested, int maxAmount) {
            // Keep vanilla's layout and recipe selection; transfer component-distinct stacks
            // through the same clicks a player uses within this recipe-book placement action.
            for (var entry : budget.entrySet()) {
                if (entry.getValue() < 1 || !entry.getKey().stack().is(requested.getItem())) {
                    continue;
                }
                executor.queueMove(entry.getKey(), 1, slot.index);
                entry.setValue(entry.getValue() - 1);
                return maxAmount - 1;
            }
            return -1;
        }
    }

    @SuppressWarnings("unchecked")
    private void placeRecipe() {
        if (!(bot.containerMenu instanceof RecipeBookMenu<?, ?> book) || !(recipe.value() instanceof CraftingRecipe)) {
            fail("crafting menu closed");
            return;
        }
        var menu = (RecipeBookMenu<CraftingInput, CraftingRecipe>) book;
        expectedMenu = menu;
        var holder = (RecipeHolder<CraftingRecipe>) recipe;
        if (menu instanceof CraftingMenu table) {
            table.beginPlacingRecipe();
            new Placement(menu, this).place(bot, holder);
            clicks.add(() -> table.finishPlacingRecipe(holder));
        } else {
            new Placement(menu, this).place(bot, holder);
        }
        next(Phase.READY);
    }

    @SuppressWarnings("unchecked")
    private void readyCraft() {
        var menu = (RecipeBookMenu<CraftingInput, CraftingRecipe>) bot.containerMenu;
        ItemStack output = menu.getSlot(0).getItem();
        if (output.isEmpty() || !menu.recipeMatches((RecipeHolder<CraftingRecipe>) recipe)) {
            fail("recipe placement failed");
            return;
        }
        result = new ItemKey(output);
        resultBefore = amount(result);
        cookOutput = output.getCount();
        next(Phase.TAKE);
    }

    private void takeCraft() {
        bot.containerMenu.clicked(0, 0, ClickType.QUICK_MOVE, bot);
        if (amount(result) - resultBefore != cookOutput) {
            fail("result count did not increase by " + cookOutput);
            return;
        }
        if (--repetitions > 0) {
            next(Phase.PLACE);
        } else {
            next(Phase.DRAIN);
        }
    }

    private void loadFurnace() {
        if (!(recipe.value() instanceof AbstractCookingRecipe cooking)) {
            fail("expected cooking recipe");
            return;
        }
        if (!cookingInitialized) {
            cookingInitialized = true;
            cookingInputs.clear();
            cookingFuel.clear();
            result = new ItemKey(cooking.getResultItem(bot.registryAccess()));
            cookingOutputCount = cooking.getResultItem(bot.registryAccess()).getCount();
            cookOutput = cookingOutputCount * step().times();
            resultBefore = amount(result);
            cookedTaken = 0;
            long inputsNeeded = step().times();
            for (var entry : step().inputs().entrySet()) {
                long input = cooking.getIngredients().getFirst().test(entry.getKey().stack())
                    ? Math.min(inputsNeeded, entry.getValue()) : 0;
                if (input > 0) {
                    cookingInputs.put(entry.getKey(), input);
                    inputsNeeded -= input;
                }
                if (entry.getValue() > input) {
                    cookingFuel.put(entry.getKey(), entry.getValue() - input);
                }
            }
        }
        var input = cookingInputs.entrySet().iterator().next();
        batchOperations = Math.min(input.getValue(), Math.min(input.getKey().stack().getMaxStackSize(),
            bot.containerMenu.getSlot(2).getMaxStackSize(result.stack()) / cookingOutputCount));
        queueMove(input.getKey(), batchOperations, 0);
        consumePending(cookingInputs, input.getKey(), batchOperations);
        queueFuel(cooking);
        closeAndWait(cooking.getCookingTime() * batchOperations);
        next(Phase.COOK);
    }

    private static void consumePending(Map<ItemKey, Long> pending, ItemKey key, long count) {
        long left = pending.get(key) - count;
        if (left == 0) {
            pending.remove(key);
        } else {
            pending.put(key, left);
        }
    }

    private boolean queueFuel(AbstractCookingRecipe cooking) {
        if (cookingFuel.isEmpty()) {
            return false;
        }
        var fuel = cookingFuel.entrySet().iterator().next();
        ItemStack existing = bot.containerMenu.getSlot(1).getItem();
        if (!existing.isEmpty() && existing.getBurnTime(cooking.getType()) == 0) {
            clicks.add(() -> bot.containerMenu.clicked(1, 0, ClickType.QUICK_MOVE, bot));
        } else if (!existing.isEmpty() && !new ItemKey(existing).equals(fuel.getKey())) {
            return false;
        }
        int alreadyLoaded = existing.isEmpty() || existing.getBurnTime(cooking.getType()) == 0 ? 0 : existing.getCount();
        long transfer = Math.min(fuel.getValue(), fuel.getKey().stack().getMaxStackSize() - alreadyLoaded);
        queueMove(fuel.getKey(), transfer, 1);
        consumePending(cookingFuel, fuel.getKey(), transfer);
        return transfer > 0;
    }

    private void closeAndWait(long ticks) {
        clicks.add(() -> {
            expectedMenu = null;
            observeFurnace();
            bot.closeContainer();
            cookWait = ticks;
        });
    }

    private void queueMove(ItemKey key, long count, int destination) {
        // One item per right click bounds the transfer to exactly the planner's amount.
        for (long moved = 0; moved < count; moved++) {
            int[] sourceSlot = {-1};
            clicks.add(() -> {
                int slot = findInventoryItem(key);
                if (slot < 0) {
                    fail("input disappeared while loading " + key);
                    return;
                }
                sourceSlot[0] = inventoryMenuSlot(slot);
                bot.containerMenu.clicked(sourceSlot[0], 0, ClickType.PICKUP, bot);
            });
            clicks.add(() -> {
                int before = bot.containerMenu.getSlot(destination).getItem().getCount();
                bot.containerMenu.clicked(destination, 1, ClickType.PICKUP, bot);
                if (bot.containerMenu.getSlot(destination).getItem().getCount() != before + 1) {
                    fail("slot rejected " + key);
                }
            });
            clicks.add(() -> {
                if (!bot.containerMenu.getCarried().isEmpty()) {
                    bot.containerMenu.clicked(sourceSlot[0], 0, ClickType.PICKUP, bot);
                    if (!bot.containerMenu.getCarried().isEmpty()) {
                        fail("inventory changed while returning cursor");
                    }
                }
            });
        }
    }

    private void cook() {
        if (cookWait-- > 0) {
            return;
        }
        if (!(bot.containerMenu instanceof AbstractFurnaceMenu)) {
            BlockHitResult hit = controller.craftApproach(stationPos);
            if (hit == null) {
                return;
            }
            bot.gameMode.useItemOn(bot, bot.serverLevel(), bot.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
            expectedMenu = bot.containerMenu;
            if (!(bot.containerMenu instanceof AbstractFurnaceMenu)) {
                fail("furnace menu did not reopen");
            }
            return;
        }
        ItemStack output = bot.containerMenu.getSlot(2).getItem();
        long batchOutput = batchOperations * cookingOutputCount;
        if (!output.isEmpty() && new ItemKey(output).equals(result) && output.getCount() == batchOutput) {
            bot.containerMenu.clicked(2, 0, ClickType.QUICK_MOVE, bot);
            cookedTaken += batchOutput;
            if (amount(result) - resultBefore != cookedTaken) {
                fail("cooked result count mismatch");
                return;
            }
            if (cookedTaken == cookOutput) {
                clicks.add(() -> bot.containerMenu.clicked(1, 0, ClickType.QUICK_MOVE, bot));
                next(Phase.DRAIN);
            } else {
                next(Phase.LOAD);
            }
        } else {
            var menu = (AbstractFurnaceMenu) bot.containerMenu;
            var cooking = (AbstractCookingRecipe) recipe.value();
            if (!menu.isLit() && !queueFuel(cooking)) {
                fail("fuel exhausted before cooking completed");
                return;
            }
            closeAndWait(Math.max(10, cooking.getCookingTime() * (batchOutput - output.getCount()) / cookingOutputCount));
        }
    }

    private void finishStep() {
        observeFurnace();
        expectedMenu = null;
        bot.closeContainer();
        // A placed station is reserved outside inventory, exactly as in the planner.
        var expected = new TreeMap<>(beforeStep);
        step().inputs().forEach((key, value) -> expected.merge(key, -value, Long::sum));
        step().outputs().forEach((key, value) -> expected.merge(key, value, Long::sum));
        if (placed.containsKey(step().station()) && beforeStep.containsKey(new ItemKey(new ItemStack(CraftStations.item(step().station()))))) {
            ItemKey station = new ItemKey(new ItemStack(CraftStations.item(step().station())));
            // Only the first use moved the station out of inventory.
            if (amount(station) < expected.getOrDefault(station, 0L)) {
                expected.merge(station, -1L, Long::sum);
            }
        }
        expected.values().removeIf(value -> value == 0);
        if (!expected.equals(inventory())) {
            fail("material balance changed");
            return;
        }
        ItemKey targetKey = new ItemKey(target);
        craftedTarget += step().outputs().getOrDefault(targetKey, 0L)
            - step().inputs().getOrDefault(targetKey, 0L);
        stepIndex++;
        next(Phase.STEP);
    }

    private void cleanup() {
        if (placed.isEmpty()) {
            ItemKey key = new ItemKey(target);
            long held = amount(key);
            if (held < (mode == CraftPlan.Mode.CRAFT ? initiallyHeld + count : count)
                || mode == CraftPlan.Mode.CRAFT && craftedTarget < count) {
                fail("target items changed before completion");
                return;
            }
            var details = new ArrayList<String>();
            if (mode == CraftPlan.Mode.OBTAIN && initiallyHeld > 0) {
                details.add("had " + initiallyHeld);
            }
            taken.forEach((sourceItem, amount) -> details.add("took " + amount
                + (mode == CraftPlan.Mode.OBTAIN && sourceItem.item().equals(key) ? "" : " " + sourceItem.item())
                + " from " + sourceItem.description()));
            if (mode == CraftPlan.Mode.OBTAIN && craftedTarget > 0) {
                details.add("crafted " + craftedTarget);
            }
            String message = mode == CraftPlan.Mode.CRAFT ? "crafted " + craftedTarget + " " + key
                : "got " + held + " " + key;
            if (!details.isEmpty()) {
                message += mode == CraftPlan.Mode.CRAFT ? " (" + String.join(", ", details) + ")"
                    : ": " + String.join(", ", details);
            }
            complete(message);
            return;
        }
        var entry = placed.entrySet().iterator().next();
        cleanupStation = entry.getKey();
        stationPos = entry.getValue();
        var state = bot.level().getBlockState(stationPos);
        if (state.requiresCorrectToolForDrops() && bot.getInventory().items.stream()
                .noneMatch(stack -> !stack.isEmpty() && stack.isCorrectToolForDrops(state))) {
            notes.add("left a " + cleanupStation + " at " + coordinates(stationPos));
            placed.remove(cleanupStation);
            return;
        }
        cleanupBefore = amount(new ItemKey(new ItemStack(CraftStations.item(cleanupStation))));
        next(Phase.COLLECT);
        controller.craftMine(stationPos);
    }

    void collected() {
        if (amount(new ItemKey(new ItemStack(CraftStations.item(cleanupStation)))) <= cleanupBefore) {
            fail("station drop not collected at " + coordinates(stationPos));
            return;
        }
        placed.remove(cleanupStation);
        next(Phase.CLEANUP);
    }

    private int findInventoryItem(ItemKey key) {
        for (int slot = 0; slot < bot.getInventory().items.size(); slot++) {
            ItemStack stack = bot.getInventory().getItem(slot);
            if (!stack.isEmpty() && new ItemKey(stack).equals(key)) {
                return slot;
            }
        }
        return -1;
    }

    private int inventoryMenuSlot(int inventorySlot) {
        for (int slot = 0; slot < bot.containerMenu.slots.size(); slot++) {
            var candidate = bot.containerMenu.getSlot(slot);
            if (candidate.container == bot.getInventory() && candidate.getContainerSlot() == inventorySlot) {
                return slot;
            }
        }
        throw new IllegalStateException("inventory slot unavailable");
    }

    void cancel() {
        complete(failureMessage("cancelled"));
    }

    void fail(String reason) {
        if (withdrawal != null) {
            withdrawal.fail(reason);
            return;
        }
        complete(failureMessage(reason));
    }

    private String failureMessage(String reason) {
        String recipeId = phase == Phase.WITHDRAW ? "withdraw" : plan == null || stepIndex >= plan.steps().size()
            ? "cleanup" : step().recipeId().toString();
        return "failed: step " + (stepIndex + 1) + " " + recipeId + ": " + reason;
    }

    private void complete(String message) {
        if (finished) {
            return;
        }
        finished = true;
        if (withdrawal != null) {
            withdrawal.fail("cancelled");
            withdrawal = null;
        }
        observeFurnace();
        clicks.clear();
        expectedMenu = null;
        bot.closeContainer();
        for (var entry : placed.entrySet()) {
            if (!bot.level().getBlockState(entry.getValue()).isAir()) {
                notes.add("left a " + entry.getKey() + " at " + coordinates(entry.getValue()));
            }
        }
        if (!notes.isEmpty()) {
            message += " (" + String.join("; ", notes) + ")";
        }
        controller.craftFinished(message);
    }

    private static String coordinates(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}
