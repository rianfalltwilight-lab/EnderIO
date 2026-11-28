package com.enderio.enderio.client.content.conduits.gui.screen_type;

import com.enderio.core.common.util.TooltipUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import com.enderio.enderio.EnderIO;
import com.enderio.enderio.api.conduits.screen.ConduitMenuDataAccess;
import com.enderio.enderio.api.conduits.screen.ConduitScreenHelper;
import com.enderio.enderio.api.conduits.screen.IOConduitScreenType;
import com.enderio.enderio.content.conduits.ConduitLang;
import com.enderio.enderio.content.conduits.type.item.ItemConduit;
import com.enderio.enderio.content.conduits.type.item.ItemConduitConnectionConfig;
import com.enderio.enderio.foundation.lang.EIOCommonLang;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

public class ItemConduitScreenType extends IOConduitScreenType<ItemConduitConnectionConfig> {

    private static final ResourceLocation ICON_ROUND_ROBIN_ENABLED = EnderIO.rl("icon/round_robin_enabled");
    private static final ResourceLocation ICON_ROUND_ROBIN_DISABLED = EnderIO.rl("icon/round_robin_disabled");
    private static final ResourceLocation ICON_SELF_FEED_ENABLED = EnderIO.rl("icon/self_feed_enabled");
    private static final ResourceLocation ICON_SELF_FEED_DISABLED = EnderIO.rl("icon/self_feed_disabled");

    private static final ResourceLocation ICON_BALANCED_ENABLED = EnderIO.rl("icon/balanced_enabled");
    private static final ResourceLocation ICON_BALANCED_DISABLED = EnderIO.rl("icon/balanced_disabled");

    private static final ResourceLocation ICON_INCREASE = EnderIO.rl("icon/increase");
    private static final ResourceLocation ICON_DECREASE = EnderIO.rl("icon/decrease");

    @Override
    public void renderLabels(ConduitMenuDataAccess<ItemConduitConnectionConfig> dataAccess, GuiGraphics guiGraphics,
            int startX, int startY, Font font, int mouseX, int mouseY) {
        super.renderLabels(dataAccess, guiGraphics, startX, startY, font, mouseX, mouseY);

        guiGraphics.drawString(font, ConduitLang.PRIORITY, 22, 7 + 4 + 4 + 8 + 16 + 12, 0x000000, false);

        int interval = dataAccess.getConnectionConfig().extractInterval();
        Component intervalText = interval == 0 ? ConduitLang.EXTRACT_INTERVAL_DEFAULT
                : TooltipUtil.withArgs(ConduitLang.EXTRACT_INTERVAL_TICKS.copy(), interval);
        guiGraphics.drawString(font, intervalText, 134, 81, 0x000000, false);

        // TODO: Show redstone signal indicators using the extra NBT payload.
    }

    private int intervalStep() {
        if (Screen.hasControlDown()) {
            return 200;
        }

        return Screen.hasShiftDown() ? 100 : 10;
    }

    private int getIncrement() {
        if (Screen.hasControlDown()) {
            return 100;
        }

        if (Screen.hasShiftDown()) {
            return 10;
        }

        return 1;
    }

    @Override
    public void createLeftWidgets(ConduitScreenHelper screen, int startX, int startY,
            ConduitMenuDataAccess<ItemConduitConnectionConfig> dataAccess) {
        super.createLeftWidgets(screen, startX, startY, dataAccess);

        // Channel color
        screen.addColorPicker(startX, startY + 20, ConduitLang.CHANNEL,
                () -> dataAccess.getConnectionConfig().insertChannel(),
                value -> dataAccess.updateConnectionConfig(config -> config.withInsertChannel(value)));

        screen.addFilterConfigureButton(startX + 1, startY + 82, ItemConduit.INSERT_FILTER_SLOT);

        // Priority, typed or stepped
        var priorityBox = new EditBox(Minecraft.getInstance().font, startX + 39, startY + 40, 40, 14, ConduitLang.PRIORITY);
        priorityBox.setMaxLength(5);
        priorityBox.setFilter(text -> text.matches("-?\\d{0,4}"));
        priorityBox.setValue(String.valueOf(dataAccess.getConnectionConfig().priority()));
        priorityBox.setResponder(text -> {
            if (text.isEmpty() || text.equals("-")) {
                return;
            }

            int typed = Integer.parseInt(text);
            if (typed != dataAccess.getConnectionConfig().priority()) {
                dataAccess.updateConnectionConfig(config -> config.withPriority(typed));
            }
        });
        screen.addRenderableWidget(priorityBox);
        screen.addPreRenderAction(() -> {
            // Follow changes made elsewhere, without fighting the player's typing.
            String current = String.valueOf(dataAccess.getConnectionConfig().priority());
            if (!priorityBox.isFocused() && !priorityBox.getValue().equals(current)) {
                priorityBox.setValue(current);
            }
        });

        // Priority up/down
        screen.addIconButton(startX + 80, startY + 38, 9, 9, Component.empty(), ICON_INCREASE, () -> dataAccess
                .updateConnectionConfig(config -> config.withPriority(config.priority() + getIncrement())));
        screen.addIconButton(startX + 80, startY + 38 + 9, 9, 9, Component.empty(), ICON_DECREASE, () -> dataAccess
                .updateConnectionConfig(config -> config.withPriority(config.priority() - getIncrement())));
    }

    @Override
    public void createRightWidgets(ConduitScreenHelper screen, int startX, int startY,
            ConduitMenuDataAccess<ItemConduitConnectionConfig> dataAccess) {
        super.createRightWidgets(screen, startX, startY, dataAccess);

        // Channel color
        screen.addColorPicker(startX, startY + 20, ConduitLang.CHANNEL,
                () -> dataAccess.getConnectionConfig().extractChannel(),
                value -> dataAccess.updateConnectionConfig(config -> config.withExtractChannel(value)));

        // Round robin
        screen.addToggleButton(startX + 16 + 4, startY + 20, 16, 16, ConduitLang.ROUND_ROBIN_ENABLED,
                ConduitLang.ROUND_ROBIN_DISABLED, ICON_ROUND_ROBIN_ENABLED, ICON_ROUND_ROBIN_DISABLED,
                () -> dataAccess.getConnectionConfig().isRoundRobin(),
                value -> dataAccess.updateConnectionConfig(config -> config.withIsRoundRobin(value)));

        // Self feed
        screen.addToggleButton(startX + (16 + 4) * 2, startY + 20, 16, 16, ConduitLang.SELF_FEED_ENABLED,
                ConduitLang.SELF_FEED_DISABLED, ICON_SELF_FEED_ENABLED, ICON_SELF_FEED_DISABLED,
                () -> dataAccess.getConnectionConfig().isSelfFeed(),
                value -> dataAccess.updateConnectionConfig(config -> config.withIsSelfFeed(value)));

        // Redstone control
        var redstoneChannelWidget = screen.addColorPicker(startX + 16 + 4, startY + 40, ConduitLang.REDSTONE_CHANNEL,
                () -> dataAccess.getConnectionConfig().extractRedstoneChannel(),
                value -> dataAccess.updateConnectionConfig(config -> config.withExtractRedstoneChannel(value)));

        // Only show the redstone widget when redstone control is sensitive to signals.
        screen.addPreRenderAction(() -> redstoneChannelWidget.visible = dataAccess.getConnectionConfig()
                .extractRedstoneControl()
                .isRedstoneSensitive());

        screen.addRedstoneControlPicker(startX, startY + 40, EIOCommonLang.REDSTONE_MODE,
                () -> dataAccess.getConnectionConfig().extractRedstoneControl(),
                value -> dataAccess.updateConnectionConfig(config -> config.withExtractRedstoneControl(value)));

        screen.addFilterConfigureButton(startX + 1, startY + 82, ItemConduit.EXTRACT_FILTER_SLOT);

        // Balanced delivery
        screen.addToggleButton(startX + (16 + 4) * 3, startY + 20, 16, 16, ConduitLang.BALANCED_ENABLED,
                ConduitLang.BALANCED_DISABLED, ICON_BALANCED_ENABLED, ICON_BALANCED_DISABLED,
                () -> dataAccess.getConnectionConfig().isBalanced(),
                value -> dataAccess.updateConnectionConfig(config -> config.withIsBalanced(value)));

        // Extraction interval, 10 ticks a step (shift 100, control 200)
        screen.addIconButton(startX + 68, startY + 62, 9, 9, ConduitLang.EXTRACT_INTERVAL, ICON_INCREASE,
                () -> dataAccess.updateConnectionConfig(
                        config -> config.withExtractInterval(config.extractInterval() + intervalStep())));
        screen.addIconButton(startX + 68, startY + 71, 9, 9, ConduitLang.EXTRACT_INTERVAL, ICON_DECREASE,
                () -> dataAccess.updateConnectionConfig(
                        config -> config.withExtractInterval(config.extractInterval() - intervalStep())));
    }

    @Override
    protected ItemConduitConnectionConfig setLeftEnabled(ItemConduitConnectionConfig config, boolean isEnabled) {
        return config.withIsInsert(isEnabled);
    }

    @Override
    protected ItemConduitConnectionConfig setRightEnabled(ItemConduitConnectionConfig config, boolean isEnabled) {
        return config.withIsExtract(isEnabled);
    }
}
