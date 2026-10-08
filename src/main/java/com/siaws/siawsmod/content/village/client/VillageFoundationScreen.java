package com.siaws.siawsmod.content.village.client;

import com.siaws.siawsmod.content.village.VillageFoundationMenu;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public class VillageFoundationScreen extends AbstractContainerScreen<VillageFoundationMenu> {
    private Button cycleButton;

    public VillageFoundationScreen(VillageFoundationMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageHeight = 120;
        this.inventoryLabelY = Integer.MAX_VALUE; // no player slots to label
    }

    @Override
    protected void init() {
        super.init();
        int cx = this.width / 2;
        int y = this.topPos + 40;
        this.cycleButton = this.addRenderableWidget(Button.builder(flavorLabel(), b -> press(VillageFoundationMenu.BUTTON_CYCLE))
                .bounds(cx - 100, y, 200, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.siasworkshop.village_foundation.create"),
                        b -> press(VillageFoundationMenu.BUTTON_CREATE))
                .bounds(cx - 100, y + 24, 200, 20).build());
    }

    private void press(int buttonId) {
        if (this.minecraft != null && this.minecraft.gameMode != null) {
            this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, buttonId);
        }
    }

    private Component flavorLabel() {
        return Component.translatable("gui.siasworkshop.village_foundation.flavor",
                Component.translatable("gui.siasworkshop.village_foundation.flavor." + this.menu.flavor().name().toLowerCase()));
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.cycleButton.setMessage(flavorLabel()); // follow server-synced flavor
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        int x = (this.width - this.imageWidth) / 2;
        int y = (this.height - this.imageHeight) / 2;
        guiGraphics.fill(x, y, x + this.imageWidth, y + this.imageHeight, 0xC0000000);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, y + 16, 0xFFFFFF);
    }
}
