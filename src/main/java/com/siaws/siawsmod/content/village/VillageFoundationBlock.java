package com.siaws.siawsmod.content.village;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.network.NetworkHooks;

public class VillageFoundationBlock extends Block implements EntityBlock {
    public VillageFoundationBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new VillageFoundationBlockEntity(pos, state);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
            BlockHitResult hitResult) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof VillageFoundationBlockEntity be
                && player instanceof ServerPlayer serverPlayer) {
            NetworkHooks.openScreen(serverPlayer, be, pos);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
