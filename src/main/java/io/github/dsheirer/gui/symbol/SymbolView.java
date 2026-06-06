/*
 * *****************************************************************************
 * Copyright (C) 2014-2025 Dennis Sheirer
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

package io.github.dsheirer.gui.symbol;

import io.github.dsheirer.module.decode.FeedbackDecoder;
import io.github.dsheirer.sample.Listener;
import java.util.Arrays;
import javafx.application.Platform;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.ScatterChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Label;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Chart display for received QPSK symbols
 */
public class SymbolView extends ChannelView implements Listener<Float>
{
    private static final int SYMBOL_DISPLAY_COUNT = 4800;
    private static final int SYMBOL_QUEUE_SIZE = SYMBOL_DISPLAY_COUNT / 40;
    private final float[] mSymbolQueue = new float[SYMBOL_QUEUE_SIZE];
    private int mSymbolQueuePointer = 0;
    private int mChartSymbolPointer = 0;
    private final NumberAxis mSymbolTiming = new NumberAxis();
    private final NumberAxis mSymbolPhase = new NumberAxis(-Math.PI, Math.PI, Math.PI / 4);
    private final XYChart.Series<Number, Number> mSymbolSeries = new XYChart.Series<>();
    private final ScatterChart<Number, Number> mSymbolChart = new ScatterChart<>(mSymbolTiming, mSymbolPhase);
    private FeedbackDecoder mFeedbackDecoder;

    //Symbol quality (EVM-style) tracking.  For C4FM/4FSK DQPSK the four ideal soft-symbol levels are +/-PI/4 and
    //+/-3PI/4, with decision boundaries at 0 and +/-PI/2.  We measure how far each received symbol lands from its
    //nearest ideal level and report a rolling quality figure so the user can tell if the signal is good and whether
    //antenna/tuner changes are improving or degrading it.
    private static final double QUADRANT_BOUNDARY = Math.PI / 2.0;
    private static final double DECISION_HALF_WIDTH = Math.PI / 4.0; //distance from an ideal level to a boundary
    private static final double QUALITY_SMOOTHING = 0.2; //EMA weight applied to each flushed batch
    private final Label mQualityLabel = new Label("Symbol Quality:  --");
    private static final String COLOR_GOOD = "#639922";
    private static final String COLOR_FAIR = "#EF9F27";
    private static final String COLOR_POOR = "#E24B4A";
    private static final double QUALITY_BAR_WIDTH = 220.0;
    private static final double QUALITY_BAR_HEIGHT = 10.0;
    private final Rectangle mQualityMarker = new Rectangle(2, 16);
    private double mAverageErrorPower = -1.0; //EMA of mean-squared symbol error (radians^2); <0 = uninitialized

    /**
     * Constructs an instance
     */
    public SymbolView()
    {
        mSymbolSeries.setName("xx Demodulated Symbols");

        for(int x = 0; x < SYMBOL_DISPLAY_COUNT; x++)
        {
            mSymbolSeries.getData().add(new XYChart.Data<>(x, 0));
        }

        mSymbolChart.getData().add(mSymbolSeries);
        mSymbolChart.setMaxHeight(Double.MAX_VALUE);
        mSymbolChart.setMaxWidth(Double.MAX_VALUE);
        mSymbolChart.setAnimated(false);
        VBox.setVgrow(mSymbolChart, Priority.ALWAYS);
        //Quality zone bar: 0-50% poor (red), 50-75% fair (amber), 75-100% good (green), with a live marker
        Rectangle poorZone = new Rectangle(0, 3, QUALITY_BAR_WIDTH * 0.50, QUALITY_BAR_HEIGHT);
        poorZone.setFill(Color.web(COLOR_POOR));
        Rectangle fairZone = new Rectangle(QUALITY_BAR_WIDTH * 0.50, 3, QUALITY_BAR_WIDTH * 0.25, QUALITY_BAR_HEIGHT);
        fairZone.setFill(Color.web(COLOR_FAIR));
        Rectangle goodZone = new Rectangle(QUALITY_BAR_WIDTH * 0.75, 3, QUALITY_BAR_WIDTH * 0.25, QUALITY_BAR_HEIGHT);
        goodZone.setFill(Color.web(COLOR_GOOD));
        mQualityMarker.setFill(Color.web("#333333"));
        mQualityMarker.setVisible(false);

        Pane barPane = new Pane(poorZone, fairZone, goodZone, mQualityMarker);
        barPane.setMinSize(QUALITY_BAR_WIDTH, 16);
        barPane.setPrefSize(QUALITY_BAR_WIDTH, 16);
        barPane.setMaxSize(QUALITY_BAR_WIDTH, 16);

        String zoneStyle = "-fx-font-size: 10px; -fx-text-fill: #808080;";
        Label poorLabel = new Label("poor");
        poorLabel.setMinWidth(QUALITY_BAR_WIDTH * 0.50);
        poorLabel.setStyle(zoneStyle);
        Label fairLabel = new Label("fair");
        fairLabel.setMinWidth(QUALITY_BAR_WIDTH * 0.25);
        fairLabel.setStyle(zoneStyle);
        Label goodLabel = new Label("good");
        goodLabel.setStyle(zoneStyle);
        HBox zoneLabels = new HBox(poorLabel, fairLabel, goodLabel);
        zoneLabels.setMaxWidth(QUALITY_BAR_WIDTH);

        VBox barBox = new VBox(1, barPane, zoneLabels);
        HBox header = new HBox(15, mQualityLabel, barBox);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(4, 0, 0, 8));

        Tooltip tooltip = new Tooltip(
            "Symbol quality measures how far each demodulated symbol lands from its ideal level.\n" +
            "RMS error (radians) is the raw measurement; quality % is the same value rescaled:\n" +
            "100% = zero error, 0% = symbols at the decision boundary (0.785 rad RMS).\n\n" +
            "GOOD  >= 75%  (RMS < 0.20 rad)  -  tight symbol rows, clean audio\n" +
            "FAIR  50-74%  (RMS 0.20-0.39 rad)  -  fuzzy rows, occasional dropped frames\n" +
            "POOR  < 50%  (RMS > 0.39 rad)  -  rows smearing, decode breaking up");
        tooltip.setShowDelay(Duration.millis(300));
        Tooltip.install(header, tooltip);

        getChildren().addAll(header, mSymbolChart);
    }

    /**
     * Updates the chart axis label with the currently displayed protocol
     * @param protocol that is being processed/displayed.
     */
    public void setProtocol(String protocol)
    {
        Platform.runLater(() -> mSymbolSeries.setName(protocol + " Demodulated Symbols"));
    }

    /**
     * Registers the decoder with this view and this view then registers as a symbol listener on the decoder.
     * @param feedbackDecoder for this view
     */
    public void setSymbolProvider(FeedbackDecoder feedbackDecoder)
    {
        removeSymbolProvider();

        mFeedbackDecoder = feedbackDecoder;
        mAverageErrorPower = -1.0;
        Platform.runLater(() -> {
            mQualityLabel.setText("Symbol Quality:  --");
            mQualityLabel.setStyle(null);
            mQualityMarker.setVisible(false);
        });

        if(mFeedbackDecoder != null)
        {
            mFeedbackDecoder.setSymbolListener(this);
        }
    }

    /**
     * Removes current symbol provider and unregisters this view from that provider.
     */
    public void removeSymbolProvider()
    {
        if(mFeedbackDecoder != null)
        {
            mFeedbackDecoder.removeSymbolListener();
        }

        mFeedbackDecoder = null;
    }

    @Override
    public void receive(Float symbol)
    {
        if(isShowing())
        {
            mSymbolQueue[mSymbolQueuePointer++] = symbol;

            //Flush the queued symbols to the chart when the queue is full
            if(mSymbolQueuePointer == SYMBOL_QUEUE_SIZE)
            {
                float[] symbolDataCopy = Arrays.copyOf(mSymbolQueue, mSymbolQueue.length);

                //Execute the chart data update on the JavaFX UI thread
                Platform.runLater(() ->
                {
                    double sumSquaredError = 0.0;

                    for(float v : symbolDataCopy)
                    {
                        mSymbolSeries.getData().get(mChartSymbolPointer++).setYValue(v);
                        mChartSymbolPointer %= SYMBOL_DISPLAY_COUNT;

                        double error = v - nearestIdealPhase(v);
                        sumSquaredError += (error * error);
                    }

                    updateQuality(sumSquaredError / symbolDataCopy.length);
                });

                mSymbolQueuePointer = 0;
            }
        }
    }

    /**
     * Returns the nearest ideal soft-symbol phase (+/-PI/4 or +/-3PI/4) for the supplied received symbol value.
     * @param symbol received soft-symbol value in radians.
     * @return nearest ideal phase in radians.
     */
    private static double nearestIdealPhase(double symbol)
    {
        if(symbol >= QUADRANT_BOUNDARY)
        {
            return 3.0 * Math.PI / 4.0;
        }
        else if(symbol >= 0)
        {
            return Math.PI / 4.0;
        }
        else if(symbol > -QUADRANT_BOUNDARY)
        {
            return -Math.PI / 4.0;
        }
        else
        {
            return -3.0 * Math.PI / 4.0;
        }
    }

    /**
     * Smooths the per-batch mean-squared symbol error and updates the quality readout label.  Quality is scaled so
     * that zero error reads 100% and an RMS error at the decision boundary (PI/4) reads 0%.
     * @param batchMeanSquaredError mean of squared symbol errors for the most recent batch (radians^2).
     */
    private void updateQuality(double batchMeanSquaredError)
    {
        if(mAverageErrorPower < 0)
        {
            mAverageErrorPower = batchMeanSquaredError;
        }
        else
        {
            mAverageErrorPower = (QUALITY_SMOOTHING * batchMeanSquaredError) +
                ((1.0 - QUALITY_SMOOTHING) * mAverageErrorPower);
        }

        double rmsError = Math.sqrt(Math.max(0.0, mAverageErrorPower));
        double quality = Math.max(0.0, Math.min(100.0, 100.0 * (1.0 - (rmsError / DECISION_HALF_WIDTH))));

        String rating;
        String color;

        if(quality >= 75.0)
        {
            rating = "GOOD";
            color = COLOR_GOOD;
        }
        else if(quality >= 50.0)
        {
            rating = "FAIR";
            color = COLOR_FAIR;
        }
        else
        {
            rating = "POOR";
            color = COLOR_POOR;
        }

        mQualityLabel.setText(String.format("Symbol Quality: %3.0f%%   (RMS error %.2f rad)   %s", quality, rmsError,
            rating));
        mQualityLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: " + color + ";");
        mQualityMarker.setX(Math.min(QUALITY_BAR_WIDTH - 2.0, QUALITY_BAR_WIDTH * quality / 100.0));
        mQualityMarker.setVisible(true);
    }
}
