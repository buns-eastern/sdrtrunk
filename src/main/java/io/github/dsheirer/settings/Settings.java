/*
 * *****************************************************************************
 * Copyright (C) 2014-2024 Dennis Sheirer
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

package io.github.dsheirer.settings;

import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.settings.application.ApplicationSettings;
import io.github.dsheirer.settings.calibration.VectorCalibrationSettings;
import io.github.dsheirer.settings.decoder.JmbeLibrarySettings;
import io.github.dsheirer.settings.directory.DirectorySettings;
import io.github.dsheirer.settings.duplicate.CallManagementSettings;
import io.github.dsheirer.settings.event.DecodeEventSettings;
import io.github.dsheirer.settings.identifier.TalkgroupFormatSettings;
import io.github.dsheirer.settings.javafx.JavaFxSettings;
import io.github.dsheirer.settings.map.MapSettings;
import io.github.dsheirer.settings.mp3.MP3Settings;
import io.github.dsheirer.settings.network.ImbeStreamSettings;
import io.github.dsheirer.settings.network.NetworkStreamSettings;
import io.github.dsheirer.settings.network.PcmStreamSettings;
import io.github.dsheirer.settings.network.StandaloneStreamSettings;
import io.github.dsheirer.settings.network.HeartbeatSettings;
import io.github.dsheirer.settings.network.StreamHeartbeatSettings;
import io.github.dsheirer.settings.network.ChannelHeartbeatSettings;
import io.github.dsheirer.settings.network.SoftwareHeartbeatSettings;
import io.github.dsheirer.settings.playback.PlaybackSettings;
import io.github.dsheirer.settings.playlist.PlaylistSettings;
import io.github.dsheirer.settings.radioreference.RadioReferenceSettings;
import io.github.dsheirer.settings.record.RecordSettings;
import io.github.dsheirer.settings.source.TunerSettings;
import io.github.dsheirer.settings.swing.SwingSettings;
import io.github.dsheirer.sample.Listener;

/**
 * Application settings: a collection of settings groups that can be accessed by settings type.  The stored values are
 * listed in the {@link Setting} enumeration.
 *
 * Note: settings updates are broadcast throughout the system using the Google Guava Event Bus.  Each component
 * can register with the event bus and annotate a method to receive updates:
 *
 * To register a component to receive events, add this in the constructor:
 * MyEventBus.getEventBus().register(this);
 *
 * To receive setting update notifications, annotate a method with @Subscribe and use a SettingsType argument:
 *
 * @Subscribe
 * public void settingsUpdated(SettingsType settingsType)
 * {
 * }
 */
public class Settings implements Listener<SettingsType>
{
    private ApplicationSettings mApplicationSettings;
    private DecodeEventSettings mDecodeEventSettings;
    private DirectorySettings mDirectorySettings;
    private CallManagementSettings mCallManagementSettings;
    private JmbeLibrarySettings mJmbeLibrarySettings;
    private MapSettings mMapSettings;
    private MP3Settings mMP3Settings;
    private NetworkStreamSettings mNetworkStreamSettings;
    private ImbeStreamSettings mImbeStreamSettings;
    private PcmStreamSettings mPcmStreamSettings;
    private StandaloneStreamSettings mStandaloneStreamSettings;
    private HeartbeatSettings mHeartbeatSettings;
    private StreamHeartbeatSettings mStreamHeartbeatSettings;
    private ChannelHeartbeatSettings mChannelHeartbeatSettings;
    private SoftwareHeartbeatSettings mSoftwareHeartbeatSettings;
    private PlaybackSettings mPlaybackSettings;
    private PlaylistSettings mPlaylistSettings;
    private RadioReferenceSettings mRadioReferenceSettings;
    private RecordSettings mRecordSettings;
    private TalkgroupFormatSettings mTalkgroupFormatSettings;
    private TunerSettings mTunerSettings;
    private VectorCalibrationSettings mVectorCalibrationSettings;

    private SwingSettings mSwingSettings = new SwingSettings();
    private JavaFxSettings mJavaFxSettings = new JavaFxSettings();

    /**
     * Constructs a new settings instance
     */
    public Settings()
    {
        loadSettings();
    }

    /**
     * Application general/miscellaneous settings.
     * @return application settings.
     */
    public ApplicationSettings getApplicationSettings()
    {
        return mApplicationSettings;
    }

    /**
     * Vector/SIMD calibration settings.
     * @return vector calibration settings.
     */
    public VectorCalibrationSettings getVectorCalibrationSettings()
    {
        return mVectorCalibrationSettings;
    }

    /**
     * Java FX window management settings
     */
    public JavaFxSettings getJavaFxSettings()
    {
        return mJavaFxSettings;
    }

    /**
     * Decode Event settings
     */
    public DecodeEventSettings getDecodeEventSettings()
    {
        return mDecodeEventSettings;
    }

    /**
     * Decoder settings
     */
    public JmbeLibrarySettings getJmbeLibrarySettings()
    {
        return mJmbeLibrarySettings;
    }

    /**
     * Directory settings
     */
    public DirectorySettings getDirectorySettings()
    {
        return mDirectorySettings;
    }

    /**
     * Audio playback settings
     */
    public PlaybackSettings getPlaybackSettings()
    {
        return mPlaybackSettings;
    }

    /**
     * Playlist settings
     */
    public PlaylistSettings getPlaylistSettings()
    {
        return mPlaylistSettings;
    }

    /**
     * Radio reference web services settings
     */
    public RadioReferenceSettings getRadioReferenceSettings()
    {
        return mRadioReferenceSettings;
    }

    /**
     * Recording settings
     */
    public RecordSettings getRecordSettings()
    {
        return mRecordSettings;
    }

    /**
     * MP3 settings
     */
    public MP3Settings getMP3Settings()
    {
        return mMP3Settings;
    }

    /**
     * Network event (9500) and raw message (9501) stream settings
     */
    public NetworkStreamSettings getNetworkStreamSettings()
    {
        return mNetworkStreamSettings;
    }

    /**
     * IMBE audio stream (9502) settings
     */
    public ImbeStreamSettings getImbeStreamSettings()
    {
        return mImbeStreamSettings;
    }

    /**
     * PCM audio stream (9503) settings
     */
    public PcmStreamSettings getPcmStreamSettings()
    {
        return mPcmStreamSettings;
    }

    /**
     * Standalone channel heartbeat stream (9504) settings
     */
    public StandaloneStreamSettings getStandaloneStreamSettings()
    {
        return mStandaloneStreamSettings;
    }

    /**
     * Heartbeat settings
     */
    public HeartbeatSettings getHeartbeatSettings()
    {
        return mHeartbeatSettings;
    }

    /**
     * StreamHeartbeat settings
     */
    public StreamHeartbeatSettings getStreamHeartbeatSettings()
    {
        return mStreamHeartbeatSettings;
    }

    /**
     * ChannelHeartbeat settings
     */
    public ChannelHeartbeatSettings getChannelHeartbeatSettings()
    {
        return mChannelHeartbeatSettings;
    }

    /**
     * SoftwareHeartbeat settings
     */
    public SoftwareHeartbeatSettings getSoftwareHeartbeatSettings()
    {
        return mSoftwareHeartbeatSettings;
    }

    /**
     * Identifier settings
     */
    public TalkgroupFormatSettings getTalkgroupFormatSettings()
    {
        return mTalkgroupFormatSettings;
    }

    /**
     * Tuner settings
     */
    public TunerSettings getTunerSettings()
    {
        return mTunerSettings;
    }


    /**
     * Window locations and table column widths stored by the earlier Swing version of the application.  Used only to
     * migrate those settings to the JavaFX windows and tables.
     */
    public SwingSettings getSwingSettings()
    {
        return mSwingSettings;
    }

    /**
     * Map view settings: the last center point and zoom level
     */
    public MapSettings getMapSettings()
    {
        return mMapSettings;
    }

    /**
     * Call management and duplicate call detection settings
     */
    public CallManagementSettings getCallManagementSettings()
    {
        return mCallManagementSettings;
    }

    /**
     * Loads the managed settings
     */
    private void loadSettings()
    {
        mApplicationSettings = new ApplicationSettings(this::receive);
        mDecodeEventSettings = new DecodeEventSettings(this::receive);
        mDirectorySettings = new DirectorySettings(this::receive);
        mCallManagementSettings = new CallManagementSettings(this::receive);
        mJmbeLibrarySettings = new JmbeLibrarySettings(this::receive);
        mMapSettings = new MapSettings(this::receive);
        mMP3Settings = new MP3Settings(this::receive);
        mNetworkStreamSettings = new NetworkStreamSettings(this::receive);
        mImbeStreamSettings = new ImbeStreamSettings(this::receive);
        mPcmStreamSettings = new PcmStreamSettings(this::receive);
        mStandaloneStreamSettings = new StandaloneStreamSettings(this::receive);
        mHeartbeatSettings = new HeartbeatSettings(this::receive);
        mStreamHeartbeatSettings = new StreamHeartbeatSettings(this::receive);
        mChannelHeartbeatSettings = new ChannelHeartbeatSettings(this::receive);
        mSoftwareHeartbeatSettings = new SoftwareHeartbeatSettings(this::receive);
        mPlaybackSettings = new PlaybackSettings(this::receive);
        mPlaylistSettings = new PlaylistSettings(this::receive, mDirectorySettings);
        mRadioReferenceSettings = new RadioReferenceSettings(this::receive);
        mRecordSettings = new RecordSettings(this::receive);
        mTalkgroupFormatSettings = new TalkgroupFormatSettings(this::receive);
        mTunerSettings = new TunerSettings(this::receive);
        mVectorCalibrationSettings = new VectorCalibrationSettings(this::receive);
    }

    /**
     * Primary method for receiving notification from a managed setting that a setting type
     * has been changed/updated.
     *
     * @param settingsType that is updated
     */
    @Override
    public void receive(SettingsType settingsType)
    {
        MyEventBus.getGlobalEventBus().post(settingsType);
    }
}
