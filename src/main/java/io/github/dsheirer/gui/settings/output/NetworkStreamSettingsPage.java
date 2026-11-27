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
import io.github.dsheirer.settings.network.NetworkStreamSettings;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import org.controlsfx.control.ToggleSwitch;

/**
 * Settings page for the NetworkStream output.  Text and behavior are the same as the earlier preferences page.
 */
public class NetworkStreamSettingsPage extends OutputSettingsPage
{
    private final NetworkStreamSettings mStreamSettings;

    /**
     * Constructs an instance
     * @param settings to edit
     */
    public NetworkStreamSettingsPage(Settings settings)
    {
        super(SettingsPageType.NETWORK_STREAM, settings);
        mStreamSettings = settings.getNetworkStreamSettings();


        ToggleSwitch enableToggle = new ToggleSwitch();
        enableToggle.setSelected(mStreamSettings.isEnabled());
        enableToggle.selectedProperty().addListener((obs, oldVal, enabled) ->
                mStreamSettings.setEnabled(enabled));

        SettingsSection section = addSection(null);
        section.addRow("Enable Network Streaming", enableToggle);

        Label restartNote = warningNote("Changes take effect after restarting SDRTrunk.");

        section.addContent(restartNote);

        section = addSection("Event Stream  ·  Port");

        Label eventDesc = new Label(
            "Every call event from all monitored P25 Phase 1 and DMR channels — group calls, " +
            "individual calls, affiliations, registrations, data calls.  This is the same data " +
            "that appears in the SDRTrunk Events tab and the daily CSV event log files, delivered " +
            "in real-time as one JSON object per line.\n\n" +
            "Each message is tagged with the system name so a single subscriber can receive " +
            "all monitored systems on one connection and filter client-side by the \"system\" field.");
        eventDesc.setWrapText(true);

        section.addContent(eventDesc);
        section.addContent(portRow("Event port:", mStreamSettings.getEventPort(),
                v -> mStreamSettings.setEventPort(v)));

        section = addSection("Raw Control-Channel Stream  ·  Port");

        Label rawDesc = new Label(
            "Every valid decoded control-channel message, delivered before SDRTrunk processes " +
            "it into a higher-level event:\n\n" +
            "  • P25 Phase 1 — TSBKs (Trunked System Bursts) and AMBTCs such as " +
            "GROUP_VOICE_GRANT, RFSS_STATUS_BCST, ADJACENT_STATUS_BCST, etc.\n\n" +
            "  • DMR — CSBKs (Control Signaling Blocks) and Link Control messages such as " +
            "CSBK ALOHA, CSBK GRANT, LC GROUP VOICE CHANNEL USER, etc.  Voice audio frames " +
            "are filtered out — only signaling messages flow through.\n\n" +
            "This stream is finer-grained than the event stream.  Use it when you need to react " +
            "to specific message types, extract fields SDRTrunk doesn't surface in events, or " +
            "build your own monitoring logic on top of raw protocol data.");
        rawDesc.setWrapText(true);

        section.addContent(rawDesc);
        section.addContent(portRow("Raw port:", mStreamSettings.getRawPort(),
                v -> mStreamSettings.setRawPort(v)));

        section = addSection("Message Format  ·  Newline-delimited JSON (NDJSON)");

        Label formatDesc = new Label("One complete JSON object per line on both streams:");
        formatDesc.setWrapText(true);

        String eventExample =
            "// Event stream (port 9500)\n" +
            "{\n" +
            "  \"pipe\":       \"events\",\n" +
            "  \"system\":     \"MY_SYSTEM\",\n" +
            "  \"timestamp\":  \"2026-05-10 12:17:56\",\n" +
            "  \"durationMs\": 4200,\n" +
            "  \"protocol\":   \"APCO25\",\n" +
            "  \"event\":      \"GROUP_CALL\",\n" +
            "  \"from\":       \"1001234\",\n" +
            "  \"to\":         \"1001\",\n" +
            "  \"frequency\":  \"857.112500\",\n" +
            "  \"details\":    \"ENCRYPTED\",\n" +
            "  \"eventId\":    -1234567890\n" +
            "}\n\n" +
            "// Raw CC stream (port 9501) — P25\n" +
            "{\"pipe\":\"raw_cc\",\"system\":\"MY_SYSTEM\",\"timestamp\":\"2026-05-10 12:17:56\"," +
            "\"message\":\"TSBK OSP GROUP VOICE GRANT  LCN:5  TGP:1001  SU:1001234 ...\"}\n\n" +
            "// Raw CC stream (port 9501) — DMR\n" +
            "{\"pipe\":\"raw\",\"protocol\":\"DMR\",\"system\":\"MY_DMR_SYSTEM\",\"timeslot\":1," +
            "\"timestamp\":\"2026-05-10 12:17:56\",\"message\":\"CSBK ALOHA  ...\"}";

        TextArea formatBox = codeBox(eventExample, 220);

        section.addContent(formatDesc); section.addContent(formatBox);

        section = addSection("Quick Start  ·  Connect with Python");

        Label quickDesc = new Label(
            "Run this on any machine that can reach the SDRTrunk host over the network.  " +
            "Replace the IP address with the IP of the machine running SDRTrunk:");
        quickDesc.setWrapText(true);

        String pythonCode =
            "import socket, json, threading\n\n" +
            "SDRTRUNK_HOST = '192.168.1.100'   # <- change to your SDRTrunk machine's IP\n\n" +
            "def tap(port, label):\n" +
            "    s = socket.create_connection((SDRTRUNK_HOST, port))\n" +
            "    print(f'[{label}] connected')\n" +
            "    for line in s.makefile():\n" +
            "        obj = json.loads(line)\n" +
            "        print(f'[{label}] {obj[\"system\"]} | {obj}')\n\n" +
            "# Tap both pipes at once\n" +
            "threading.Thread(target=tap, args=(9500, 'EVENTS'), daemon=True).start()\n" +
            "threading.Thread(target=tap, args=(9501, 'RAW CC'), daemon=True).start()\n\n" +
            "input('Press Enter to quit\\n')";

        TextArea pythonBox = codeBox(pythonCode, 230);

        Node testTip = tip("You can also test with netcat:   nc 192.168.1.100 9500\n" +
            "or Telnet:                        telnet 192.168.1.100 9500\n" +
            "JSON objects will scroll by in real-time as activity is decoded.", true);

        section.addContent(quickDesc); section.addContent(pythonBox); section.addContent(testTip);
    }
}
