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
import io.github.dsheirer.settings.network.ImbeStreamSettings;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import org.controlsfx.control.ToggleSwitch;

/**
 * Settings page for the ImbeStream output.  Text and behavior are the same as the earlier preferences page.
 */
public class ImbeStreamSettingsPage extends OutputSettingsPage
{
    private final ImbeStreamSettings mStreamSettings;

    /**
     * Constructs an instance
     * @param settings to edit
     */
    public ImbeStreamSettingsPage(Settings settings)
    {
        super(SettingsPageType.IMBE_STREAM, settings);
        mStreamSettings = settings.getImbeStreamSettings();


        // ── Enable / port ─────────────────────────────────────────────────
        ToggleSwitch enableToggle = new ToggleSwitch();
        enableToggle.setSelected(mStreamSettings.isEnabled());
        enableToggle.selectedProperty().addListener((obs, oldVal, enabled) ->
                mStreamSettings.setEnabled(enabled));

        SettingsSection section = addSection(null);
        section.addRow("Enable IMBE Audio Stream", enableToggle);

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

        // ── What is an IMBE frame? ─────────────────────────────────────────
        section = addSection("What Is an IMBE Frame?");
        Label imbeDesc = new Label(
            "P25 Phase 1 voice is compressed using IMBE (Improved Multi-Band Excitation), a " +
            "vocoder codec.  The radio transmits groups of 9 compressed frames called an LDU " +
            "(Link Data Unit).  Each frame is 18 bytes (144 bits) and represents exactly 20 ms " +
            "of audio at 8 kHz.\n\n" +
            "SDRTrunk normally feeds each frame into the JMBE library, which decompresses it " +
            "into 160 PCM float samples, accumulates the samples into an AudioSegment, and " +
            "writes an MP3 file when the call ends.  This stream hands you the 18-byte frames " +
            "directly so your application can decode them in real-time using JMBE — or any " +
            "other IMBE-compatible codec — and play the audio immediately.");
        imbeDesc.setWrapText(true);
        section.addContent(imbeDesc);

        // ── Message format ─────────────────────────────────────────────────
        section = addSection("Message Format  ·  Newline-delimited JSON (NDJSON)");
        Label fmtDesc = new Label(
            "Three message types flow on this port.  Each is a single JSON object terminated " +
            "by a newline (\\n).  Use the \"type\" field to distinguish them:");
        fmtDesc.setWrapText(true);
        section.addContent(fmtDesc);

        String formatExample =
            "// 1. Call started — emitted once when squelch opens\n" +
            "{\n" +
            "  \"type\":      \"call_start\",\n" +
            "  \"callId\":    \"a3f9b2\",       // unique ID for this transmission\n" +
            "  \"system\":    \"MY_SYSTEM\",\n" +
            "  \"talkgroup\": \"1001\",\n" +
            "  \"from\":      \"1001234\",      // source radio unit ID\n" +
            "  \"timestamp\": \"2026-05-20 14:23:01\"\n" +
            "}\n\n" +
            "// 2. IMBE audio frame — 9 per LDU, one every ~20 ms per active channel\n" +
            "{\n" +
            "  \"type\":      \"frame\",\n" +
            "  \"callId\":    \"a3f9b2\",       // matches the call_start above\n" +
            "  \"system\":    \"MY_SYSTEM\",\n" +
            "  \"talkgroup\": \"1001\",\n" +
            "  \"from\":      \"1001234\",\n" +
            "  \"seq\":       4,               // frame counter within this call (0-based)\n" +
            "  \"imbe\":      \"Qx7f2mAbT...\"  // 18 bytes, Base64-encoded\n" +
            "}\n\n" +
            "// 3. Call ended — emitted once when squelch closes\n" +
            "{\n" +
            "  \"type\":      \"call_end\",\n" +
            "  \"callId\":    \"a3f9b2\",\n" +
            "  \"system\":    \"MY_SYSTEM\",\n" +
            "  \"talkgroup\": \"1001\",\n" +
            "  \"frames\":    47              // total frames sent for this call\n" +
            "}";

        section.addContent(codeBox(formatExample, 380));

        // ── Decoding with JMBE ─────────────────────────────────────────────
        section = addSection("Decoding with JMBE  ·  Java");
        Label jmbeDesc = new Label(
            "JMBE is open-source (Apache 2.0) and available at github.com/DSheirer/jmbe.  " +
            "Add the jmbe-1.0.9.jar to your project.  One codec instance must be maintained " +
            "per active call — do NOT reset() between frames within the same call, only at " +
            "call_end.  The codec preserves vocoder state across frames; resetting mid-call " +
            "causes audio artifacts.\n\n" +
            "getAudio() returns 160 float samples at 8 kHz (20 ms of audio).  Feed them " +
            "directly to javax.sound.sampled for playback, or encode with a library like " +
            "LAME or FFmpeg for MP3/Opus output.");
        jmbeDesc.setWrapText(true);
        section.addContent(jmbeDesc);

        String javaCode =
            "import jmbe.iface.*;\nimport java.util.Base64;\n\n" +
            "// One-time setup — load the library\n" +
            "IAudioCodecLibrary lib = new jmbe.JMBEAudioLibrary();\n" +
            "Map<String, IAudioCodec> codecs = new ConcurrentHashMap<>();\n\n" +
            "// On call_start: create a codec instance for this callId\n" +
            "codecs.put(callId, lib.getAudioConverter(\"IMBE\"));\n\n" +
            "// On each frame message:\n" +
            "IAudioCodec codec = codecs.get(callId);\n" +
            "byte[] imbeBytes = Base64.getDecoder().decode(msg.get(\"imbe\").getAsString());\n" +
            "float[] pcm = codec.getAudio(imbeBytes);  // 160 samples @ 8 kHz\n" +
            "// → write pcm to a javax.sound.sampled.SourceDataLine for live playback\n\n" +
            "// On call_end: flush and release\n" +
            "IAudioCodec done = codecs.remove(callId);\n" +
            "if (done != null) done.reset();";

        section.addContent(codeBox(javaCode, 220));

        // ── Quick start Python ─────────────────────────────────────────────
        section = addSection("Quick Start  ·  Inspect the stream with Python");
        Label pyDesc = new Label(
            "This snippet connects and prints every message.  Replace the IP address with the " +
            "address of the machine running SDRTrunk:");
        pyDesc.setWrapText(true);
        section.addContent(pyDesc);

        String pythonCode =
            "import socket, json\n\n" +
            "SDRTRUNK_HOST = '192.168.1.100'   # <- your SDRTrunk machine\n" +
            "IMBE_PORT     = 9502\n\n" +
            "s = socket.create_connection((SDRTRUNK_HOST, IMBE_PORT))\n" +
            "print(f'Connected to IMBE stream on {SDRTRUNK_HOST}:{IMBE_PORT}')\n\n" +
            "for raw_line in s.makefile():\n" +
            "    msg = json.loads(raw_line)\n" +
            "    t = msg['type']\n\n" +
            "    if t == 'call_start':\n" +
            "        print(f'[START] TG {msg[\"talkgroup\"]} | FROM {msg[\"from\"]} | call {msg[\"callId\"]}')\n" +
            "    elif t == 'frame':\n" +
            "        import base64\n" +
            "        raw = base64.b64decode(msg['imbe'])   # 18 bytes — feed to IMBE decoder\n" +
            "        print(f'  frame #{msg[\"seq\"]:04d}  {raw.hex()}')\n" +
            "    elif t == 'call_end':\n" +
            "        print(f'[END]   TG {msg[\"talkgroup\"]} | {msg[\"frames\"]} frames | call {msg[\"callId\"]}')";

        section.addContent(codeBox(pythonCode, 255));

        Node tip = tip("Multiple clients can connect simultaneously — each receives its own copy of " +
            "the full stream.  Filter client-side by \"talkgroup\" or \"system\" to isolate " +
            "specific channels.  The \"seq\" field increments per call (not globally), so a " +
            "gap in seq numbers within the same callId indicates lost frames.", false);
        section.addContent(tip);
    }
}
