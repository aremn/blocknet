/*
 * This file is part of Blocknet's pathing module, derived from Baritone/Automatone.
 *
 * It is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * It is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the
 * implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General
 * Public License for more details.
 */

package dev.blocknet.pathing.utils.player;

import dev.blocknet.pathing.api.utils.IInteractionController;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Breaks and places blocks for a server player through {@code ServerPlayerGameMode}, the same calls
 * a client's dig and use packets trigger. Break progress accumulates per tick with the player's real
 * break speed, so the server's own timing check accepts it and mods see a normal player.
 */
public final class PlayerInteractionController implements IInteractionController {
    private final ServerPlayer player;
    @Nullable
    private BlockPos miningPos;
    private Direction miningFace = Direction.UP;
    private float progress;
    private boolean brokeBlock;
    private int sequence;

    public PlayerInteractionController(ServerPlayer player) {
        this.player = player;
    }

    @Override
    public boolean clickBlock(BlockPos pos, Direction face) {
        Level level = player.level();
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return false;
        }
        if (miningPos != null && !miningPos.equals(pos)) {
            resetBlockRemoving();
        }
        brokeBlock = false;
        miningPos = pos.immutable();
        miningFace = face;
        progress = state.getDestroyProgress(player, level, pos);
        player.gameMode.handleBlockBreakAction(pos, Action.START_DESTROY_BLOCK, face, level.getMaxBuildHeight(), sequence++);
        if (level.getBlockState(pos).isAir()) {
            // Broke instantly (zero hardness or creative).
            miningPos = null;
            brokeBlock = true;
        }
        return true;
    }

    @Override
    public boolean onPlayerDamageBlock(BlockPos pos, Direction side) {
        if (miningPos == null || !miningPos.equals(pos)) {
            return false;
        }
        Level level = player.level();
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            miningPos = null;
            brokeBlock = true;
            return true;
        }
        progress += state.getDestroyProgress(player, level, pos);
        if (progress >= 1.0F) {
            player.gameMode.handleBlockBreakAction(pos, Action.STOP_DESTROY_BLOCK, side, level.getMaxBuildHeight(), sequence++);
            progress = 0.0F;
            if (level.getBlockState(pos).isAir()) {
                miningPos = null;
                brokeBlock = true;
            }
        }
        return true;
    }

    @Override
    public void resetBlockRemoving() {
        if (miningPos != null) {
            player.gameMode.handleBlockBreakAction(miningPos, Action.ABORT_DESTROY_BLOCK, miningFace, player.level().getMaxBuildHeight(), sequence++);
            miningPos = null;
        }
        progress = 0.0F;
    }

    @Override
    public boolean hasBrokenBlock() {
        return brokeBlock;
    }

    @Override
    public GameType getGameType() {
        return player.gameMode.getGameModeForPlayer();
    }

    @Override
    public InteractionResult processRightClickBlock(LivingEntity entity, Level world, InteractionHand hand, BlockHitResult result) {
        return player.gameMode.useItemOn(player, world, player.getItemInHand(hand), hand, result);
    }

    @Override
    public InteractionResult processRightClick(LivingEntity entity, Level world, InteractionHand hand) {
        return player.gameMode.useItem(player, world, player.getItemInHand(hand), hand);
    }

    @Override
    public void setHittingBlock(boolean hittingBlock) {}

    @Override
    public double getBlockReachDistance() {
        return player.blockInteractionRange();
    }
}
