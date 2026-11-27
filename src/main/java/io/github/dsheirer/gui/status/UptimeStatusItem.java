/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */

package io.github.dsheirer.gui.status;

import io.github.dsheirer.monitor.SystemUptime;
import io.github.dsheirer.settings.Setting;
import java.util.function.Supplier;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.util.Duration;

/**
 * Status bar item showing how long the computer, or sdrtrunk, has been running.
 */
public class UptimeStatusItem extends StatusItem
{
    private static final String ICON_MONITOR =
        "M21 2H3c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h7v2H8v2h8v-2h-2v-2h7c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm0 14H3V4h18v12z";
    private static final String ICON_BROADCAST =
        "M12 11c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm6-3l-1.4 1.4C17.9 10.6 18.5 12 18.5 13.5c0 1.5-.6 2.9-1.9 " +
        "4.1L18 19c1.7-1.5 2.5-3.5 2.5-5.5S19.7 9.5 18 8zM6 13.5c0-1.5.6-2.9 1.9-4.1L6.5 8C4.8 9.5 4 11.5 4 13.5s.8 4 " +
        "2.5 5.5l1.4-1.4C6.6 16.4 6 15 6 13.5z";

    private final Label mValue = new Label();
    private final Supplier<String> mUptime;
    private final Timeline mTimeline;

    private UptimeStatusItem(String title, Setting shownSetting, String iconPath, String tooltip,
                             Supplier<String> uptime)
    {
        super(title, shownSetting, false);
        mUptime = uptime;

        Region icon = new Region();
        icon.getStyleClass().add("status-uptime-icon");
        icon.setStyle("-fx-shape: \"" + iconPath + "\";");
        icon.setMinSize(14, 14);
        icon.setMaxSize(14, 14);

        Label name = new Label(title);
        name.getStyleClass().add("status-uptime-name");
        mValue.getStyleClass().add("status-uptime-value");
        getChildren().addAll(icon, name, mValue);
        setTooltipText(tooltip);

        mTimeline = new Timeline(new KeyFrame(Duration.seconds(15), event -> update()));
        mTimeline.setCycleCount(Timeline.INDEFINITE);
        mTimeline.play();
        update();
    }

    /**
     * Computer uptime item.
     */
    public static UptimeStatusItem computer()
    {
        SystemUptime.requestBootTime();
        return new UptimeStatusItem("Computer", Setting.MAIN_WINDOW_STATUS_ITEM_COMPUTER_UPTIME, ICON_MONITOR,
            "How long this computer has been running (days, hours, minutes)", SystemUptime::getMachineUptime);
    }

    /**
     * Sdrtrunk uptime item.
     */
    public static UptimeStatusItem sdrtrunk()
    {
        return new UptimeStatusItem("sdrtrunk", Setting.MAIN_WINDOW_STATUS_ITEM_SDRTRUNK_UPTIME, ICON_BROADCAST,
            "How long sdrtrunk has been running (days, hours, minutes)", SystemUptime::getSdrtrunkUptime);
    }

    @Override
    protected void update()
    {
        mValue.setText(mUptime.get());
    }

    @Override
    public void dispose()
    {
        mTimeline.stop();
    }
}
