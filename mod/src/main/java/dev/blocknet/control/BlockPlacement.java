package dev.blocknet.control;

import dev.blocknet.body.BotPlayer;
import dev.blocknet.memory.WorldMemory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Shared station/block placement checks and the player's sneak-right-click path. */
public final class BlockPlacement {
    private BlockPlacement() {}

    public static BlockHitResult face(BotPlayer bot, BlockPos spot, Direction face) {
        var level = bot.serverLevel();
        BlockPos support = spot.relative(face.getOpposite());
        if (!level.getBlockState(spot).canBeReplaced() || level.getBlockState(support).canBeReplaced()
                || bot.getBoundingBox().intersects(new AABB(spot))
                || !level.getEntities(bot, new AABB(spot), e -> !e.isSpectator()).isEmpty()
                || !bot.canInteractWithBlock(support, -0.5)) return null;
        var shape = level.getBlockState(support).getShape(level, support);
        if (shape.isEmpty()) return null;
        AABB bounds = shape.bounds();
        Vec3 center = bounds.getCenter().add(Vec3.atLowerCornerOf(support));
        Vec3 point = center.add(face.getStepX() * bounds.getXsize() * 0.4999,
            face.getStepY() * bounds.getYsize() * 0.4999, face.getStepZ() * bounds.getZsize() * 0.4999);
        var hit = WorldMemory.clip(bot, point, ClipContext.Block.OUTLINE);
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(support)
            && hit.getDirection() == face ? hit : null;
    }

    public static void click(BotPlayer bot, BlockHitResult hit) {
        boolean sneaking = bot.isShiftKeyDown();
        bot.setShiftKeyDown(true);
        try {
            bot.gameMode.useItemOn(bot, bot.serverLevel(), bot.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
        } finally { bot.setShiftKeyDown(sneaking); }
    }
}
