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

package io.github.dsheirer.discovery;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelEvent;
import io.github.dsheirer.dsp.filter.channelizer.PolyphaseChannelManager;
import io.github.dsheirer.playlist.PlaylistManager;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.source.ChannelizerType;
import io.github.dsheirer.source.config.SourceConfigTuner;
import io.github.dsheirer.source.config.SourceConfigTunerMultipleFrequency;
import io.github.dsheirer.source.config.SourceConfiguration;
import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Coordinates per-tuner discovery monitors, the hit list, clip retention, offline clip analysis and persistence.
 * This class never writes to the playlist; it only reads channel frequencies to build the exclusion mask.
 */
public class DiscoveryManager implements DiscoveryMonitor.Listener
{
    private static final Logger mLog = LoggerFactory.getLogger(DiscoveryManager.class);
    private static final long ANALYSIS_DELAY_MS = 2000;

    public interface HitListener
    {
        void hitsChanged();
        void statusChanged(String tunerId, String status);
    }

    private final TunerManager mTunerManager;
    private final PlaylistManager mPlaylistManager;
    private final UserPreferences mUserPreferences;
    private final DiscoveryPreference mPreference;
    private final Map<String,DiscoveryMonitor> mMonitors = new ConcurrentHashMap<>();
    private final Map<String,DiscoveryHit> mHits = new ConcurrentHashMap<>();
    private final Set<Long> mIgnoredFrequencies = Collections.synchronizedSet(new HashSet<>());
    private final List<HitListener> mListeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService mExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sdrtrunk discovery");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService mAnalyzer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sdrtrunk discovery analyzer");
        t.setDaemon(true);
        return t;
    });
    private final ObjectMapper mMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final Path mDirectory;
    private final Path mClipDirectory;
    private final Path mHitsFile;
    private volatile boolean mDirty = false;

    public DiscoveryManager(TunerManager tunerManager, PlaylistManager playlistManager, UserPreferences userPreferences)
    {
        mTunerManager = tunerManager;
        mPlaylistManager = playlistManager;
        mUserPreferences = userPreferences;
        mPreference = userPreferences.getDiscoveryPreference();
        mDirectory = userPreferences.getDirectoryPreference().getDirectoryApplicationRoot().resolve("discovery");
        mClipDirectory = mDirectory.resolve("clips");
        mHitsFile = mDirectory.resolve("hits.json");

        try
        {
            Files.createDirectories(mClipDirectory);
        }
        catch(IOException e)
        {
            mLog.error("Couldn't create discovery directory [" + mClipDirectory + "]", e);
        }

        mIgnoredFrequencies.addAll(mPreference.getIgnoredFrequencies());
        load();

        //Playlist changes rebuild the exclusion mask
        mPlaylistManager.getChannelModel().addListener(this::channelChanged);

        mExecutor.scheduleAtFixedRate(this::housekeeping, 30, 60, TimeUnit.SECONDS);
    }

    private void channelChanged(ChannelEvent event)
    {
        mExecutor.execute(this::refreshExclusions);
    }

    public DiscoveryPreference getPreference()
    {
        return mPreference;
    }

    public Path getClipDirectory()
    {
        return mClipDirectory;
    }

    public void addListener(HitListener listener)
    {
        mListeners.add(listener);
    }

    public void removeListener(HitListener listener)
    {
        mListeners.remove(listener);
    }

    private void notifyHitsChanged()
    {
        mDirty = true;

        for(HitListener listener : mListeners)
        {
            listener.hitsChanged();
        }
    }

    private void notifyStatus(String tunerId, String status)
    {
        for(HitListener listener : mListeners)
        {
            listener.statusChanged(tunerId, status);
        }
    }

    /**
     * Indicates if the application is configured to use the polyphase channelizer.  Discovery taps the polyphase
     * channelizer's per-bin output, so it cannot operate when the heterodyne channelizer is selected - heterodyne
     * creates a single tuned down-converter per channel on demand and never produces full-spectrum bin results.
     *
     * @return true when polyphase is selected, and true as a safe default if the preference can't be read.
     */
    public boolean isPolyphaseChannelizer()
    {
        try
        {
            return mUserPreferences.getTunerPreference().getChannelizerType() == ChannelizerType.POLYPHASE;
        }
        catch(Throwable t)
        {
            return true;
        }
    }

    /**
     * Available tuners that support discovery (polyphase channelizer based), whether or not currently monitored.
     */
    public List<DiscoveredTuner> getDiscoveryCapableTuners()
    {
        List<DiscoveredTuner> tuners = new ArrayList<>();

        for(DiscoveredTuner discoveredTuner : mTunerManager.getDiscoveredTunerModel().getAvailableTuners())
        {
            if(discoveredTuner.hasTuner() && discoveredTuner.getTuner().getChannelSourceManager() != null &&
               discoveredTuner.getTuner().getChannelSourceManager().getPolyphaseChannelManager() != null)
            {
                tuners.add(discoveredTuner);
            }
        }

        return tuners;
    }

    public boolean isMonitoring(String tunerId)
    {
        DiscoveryMonitor monitor = mMonitors.get(tunerId);
        return monitor != null && monitor.isRunning();
    }

    public DiscoveryMonitor getMonitor(String tunerId)
    {
        return mMonitors.get(tunerId);
    }

    /**
     * Settings for the tuner, loaded from preferences and created on demand.
     */
    public DiscoverySettings getSettings(String tunerId)
    {
        DiscoveryMonitor monitor = mMonitors.get(tunerId);

        if(monitor != null)
        {
            return monitor.getSettings();
        }

        return mPreference.loadSettings(tunerId);
    }

    /**
     * Starts discovery on the tuner.
     */
    public boolean start(DiscoveredTuner discoveredTuner)
    {
        if(discoveredTuner == null || !discoveredTuner.hasTuner())
        {
            return false;
        }

        Tuner tuner = discoveredTuner.getTuner();
        PolyphaseChannelManager channelManager = tuner.getChannelSourceManager().getPolyphaseChannelManager();

        if(channelManager == null)
        {
            return false;
        }

        String tunerId = discoveredTuner.getId();
        DiscoveryMonitor monitor = mMonitors.get(tunerId);

        if(monitor == null)
        {
            DiscoverySettings settings = mPreference.loadSettings(tunerId);
            monitor = new DiscoveryMonitor(tunerId, channelManager, tuner.getTunerController(), settings,
                mClipDirectory, this);
            mMonitors.put(tunerId, monitor);
        }

        monitor.setExcludedFrequencies(getExcludedFrequencies());
        monitor.start();
        mPreference.setEnabled(tunerId, true);
        notifyStatus(tunerId, "Monitoring");
        return true;
    }

    public void stop(String tunerId)
    {
        DiscoveryMonitor monitor = mMonitors.get(tunerId);

        if(monitor != null)
        {
            monitor.stop();
        }

        mPreference.setEnabled(tunerId, false);

        for(DiscoveryHit hit : mHits.values())
        {
            if(hit.getTunerId().equals(tunerId) && hit.isActive())
            {
                hit.setActive(false);
            }
        }

        notifyStatus(tunerId, "Stopped");
        notifyHitsChanged();
    }

    /**
     * Saves current settings for the tuner to preferences.
     */
    public void saveSettings(String tunerId, DiscoverySettings settings)
    {
        mPreference.saveSettings(tunerId, settings);
    }

    /**
     * Auto-starts monitors on tuners that were enabled when the application last ran.  Call once tuners are up.
     */
    public void autoStart()
    {
        for(DiscoveredTuner discoveredTuner : getDiscoveryCapableTuners())
        {
            if(mPreference.isEnabled(discoveredTuner.getId()))
            {
                start(discoveredTuner);
            }
        }
    }

    /**
     * All playlist channel frequencies plus user-ignored frequencies.
     */
    private Set<Long> getExcludedFrequencies()
    {
        Set<Long> frequencies = new HashSet<>(mIgnoredFrequencies);

        for(Channel channel : mPlaylistManager.getChannelModel().getChannels())
        {
            SourceConfiguration source = channel.getSourceConfiguration();

            if(source instanceof SourceConfigTunerMultipleFrequency multi)
            {
                frequencies.addAll(multi.getFrequencies());
            }
            else if(source instanceof SourceConfigTuner single)
            {
                frequencies.add(single.getFrequency());
            }
        }

        return frequencies;
    }

    private void refreshExclusions()
    {
        Set<Long> excluded = getExcludedFrequencies();

        for(DiscoveryMonitor monitor : mMonitors.values())
        {
            monitor.setExcludedFrequencies(excluded);
        }
    }

    public List<DiscoveryHit> getHits()
    {
        return new ArrayList<>(mHits.values());
    }

    public DiscoveryHit getHit(String key)
    {
        return mHits.get(key);
    }

    /**
     * Marks the hit ignored and adds its frequency to the exclusion list so it never fires again.
     */
    public void ignore(DiscoveryHit hit)
    {
        hit.setStatus(DiscoveryHit.Status.IGNORED);
        hit.setActive(false);
        mIgnoredFrequencies.add(hit.getBinFrequency());
        mPreference.setIgnoredFrequencies(mIgnoredFrequencies);
        mExecutor.execute(this::refreshExclusions);
        notifyHitsChanged();
    }

    public void unignore(DiscoveryHit hit)
    {
        mIgnoredFrequencies.remove(hit.getBinFrequency());
        mPreference.setIgnoredFrequencies(mIgnoredFrequencies);
        hit.setStatus(hit.hasClip() ? DiscoveryHit.Status.CAPTURED : DiscoveryHit.Status.NEW);
        mExecutor.execute(this::refreshExclusions);
        notifyHitsChanged();
    }

    public void delete(DiscoveryHit hit)
    {
        mHits.remove(hit.getKey());
        deleteClip(hit.getClipPath());
        notifyHitsChanged();
    }

    public void clearAll()
    {
        for(DiscoveryHit hit : mHits.values())
        {
            deleteClip(hit.getClipPath());
        }

        mHits.clear();
        notifyHitsChanged();
    }

    private void deleteClip(String clipPath)
    {
        if(clipPath != null)
        {
            try
            {
                Files.deleteIfExists(Path.of(clipPath));
            }
            catch(IOException e)
            {
                mLog.warn("Couldn't delete clip [" + clipPath + "]");
            }
        }
    }

    /**
     * Queues offline analysis of the hit's clip.
     */
    public void analyze(DiscoveryHit hit)
    {
        if(hit == null || !hit.hasClip())
        {
            return;
        }

        hit.setStatus(DiscoveryHit.Status.ANALYZING);
        notifyHitsChanged();

        mAnalyzer.execute(() -> {
            try
            {
                ClipAnalyzer.Result result = new ClipAnalyzer(mPreference.isIncludeP25()).analyze(Path.of(hit.getClipPath()),
                    hit.getBinFrequency());
                hit.setProtocol(result.protocol());
                hit.setDetails(result.details());

                if(result.carrierFrequency() > 0)
                {
                    hit.setCarrierFrequency(result.carrierFrequency());
                }

                hit.setStatus(result.identified() ? DiscoveryHit.Status.IDENTIFIED : DiscoveryHit.Status.UNIDENTIFIED);
            }
            catch(Throwable t)
            {
                mLog.error("Error analyzing discovery clip [" + hit.getClipPath() + "]", t);
                hit.setProtocol("");
                hit.setDetails("Analysis error: " + t.getMessage());
                hit.setStatus(DiscoveryHit.Status.UNIDENTIFIED);
            }

            notifyHitsChanged();
        });
    }

    //---------------------------------------------------------------------------------------------------------------
    // DiscoveryMonitor.Listener - invoked on the channelizer thread; hand off quickly.
    //---------------------------------------------------------------------------------------------------------------

    @Override
    public void hitStarted(String tunerId, long binFrequency, double levelDb, double snrDb, long timestamp)
    {
        mExecutor.execute(() -> {
            String key = DiscoveryHit.key(tunerId, binFrequency);
            DiscoveryHit hit = mHits.computeIfAbsent(key, k -> new DiscoveryHit(tunerId, binFrequency, timestamp));
            hit.setHitCount(hit.getHitCount() + 1);
            hit.setLastHeard(timestamp);
            hit.setLastLevelDb(levelDb);
            hit.setPeakLevelDb(Math.max(hit.getPeakLevelDb(), levelDb));
            hit.setPeakSnrDb(Math.max(hit.getPeakSnrDb(), snrDb));
            hit.setActive(true);
            notifyHitsChanged();
        });
    }

    @Override
    public void hitUpdated(String tunerId, long binFrequency, double levelDb, double snrDb, long timestamp)
    {
        DiscoveryHit hit = mHits.get(DiscoveryHit.key(tunerId, binFrequency));

        if(hit != null)
        {
            hit.setLastHeard(timestamp);
            hit.setLastLevelDb(levelDb);

            boolean changed = false;

            if(levelDb > hit.getPeakLevelDb())
            {
                hit.setPeakLevelDb(levelDb);
                changed = true;
            }

            if(snrDb > hit.getPeakSnrDb())
            {
                hit.setPeakSnrDb(snrDb);
                changed = true;
            }

            if(changed)
            {
                mExecutor.execute(this::notifyHitsChanged);
            }
        }
    }

    @Override
    public void hitEnded(String tunerId, long binFrequency, long timestamp, long activeMs)
    {
        mExecutor.execute(() -> {
            DiscoveryHit hit = mHits.get(DiscoveryHit.key(tunerId, binFrequency));

            if(hit != null)
            {
                hit.setActive(false);
                hit.setLastHeard(timestamp);
                hit.setTotalActiveMs(hit.getTotalActiveMs() + Math.max(0, activeMs));
                notifyHitsChanged();
            }
        });
    }

    @Override
    public void clipCompleted(String tunerId, long binFrequency, Path clip, double channelSampleRate)
    {
        mExecutor.schedule(() -> {
            DiscoveryHit hit = mHits.get(DiscoveryHit.key(tunerId, binFrequency));

            if(hit == null || clip == null)
            {
                return;
            }

            //Keep only the most recent clip per hit
            if(hit.hasClip() && !clip.toString().equals(hit.getClipPath()))
            {
                deleteClip(hit.getClipPath());
            }

            hit.setClipPath(clip.toString());

            if(hit.getStatus() == DiscoveryHit.Status.NEW)
            {
                hit.setStatus(DiscoveryHit.Status.CAPTURED);
            }

            notifyHitsChanged();

            if(mPreference.isAutoAnalyze() && hit.getStatus() != DiscoveryHit.Status.IGNORED)
            {
                analyze(hit);
            }
        }, ANALYSIS_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    public void floorUpdated(String tunerId, double floorDb, int binCount)
    {
        //Rate limit isn't needed: the panel polls monitor.getFloorDb() on its own timer.
    }

    //---------------------------------------------------------------------------------------------------------------
    // Persistence, retention, export
    //---------------------------------------------------------------------------------------------------------------

    private void housekeeping()
    {
        try
        {
            if(mDirty)
            {
                mDirty = false;
                save();
            }

            int retentionDays = mPreference.getRetentionDays();

            if(retentionDays > 0)
            {
                long cutoff = System.currentTimeMillis() - retentionDays * 24L * 3600L * 1000L;

                for(DiscoveryHit hit : mHits.values())
                {
                    if(hit.hasClip())
                    {
                        Path clip = Path.of(hit.getClipPath());

                        try
                        {
                            if(!Files.exists(clip))
                            {
                                hit.setClipPath(null);
                            }
                            else if(Files.getLastModifiedTime(clip).toMillis() < cutoff)
                            {
                                Files.deleteIfExists(clip);
                                hit.setClipPath(null);
                            }
                        }
                        catch(IOException e)
                        {
                            //ignore
                        }
                    }
                }

                //Remove orphan clips not referenced by any hit
                Set<String> referenced = new HashSet<>();

                for(DiscoveryHit hit : mHits.values())
                {
                    if(hit.hasClip())
                    {
                        referenced.add(hit.getClipPath());
                    }
                }

                try(Stream<Path> files = Files.list(mClipDirectory))
                {
                    files.filter(p -> p.toString().endsWith(".wav") && !referenced.contains(p.toString()))
                        .forEach(p -> {
                            try
                            {
                                if(Files.getLastModifiedTime(p).toMillis() < cutoff)
                                {
                                    Files.deleteIfExists(p);
                                }
                            }
                            catch(IOException e)
                            {
                                //ignore
                            }
                        });
                }
            }
        }
        catch(Throwable t)
        {
            mLog.error("Discovery housekeeping error", t);
        }
    }

    public synchronized void save()
    {
        try
        {
            Files.createDirectories(mDirectory);
            mMapper.writeValue(mHitsFile.toFile(), new ArrayList<>(mHits.values()));
        }
        catch(IOException e)
        {
            mLog.error("Couldn't save discovery hits to [" + mHitsFile + "]", e);
        }
    }

    private void load()
    {
        if(Files.exists(mHitsFile))
        {
            try
            {
                List<DiscoveryHit> hits = mMapper.readValue(mHitsFile.toFile(), new TypeReference<List<DiscoveryHit>>() {});

                for(DiscoveryHit hit : hits)
                {
                    hit.setActive(false);

                    if(hit.getStatus() == DiscoveryHit.Status.ANALYZING)
                    {
                        hit.setStatus(hit.hasClip() ? DiscoveryHit.Status.CAPTURED : DiscoveryHit.Status.NEW);
                    }

                    mHits.put(hit.getKey(), hit);
                }
            }
            catch(IOException e)
            {
                mLog.error("Couldn't load discovery hits from [" + mHitsFile + "]", e);
            }
        }
    }

    /**
     * Exports the hit list as CSV.
     */
    public void exportCsv(Path file) throws IOException
    {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        StringBuilder sb = new StringBuilder();
        sb.append("Tuner,Bin Frequency (Hz),Carrier Frequency (Hz),First Heard,Last Heard,Hits,Active Seconds,Peak Level (dB),Peak SNR (dB),Protocol,Details,Status,Clip\n");

        List<DiscoveryHit> hits = getHits();
        hits.sort((a, b) -> Long.compare(a.getBinFrequency(), b.getBinFrequency()));

        for(DiscoveryHit hit : hits)
        {
            sb.append(csv(hit.getTunerId())).append(',');
            sb.append(hit.getBinFrequency()).append(',');
            sb.append(hit.getCarrierFrequency()).append(',');
            sb.append(sdf.format(new Date(hit.getFirstHeard()))).append(',');
            sb.append(sdf.format(new Date(hit.getLastHeard()))).append(',');
            sb.append(hit.getHitCount()).append(',');
            sb.append(hit.getTotalActiveMs() / 1000).append(',');
            sb.append(String.format("%.1f", hit.getPeakLevelDb())).append(',');
            sb.append(String.format("%.1f", hit.getPeakSnrDb())).append(',');
            sb.append(csv(hit.getProtocol())).append(',');
            sb.append(csv(hit.getDetails())).append(',');
            sb.append(hit.getStatus()).append(',');
            sb.append(csv(hit.getClipPath() == null ? "" : hit.getClipPath())).append('\n');
        }

        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    private static String csv(String value)
    {
        if(value == null)
        {
            return "";
        }

        if(value.contains(",") || value.contains("\"") || value.contains("\n"))
        {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }

        return value;
    }

    public void shutdown()
    {
        for(DiscoveryMonitor monitor : mMonitors.values())
        {
            monitor.stop();
        }

        save();
        mExecutor.shutdownNow();
        mAnalyzer.shutdownNow();
    }
}
