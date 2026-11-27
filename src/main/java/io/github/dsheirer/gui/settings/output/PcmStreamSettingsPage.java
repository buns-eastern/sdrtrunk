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

package io.github.dsheirer.gui.settings.output;

import io.github.dsheirer.gui.settings.SettingsPageType;
import io.github.dsheirer.gui.settings.SettingsSection;
import io.github.dsheirer.settings.Settings;
import io.github.dsheirer.settings.network.PcmStreamSettings;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import org.controlsfx.control.ToggleSwitch;

/**
 * Settings page for the PcmStream output.  Text and behavior are the same as the earlier preferences page.
 */
public class PcmStreamSettingsPage extends OutputSettingsPage
{
    private final PcmStreamSettings mStreamSettings;

    /**
     * Constructs an instance
     * @param settings to edit
     */
    public PcmStreamSettingsPage(Settings settings)
    {
        super(SettingsPageType.PCM_STREAM, settings);
        mStreamSettings = settings.getPcmStreamSettings();


        // ── Enable / port ─────────────────────────────────────────────────
        ToggleSwitch enableToggle = new ToggleSwitch();
        enableToggle.setSelected(mStreamSettings.isEnabled());
        enableToggle.selectedProperty().addListener((obs, oldVal, enabled) ->
                mStreamSettings.setEnabled(enabled));

        SettingsSection section = addSection(null);
        section.addRow("Enable PCM Audio Stream", enableToggle);

        Label restartNote = warningNote("Changes take effect after restarting SDRTrunk.");

        section.addContent(restartNote);

        // Port row
        Label portLabel = new Label("TCP port:");
        portLabel.setMinWidth(90);
        Spinner<Integer> portSpinner = new Spinner<>();
        portSpinner.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(1024, 65535, mStreamSettings.getPort()));
        portSpinner.setEditable(true);
        portSpinner.setPrefWidth(100);
        portSpinner.valueProperty().addListener((obs, oldVal, newVal) ->
        {
            if (newVal != null) mStreamSettings.setPort(newVal);
        });
        HBox portRow = new HBox(10, portLabel, portSpinner);
        portRow.setAlignment(Pos.CENTER_LEFT);

        section.addContent(portRow);

        // ── Message format ─────────────────────────────────────────────────
        section = addSection("Message Format  ·  Newline-delimited JSON (NDJSON)");
        Label fmtDesc = new Label(
            "Four message types flow on this port.  Each is a single JSON object terminated " +
            "by a newline (\\n).  Use the \"type\" field to distinguish them.  The \"system\" " +
            "and \"site\" fields reflect the names you configured in SDRTrunk for that channel " +
            "— filter client-side on these to isolate specific systems:");
        fmtDesc.setWrapText(true);
        section.addContent(fmtDesc);

        String formatExample =
            "// 1. Fast unit ID -- fires ~180ms after squelch opens (before audio starts)\n" +
            "//    Repeats on every LDU1 throughout the call -- deduplicate by talkgroup+from\n" +
            "{\"type\":\"voice_id\",\"system\":\"MySystem\",\"site\":\"MySite\",\"talkgroup\":\"12345\",\"from\":\"1234567\",\"timestamp\":\"2026-01-01 12:00:00\"}\n\n" +
            "// 2. Call started -- emitted once when the audio pipeline opens (~360ms after squelch)\n" +
            "{\"type\":\"call_start\",\"callId\":\"a1b2c3d\",\"system\":\"MySystem\",\"site\":\"MySite\",\"talkgroup\":\"12345\",\"from\":\"1234567\",\"timestamp\":\"2026-01-01 12:00:00\"}\n\n" +
            "// 3. PCM audio chunk -- one per decoded audio buffer (~20 ms per active channel)\n" +
            "{\"type\":\"pcm\",\"callId\":\"a1b2c3d\",\"system\":\"MySystem\",\"site\":\"MySite\",\"talkgroup\":\"12345\",\"from\":\"1234567\",\"seq\":0,\"samples\":\"BASE64_ENCODED_PCM\"}\n" +
            "// samples = Base64-encoded 16-bit signed little-endian PCM at 8000 Hz mono\n\n" +
            "// 4. Call ended -- emitted once when squelch closes\n" +
            "{\"type\":\"call_end\",\"callId\":\"a1b2c3d\",\"system\":\"MySystem\",\"site\":\"MySite\",\"talkgroup\":\"12345\",\"frames\":90}";

        section.addContent(codeBox(formatExample, 200));

        // ── Python playback example ────────────────────────────────────────
        section = addSection("Quick Start  ·  Playback with Python");
        Label pyDesc = new Label(
            "This snippet connects and plays audio in real-time.  Requires numpy and " +
            "sounddevice (pip install numpy sounddevice).  Replace the IP address with the " +
            "address of the machine running SDRTrunk:");
        pyDesc.setWrapText(true);
        section.addContent(pyDesc);

        String pythonCode =
            "import socket, json, base64, numpy as np, sounddevice as sd\n\n" +
            "SDRTRUNK_HOST = '192.168.1.100'   # <- your SDRTrunk machine\n" +
            "PCM_PORT      = 9503\n\n" +
            "s = socket.create_connection((SDRTRUNK_HOST, PCM_PORT))\n" +
            "print(f'Connected to PCM stream on {SDRTRUNK_HOST}:{PCM_PORT}')\n\n" +
            "for raw_line in s.makefile():\n" +
            "    msg = json.loads(raw_line)\n" +
            "    t = msg['type']\n\n" +
            "    if t == 'voice_id':\n" +
            "        # Fires ~180ms after squelch -- fastest possible unit ID\n" +
            "        print(f'[ID]    TG {msg[\"talkgroup\"]} | FROM {msg[\"from\"]} | {msg[\"system\"]} / {msg[\"site\"]}')\\n" +
            "    elif t == 'call_start':\n" +
            "        print(f'[START] TG {msg[\"talkgroup\"]} | FROM {msg[\"from\"]} | call {msg[\"callId\"]}')\n" +
            "    elif t == 'pcm':\n" +
            "        samples = np.frombuffer(base64.b64decode(msg['samples']), dtype='<i2').astype(np.float32) / 32767\n" +
            "        sd.play(samples, 8000)\n" +
            "    elif t == 'call_end':\n" +
            "        print(f'[END]   TG {msg[\"talkgroup\"]} | {msg[\"frames\"]} frames | call {msg[\"callId\"]}')";

        section.addContent(codeBox(pythonCode, 300));

        // ── Audio format details ───────────────────────────────────────────
        section = addSection("Audio Format Details");
        Label audioDesc = new Label(
            "The \"samples\" field in each pcm message contains Base64-encoded raw audio:\n\n" +
            "  • Encoding:    16-bit signed integer (int16)\n" +
            "  • Byte order:  little-endian\n" +
            "  • Sample rate: 8000 Hz\n" +
            "  • Channels:    mono\n\n" +
            "Conversion from the internal float representation uses:\n" +
            "    (short) Math.max(-32768, Math.min(32767, Math.round(sample * 32767f)))\n\n" +
            "To decode in Python:  np.frombuffer(base64.b64decode(msg['samples']), dtype='<i2')\n" +
            "To decode in Java:    ByteBuffer.wrap(Base64.getDecoder().decode(b64)).order(ByteOrder.LITTLE_ENDIAN)\n\n" +
            "Each chunk typically contains 160 samples (20 ms at 8 kHz), matching one IMBE " +
            "frame worth of audio, though the exact count may vary by decoder.");
        audioDesc.setWrapText(true);
        section.addContent(audioDesc);

        Node tip = tip("Multiple clients can connect simultaneously — each receives its own copy of " +
            "the full stream.  Filter client-side by \"system\", \"site\", or \"talkgroup\" to isolate " +
            "specific channels.  The \"seq\" field increments per call (not globally), so a " +
            "gap in seq numbers within the same callId indicates dropped chunks.", false);
        section.addContent(tip);
    }
}
