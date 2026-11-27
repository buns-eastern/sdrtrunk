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

package io.github.dsheirer.gui.settings;

import io.github.dsheirer.gui.settings.application.AppearanceSettingsPage;
import io.github.dsheirer.gui.settings.backup.BackupSettingsPage;
import io.github.dsheirer.gui.settings.application.GeneralSettingsPage;
import io.github.dsheirer.gui.settings.calibration.PerformanceSettingsPage;
import io.github.dsheirer.gui.settings.call.CallManagementSettingsPage;
import io.github.dsheirer.gui.settings.call.IssiCallMergeSettingsPage;
import io.github.dsheirer.gui.settings.decoder.AudioCodecSettingsPage;
import io.github.dsheirer.gui.settings.directory.StorageSettingsPage;
import io.github.dsheirer.gui.settings.map.MapSettingsPage;
import io.github.dsheirer.gui.settings.output.ChannelHeartbeatSettingsPage;
import io.github.dsheirer.gui.settings.output.HeartbeatMonitorSettingsPage;
import io.github.dsheirer.gui.settings.output.ImbeStreamSettingsPage;
import io.github.dsheirer.gui.settings.output.NetworkStreamSettingsPage;
import io.github.dsheirer.gui.settings.output.PcmStreamSettingsPage;
import io.github.dsheirer.gui.settings.output.SoftwareHeartbeatSettingsPage;
import io.github.dsheirer.gui.settings.output.StandaloneStreamSettingsPage;
import io.github.dsheirer.gui.settings.output.StreamHeartbeatSettingsPage;
import io.github.dsheirer.gui.settings.playback.PlaybackSettingsPage;
import io.github.dsheirer.gui.settings.radioreference.RadioReferenceSettingsPage;
import io.github.dsheirer.gui.settings.record.RecordingSettingsPage;
import io.github.dsheirer.gui.settings.tuner.TunerSettingsPage;
import java.util.function.Function;
import jiconfont.icons.font_awesome.FontAwesome;

/**
 * Pages of the settings dialog, in sidebar order.  Each page belongs to a sidebar group and provides the title, icon
 * and description shown in the sidebar and page header, and creates its page.
 */
public enum SettingsPageType
{
    GENERAL(Group.GENERAL, "General", FontAwesome.SLIDERS,
        "Application health monitoring and channel start-up.",
        context -> new GeneralSettingsPage(context.settings())),
    APPEARANCE(Group.GENERAL, "Appearance", FontAwesome.PAINT_BRUSH,
        "Theme, alias colors and the size of the user interface.",
        context -> new AppearanceSettingsPage(context.settings())),
    PERFORMANCE(Group.GENERAL, "Performance", FontAwesome.TACHOMETER,
        "CPU vector (SIMD) operations and their calibration.",
        context -> new PerformanceSettingsPage(context.settings(), context.channelProcessingManager())),

    TUNERS(Group.RADIO, "Tuners", FontAwesome.SIGNAL,
        "How tuner samples are divided into channels, and tuner specific options.",
        context -> new TunerSettingsPage(context.settings())),
    AUDIO_CODECS(Group.RADIO, "Audio Codecs", FontAwesome.CUBES,
        "The JMBE library that decodes digital voice audio.",
        context -> new AudioCodecSettingsPage(context.settings())),
    FORMATS(Group.RADIO, "Formats", FontAwesome.HASHTAG,
        "How talkgroups, radio identifiers and event timestamps are displayed.",
        context -> new FormatSettingsPage(context.settings())),

    PLAYBACK(Group.AUDIO, "Playback & Tones", FontAwesome.HEADPHONES,
        "Audio output device and the tones inserted into call audio.",
        context -> new PlaybackSettingsPage(context.settings())),
    CALL_MANAGEMENT(Group.AUDIO, "Call Management", FontAwesome.CLONE,
        "Duplicate call detection and suppression, and patch group streaming.",
        context -> new CallManagementSettingsPage(context.settings())),
    ISSI_CALL_MERGE(Group.AUDIO, "ISSI Call Merge", FontAwesome.CODE_FORK,
        "Stream a single copy of a talkgroup that rides on two independent systems (for example via P25 ISSI), no " +
            "matter which system carries the call.",
        context -> new IssiCallMergeSettingsPage(context.settings(), context.playlistManager())),
    RECORDING(Group.AUDIO, "Recording", FontAwesome.MICROPHONE,
        "File formats for audio and baseband I/Q recordings, and MP3 encoding.",
        context -> new RecordingSettingsPage(context.settings())),

    STORAGE(Group.DATA, "Storage", FontAwesome.FOLDER_OPEN,
        "Where sdrtrunk stores its files, and disk usage limits.",
        context -> new StorageSettingsPage(context.settings())),
    BACKUP(Group.DATA, "Backup & Migrate", FontAwesome.ARCHIVE,
        "Save all of your SDRTrunk settings into one backup file. Use it to restore this computer, or to move your " +
            "setup to another computer.",
        context -> new BackupSettingsPage(context.settings())),

    SOFTWARE_HEARTBEAT(Group.EXTERNAL_OUTPUTS, "Software Heartbeat", FontAwesome.DESKTOP,
        "Confirm that SDRTrunk itself is alive by pushing a periodic heartbeat to your monitoring tools.  " +
            "If the application stops or crashes, the heartbeats stop and your monitors alert.",
        context -> new SoftwareHeartbeatSettingsPage(context.settings())),
    HEARTBEAT_MONITOR(Group.EXTERNAL_OUTPUTS, "CC Heartbeat Monitor", FontAwesome.BELL,
        "Continuously verify that your P25 trunking control channels are alive and actively " +
            "decoding — not just tuned but actually receiving valid messages from the tower.",
        context -> new HeartbeatMonitorSettingsPage(context.settings())),
    STREAMING_HEARTBEAT(Group.EXTERNAL_OUTPUTS, "Streaming Heartbeat", FontAwesome.RSS,
        "Continuously report the health of each audio streaming feed to an Uptime Kuma push monitor — one " +
            "independent monitor per stream, whatever streaming types you use.",
        context -> new StreamHeartbeatSettingsPage(context.settings(), context.playlistManager())),
    NETWORK_STREAM(Group.EXTERNAL_OUTPUTS, "Network Stream", FontAwesome.EXCHANGE,
        "Stream live SDRTrunk decode data over TCP to any application on your network. " +
            "Multiple clients can connect simultaneously.  This feature is independent of " +
            "CSV logging — both can be enabled together, or used on their own.",
        context -> new NetworkStreamSettingsPage(context.settings())),
    IMBE_STREAM(Group.EXTERNAL_OUTPUTS, "IMBE Audio Stream", FontAwesome.MUSIC,
        "Stream raw compressed voice frames from every active P25 Phase 1 voice channel " +
            "over TCP in real-time.  A separate application (on this machine or anywhere on " +
            "your LAN) connects to this port and decodes the frames into live audio using the " +
            "same open-source JMBE library that SDRTrunk uses internally.\n\n" +
            "This bypasses SDRTrunk's MP3 recording pipeline entirely, so audio is available " +
            "within ~20 ms of the radio transmitting — before any file is written to disk.",
        context -> new ImbeStreamSettingsPage(context.settings())),
    PCM_STREAM(Group.EXTERNAL_OUTPUTS, "PCM Audio Stream", FontAwesome.VOLUME_UP,
        "Stream decoded 16-bit PCM audio from every active voice channel over TCP in " +
            "real-time.  A separate application (on this machine or anywhere on your LAN) " +
            "connects to this port and plays or processes the audio immediately.\n\n" +
            "Unlike the IMBE stream on port 9502, no JMBE library is needed on the client " +
            "— SDRTrunk has already decoded the audio.  Samples are 16-bit signed " +
            "little-endian at 8000 Hz mono, Base64-encoded in each JSON message.\n\n" +
            "This stream also emits a fast voice_id message the moment a radio unit ID is " +
            "decoded from the Link Control Word (~180 ms after squelch opens), well before " +
            "audio frames arrive.  This lets dispatch or alerting apps identify who is " +
            "talking almost instantly without waiting for the audio pipeline to start.\n\n" +
            "This bypasses SDRTrunk's MP3 recording pipeline entirely, so audio is available " +
            "within ~20 ms of the radio transmitting — before any file is written to disk.",
        context -> new PcmStreamSettingsPage(context.settings())),
    STANDALONE_CHANNEL_HEARTBEAT(Group.EXTERNAL_OUTPUTS, "Standalone Channel Heartbeat", FontAwesome.HEARTBEAT,
        "Emit liveness heartbeats for every running standalone (non-trunking) channel — NBFM, AM, " +
            "conventional DMR, etc. — over TCP, so a separate application can verify which conventional " +
            "channels are actually running.\n\n" +
            "Trunking systems are intentionally excluded: their control-channel traffic already advertises " +
            "liveness on the raw/event streams.  A conventional channel that is running but quiet, however, " +
            "emits nothing elsewhere — so this stream is the way to confirm it is alive.\n\n" +
            "Each running channel announces a channel_up the moment it starts (PLAY), a heartbeat on the " +
            "configured interval while it runs, and a channel_down when it stops.  Demultiplex by the " +
            "\"channel\" field.",
        context -> new StandaloneStreamSettingsPage(context.settings(), context.playlistManager())),
    CHANNEL_HEARTBEAT(Group.EXTERNAL_OUTPUTS, "Channel Heartbeat", FontAwesome.HEARTBEAT,
        "Fire a heartbeat to an external endpoint the moment one of your chosen talkgroups or channels " +
            "becomes active.",
        context -> new ChannelHeartbeatSettingsPage(context.settings(), context.playlistManager())),

    RADIO_REFERENCE(Group.SERVICES, "Radio Reference", FontAwesome.GLOBE,
        "Your Radio Reference account, imports and alias list updates.",
        RadioReferenceSettingsPage::new),
    MAP(Group.SERVICES, "Map", FontAwesome.MAP,
        "Map tiles and the layers shown on the Map tab.",
        context -> new MapSettingsPage(context.settings()));

    private final Group mGroup;
    private final String mTitle;
    private final FontAwesome mIcon;
    private final String mDescription;
    private final Function<SettingsContext,SettingsPage> mFactory;

    SettingsPageType(Group group, String title, FontAwesome icon, String description,
                     Function<SettingsContext,SettingsPage> factory)
    {
        mGroup = group;
        mTitle = title;
        mIcon = icon;
        mDescription = description;
        mFactory = factory;
    }

    /**
     * Sidebar group for the page
     */
    public Group getGroup()
    {
        return mGroup;
    }

    /**
     * Page title for the sidebar and the page header
     */
    public String getTitle()
    {
        return mTitle;
    }

    /**
     * Page icon for the sidebar and the page header
     */
    public FontAwesome getIcon()
    {
        return mIcon;
    }

    /**
     * One line description shown under the page title
     */
    public String getDescription()
    {
        return mDescription;
    }

    /**
     * Creates the page
     * @param context with the settings to edit
     * @return page
     */
    public SettingsPage createPage(SettingsContext context)
    {
        return mFactory.apply(context);
    }

    @Override
    public String toString()
    {
        return mTitle;
    }

    /**
     * Sidebar groups, in sidebar order
     */
    public enum Group
    {
        GENERAL("General"),
        RADIO("Radio"),
        AUDIO("Audio"),
        DATA("Data"),
        EXTERNAL_OUTPUTS("External Outputs"),
        SERVICES("Services");

        private final String mLabel;

        Group(String label)
        {
            mLabel = label;
        }

        @Override
        public String toString()
        {
            return mLabel;
        }
    }
}
