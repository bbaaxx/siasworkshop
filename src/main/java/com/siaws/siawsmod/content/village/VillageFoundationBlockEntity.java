package com.siaws.siawsmod.content.village;

import com.siaws.siawsmod.content.village.VillagePlacer.Result;
import com.siaws.siawsmod.init.SiasWorkshopBlockEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

public class VillageFoundationBlockEntity extends BlockEntity implements MenuProvider {
    public enum Flavor {
        CHERRY, VANILLA;

        public Flavor next() {
            return values()[(ordinal() + 1) % values().length];
        }

        public boolean cherry() {
            return this == CHERRY;
        }
    }

    private Flavor flavor = Flavor.CHERRY;
    private boolean generated = false;

    public VillageFoundationBlockEntity(BlockPos pos, BlockState state) {
        super(SiasWorkshopBlockEntities.VILLAGE_FOUNDATION.get(), pos, state);
    }

    public Flavor flavor() {
        return flavor;
    }

    public boolean generated() {
        return generated;
    }

    public void cycleFlavor() {
        flavor = flavor.next();
        setChanged();
    }

    /** Server-side, from the menu's Create button. */
    public void create(ServerPlayer player) {
        if (generated) {
            player.displayClientMessage(Component.translatable("block.siasworkshop.village_foundation.already"), true);
            return;
        }
        if (!(level instanceof ServerLevel serverLevel)) return;
        Result result = VillagePlacer.place(serverLevel, worldPosition, flavor.cherry());
        switch (result) {
            case SUCCESS -> {
                generated = true;
                player.displayClientMessage(Component.translatable("block.siasworkshop.village_foundation.success"), true);
                // The foundation is consumed by the creation it anchors.
                serverLevel.destroyBlock(worldPosition, false);
            }
            case TOO_CLOSE -> player.displayClientMessage(Component.translatable(
                    "block.siasworkshop.village_foundation.too_close", VillagePlacer.MIN_SEPARATION), true);
            case FAILED -> player.displayClientMessage(Component.translatable("block.siasworkshop.village_foundation.failed"), true);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString("flavor", flavor.name());
        tag.putBoolean("generated", generated);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        flavor = tag.contains("flavor") ? Flavor.valueOf(tag.getString("flavor")) : Flavor.CHERRY;
        generated = tag.getBoolean("generated");
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.siasworkshop.village_foundation");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new VillageFoundationMenu(containerId, playerInventory, worldPosition);
    }
}
