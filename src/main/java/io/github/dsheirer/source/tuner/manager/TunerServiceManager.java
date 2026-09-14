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

package io.github.dsheirer.source.tuner.manager;

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelException;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.playlist.PlaylistManager;
import io.github.dsheirer.util.ThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Manages taking tuners in and out of service.
 *
 * A tuner that is out of service stays fully powered and streaming - spectrum, recording, and the frequency and
 * sample rate controls all continue to work - but the tuner manager will not allocate any channel to it.  This allows
 * a tuner to be retuned, or a new tuner to be evaluated on the bench, without the application immediately claiming it
 * for channel processing.
 *
 * Channels that were running on a tuner when it was taken out of service are remembered so that they can be
 * optionally restarted when the tuner is returned to service.  Traffic channels are never restarted directly - the
 * parent control channel re-creates those on its own.
 *
 * All operations here are built from existing public channel start/stop calls and never touch the tuner hardware,
 * the channelizer, or the sample stream.
 */
public class TunerServiceManager
{
    private static final Logger mLog = LoggerFactory.getLogger(TunerServiceManager.class);
    private static final long RESTART_STAGGER_MS = 750;
    private static TunerServiceManager sInstance;

    private final PlaylistManager mPlaylistManager;
    private final Map<String,List<Channel>> mStoppedChannels = new ConcurrentHashMap<>();

    private TunerServiceManager(PlaylistManager playlistManager)
    {
        mPlaylistManager = playlistManager;
    }

    /**
     * Initializes the singleton instance.  Call once at application startup, after the playlist manager exists.
     * @param playlistManager for access to the channel model and channel processing manager.
     */
    public static synchronized void initialize(PlaylistManager playlistManager)
    {
        if(sInstance == null && playlistManager != null)
        {
            sInstance = new TunerServiceManager(playlistManager);
        }
    }

    /**
     * Singleton instance, or null when the application has not initialized it yet.  Callers must null-check.
     */
    public static TunerServiceManager getInstance()
    {
        return sInstance;
    }

    private ChannelProcessingManager getChannelProcessingManager()
    {
        return mPlaylistManager != null ? mPlaylistManager.getChannelProcessingManager() : null;
    }

    /**
     * Channels currently being processed using a source from this tuner.  Read-only.
     * @param discoveredTuner to query.
     * @return list of channels, possibly empty, never null.
     */
    public List<Channel> getRunningChannels(DiscoveredTuner discoveredTuner)
    {
        ChannelProcessingManager manager = getChannelProcessingManager();

        if(manager == null || discoveredTuner == null || !discoveredTuner.hasTuner())
        {
            return new ArrayList<>();
        }

        try
        {
            return manager.getChannelsForTuner(discoveredTuner.getTuner());
        }
        catch(Throwable t)
        {
            mLog.error("Error identifying channels running on tuner [" + discoveredTuner.getId() + "]", t);
            return new ArrayList<>();
        }
    }

    /**
     * Channels that were stopped when this tuner was taken out of service and are still eligible to be restarted.
     * @param discoveredTuner to query.
     * @return list of channels, possibly empty, never null.
     */
    public List<Channel> getStoppedChannels(DiscoveredTuner discoveredTuner)
    {
        List<Channel> remembered = discoveredTuner != null ? mStoppedChannels.get(discoveredTuner.getId()) : null;

        if(remembered == null)
        {
            return new ArrayList<>();
        }

        List<Channel> eligible = new ArrayList<>();

        for(Channel channel : remembered)
        {
            if(isRestartEligible(channel))
            {
                eligible.add(channel);
            }
        }

        return eligible;
    }

    /**
     * A remembered channel can be restarted when it still exists in the playlist, is not already processing, and is
     * not a traffic channel.  Traffic channels are re-created by their parent control channel.
     */
    private boolean isRestartEligible(Channel channel)
    {
        if(channel == null || channel.isTrafficChannel() || channel.isProcessing())
        {
            return false;
        }

        try
        {
            return mPlaylistManager.getChannelModel().getChannels().contains(channel);
        }
        catch(Throwable t)
        {
            return false;
        }
    }

    /**
     * Takes the tuner out of service: excludes it from channel allocation and stops any channels currently running
     * on it, remembering the standard channels so they can be restarted later.
     *
     * @param discoveredTuner to take out of service.
     * @return the channels that were stopped.
     */
    public List<Channel> takeOutOfService(DiscoveredTuner discoveredTuner)
    {
        List<Channel> stopped = new ArrayList<>();

        if(discoveredTuner == null)
        {
            return stopped;
        }

        //Set the flag first so that nothing new is allocated to this tuner while channels are being stopped.
        try
        {
            discoveredTuner.setInService(false);
        }
        catch(Throwable t)
        {
            mLog.error("Error setting out of service flag for tuner [" + discoveredTuner.getId() + "]", t);
            return stopped;
        }

        ChannelProcessingManager manager = getChannelProcessingManager();
        List<Channel> running = getRunningChannels(discoveredTuner);
        List<Channel> remember = new ArrayList<>();

        for(Channel channel : running)
        {
            //Snapshot the type now - a standard channel can be converted to a traffic channel while running.
            boolean standard = !channel.isTrafficChannel();

            try
            {
                if(manager != null)
                {
                    manager.stop(channel);
                    stopped.add(channel);

                    if(standard)
                    {
                        remember.add(channel);
                    }
                }
            }
            catch(ChannelException ce)
            {
                mLog.error("Error stopping channel [" + channel.getName() + "] while taking tuner [" +
                        discoveredTuner.getId() + "] out of service - " + ce.getMessage());
            }
            catch(Throwable t)
            {
                mLog.error("Error stopping channel [" + channel.getName() + "] while taking tuner [" +
                        discoveredTuner.getId() + "] out of service", t);
            }
        }

        mStoppedChannels.put(discoveredTuner.getId(), remember);
        mLog.info("Tuner [" + discoveredTuner.getId() + "] taken OUT OF SERVICE - stopped [" + stopped.size() +
                "] channel(s), remembered [" + remember.size() + "] for restart");
        return stopped;
    }

    /**
     * Returns the tuner to service so that it can again be allocated channels, and optionally restarts the channels
     * that were stopped when it went out of service.
     *
     * @param discoveredTuner to return to service.
     * @param restartChannels true to restart the remembered channels.
     * @return the channels that will be restarted (restart itself happens on a background thread).
     */
    public List<Channel> returnToService(DiscoveredTuner discoveredTuner, boolean restartChannels)
    {
        List<Channel> toRestart = new ArrayList<>();

        if(discoveredTuner == null)
        {
            return toRestart;
        }

        try
        {
            discoveredTuner.setInService(true);
        }
        catch(Throwable t)
        {
            mLog.error("Error clearing out of service flag for tuner [" + discoveredTuner.getId() + "]", t);
            return toRestart;
        }

        mLog.info("Tuner [" + discoveredTuner.getId() + "] returned to service");

        if(restartChannels)
        {
            toRestart.addAll(getStoppedChannels(discoveredTuner));
        }

        mStoppedChannels.remove(discoveredTuner.getId());

        if(!toRestart.isEmpty())
        {
            //Restart off the UI thread and stagger the starts so that several control channels don't race each other
            //for the same tuner resources.
            final List<Channel> channels = new ArrayList<>(toRestart);
            ThreadPool.CACHED.execute(() -> {
                ChannelProcessingManager manager = getChannelProcessingManager();

                for(Channel channel : channels)
                {
                    if(manager == null)
                    {
                        return;
                    }

                    try
                    {
                        if(isRestartEligible(channel))
                        {
                            manager.start(channel);
                        }
                    }
                    catch(ChannelException ce)
                    {
                        mLog.error("Error restarting channel [" + channel.getName() + "] after returning tuner [" +
                                discoveredTuner.getId() + "] to service - " + ce.getMessage());
                    }
                    catch(Throwable t)
                    {
                        mLog.error("Error restarting channel [" + channel.getName() + "] after returning tuner [" +
                                discoveredTuner.getId() + "] to service", t);
                    }

                    try
                    {
                        TimeUnit.MILLISECONDS.sleep(RESTART_STAGGER_MS);
                    }
                    catch(InterruptedException ie)
                    {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            });
        }

        return toRestart;
    }

    /**
     * Discards any remembered channels for the tuner without changing its service state.
     */
    public void clearStoppedChannels(DiscoveredTuner discoveredTuner)
    {
        if(discoveredTuner != null)
        {
            mStoppedChannels.remove(discoveredTuner.getId());
        }
    }
}
