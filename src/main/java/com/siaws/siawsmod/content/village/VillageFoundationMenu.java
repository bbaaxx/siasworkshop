package com.siaws.siawsmod.content.village;

import com.siaws.siawsmod.content.village.VillageFoundationBlockEntity.Flavor;
import com.siaws.siawsmod.init.SiasWorkshopMenus;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;

public class VillageFoundationMenu extends AbstractContainerMenu {
    public static final int BUTTON_CYCLE = 0;
    public static final int BUTTON_CREATE = 1;

    private final BlockPos pos;
    private final ContainerData data = new SimpleContainerData(1);

    /** Server-side (and local) constructor. */
    public VillageFoundationMenu(int containerId, Inventory playerInventory, BlockPos pos) {
        super(SiasWorkshopMenus.VILLAGE_FOUNDATION.get(), containerId);
        this.pos = pos;
        if (playerInventory.player.level().getBlockEntity(pos) instanceof VillageFoundationBlockEntity be) {
            this.data.set(0, be.flavor().ordinal());
        }
        addDataSlots(data);
    }

    /** Client-side factory reading the extra-data BlockPos written by openScreen. */
    public static VillageFoundationMenu fromNetwork(int containerId, Inventory playerInventory, FriendlyByteBuf buf) {
        return new VillageFoundationMenu(containerId, playerInventory, buf.readBlockPos());
    }

    public BlockPos pos() {
        return pos;
    }

    public Flavor flavor() {
        return Flavor.values()[data.get(0) % Flavor.values().length];
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (!(player.level().getBlockEntity(pos) instanceof VillageFoundationBlockEntity be)) return false;
        if (id == BUTTON_CYCLE) {
            be.cycleFlavor();
            data.set(0, be.flavor().ordinal());
            return true;
        }
        if (id == BUTTON_CREATE && player instanceof ServerPlayer serverPlayer) {
            be.create(serverPlayer);
            data.set(0, be.flavor().ordinal());
            return true;
        }
        return false;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY; // no slots
    }

    @Override
    public boolean stillValid(Player player) {
        return player.level().getBlockEntity(pos) instanceof VillageFoundationBlockEntity
                && player.distanceToSqr(pos.getCenter()) < 64;
    }
}
