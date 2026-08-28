package com.extremecraftingtable.machines.workbench;

import com.extremecraftingtable.ECTMod;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

public class ScreenWorkbench extends AbstractContainerScreen<ContainerWorkbench> {
    private static final ResourceLocation GUI_TEXTURE = ECTMod.location("textures/gui/workbench.png");

    public ScreenWorkbench(ContainerWorkbench menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = 176;
        this.imageHeight = 232;
        this.inventoryLabelY = 10000; // Hidden: the output slot occupies the label row (y=130).
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float delta, int mouseX, int mouseY) {
        // Only blit the background texture here. The energy bar and recipe-selection
        // border are drawn in render() AFTER super.render() — GuiGraphics.fill()
        // toggles the global RenderSystem blend state, and running it here would
        // leak into the slot/tooltip rendering pass, which can suppress tooltips.
        int x = (this.width - this.imageWidth) / 2;
        int y = (this.height - this.imageHeight) / 2;
        graphics.blit(GUI_TEXTURE, x, y, 0, 0, this.imageWidth, this.imageHeight, 256, 256);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        // Draw everything vanilla-side first (background, slots, items, tooltips),
        // then overlay our decorative fills on top so their RenderSystem state
        // changes cannot affect tooltip rendering.
        super.render(graphics, mouseX, mouseY, delta);
        int x = (this.width - this.imageWidth) / 2;
        int y = (this.height - this.imageHeight) / 2;
        renderOutputSlot(graphics, x, y);
        renderEnergyBar(graphics, x, y);
        renderRecipeSelection(graphics, x, y);
    }

    /**
     * Draws a slot background for the output-cache slot at screen (8, 130).
     * The baked texture is generated without a slot there in the gap between the
     * recipe-preview rows and the player inventory, so it is drawn programmatically.
     */
    private void renderOutputSlot(GuiGraphics graphics, int x, int y) {
        int slotX = x + 8;
        int slotY = y + 130;
        // Border
        graphics.fill(slotX, slotY, slotX + 18, slotY + 18, 0xFF000000);
        // Inner floor
        graphics.fill(slotX + 1, slotY + 1, slotX + 17, slotY + 17, 0xFFC6C6C6);
        // Top-left highlight
        graphics.fill(slotX + 1, slotY + 1, slotX + 17, slotY + 2, 0xFFFFFFFF);
        graphics.fill(slotX + 1, slotY + 1, slotX + 2, slotY + 17, 0xFFFFFFFF);
        // Bottom-right shadow
        graphics.fill(slotX + 16, slotY + 2, slotX + 17, slotY + 17, 0xFF555555);
        graphics.fill(slotX + 2, slotY + 16, slotX + 17, slotY + 17, 0xFF555555);
    }

    /**
     * Renders the energy progress bar in the gap between the ingredient slots
     * (y=18-72) and the recipe-preview area (y=90-126). The {@code progress}
     * DataSlot is synced every tick from the server; showing it here gives the
     * player visible feedback about the machine's energy state.
     */
    private void renderEnergyBar(GuiGraphics graphics, int x, int y) {
        int progress = this.menu.progress.get();
        if (progress <= 0) return;
        int barX = x + 8;
        int barY = y + 76;
        int barW = 160;
        int barH = 5;
        int fill = (int) (barW * progress / 160.0f);
        // Background
        graphics.fill(barX, barY, barX + barW, barY + barH, 0xFF1A1A1A);
        // Energy fill
        graphics.fill(barX, barY, barX + fill, barY + barH, 0xFF00FF00);
    }

    /**
     * Highlights the currently selected recipe preview slot with a gold border.
     * The {@code recipeIndex} DataSlot is synced every tick from the server;
     * -1 means no recipe is selected.
     */
    private void renderRecipeSelection(GuiGraphics graphics, int x, int y) {
        int index = this.menu.recipeIndex.get();
        if (index < 0 || index >= 18) return;
        int col = index % 9;
        int row = index / 9;
        int sx = x + 8 + col * 18;
        int sy = y + 90 + row * 18;
        // 1-px border around the selected slot
        graphics.fill(sx, sy, sx + 18, sy + 1, 0xFFFFD700);
        graphics.fill(sx, sy + 17, sx + 18, sy + 18, 0xFFFFD700);
        graphics.fill(sx, sy, sx + 1, sy + 18, 0xFFFFD700);
        graphics.fill(sx + 17, sy, sx + 18, sy + 18, 0xFFFFD700);
    }
}