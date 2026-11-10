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

package io.github.dsheirer.audio.codec.mbe;

import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.audio.AbstractAudioModule;
import io.github.dsheirer.audio.squelch.ISquelchStateListener;
import io.github.dsheirer.dsp.gain.NonClippingGain;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.message.IMessageListener;
import io.github.dsheirer.preference.PreferenceType;
import io.github.dsheirer.audio.broadcast.PatchGroupStreamingOption;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.sample.Listener;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import jmbe.iface.IAudioCodec;
import jmbe.iface.IAudioCodecLibrary;
import jmbe.iface.IAudioCodecLibraryV2;
import jmbe.iface.IAudioCodecV2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base audio module for protocols that use the JMBE audio codec.
 */
public abstract class JmbeAudioModule extends AbstractAudioModule implements Listener<IMessage>, IMessageListener,
    ISquelchStateListener
{
    private static final Logger LOG = LoggerFactory.getLogger(JmbeAudioModule.class);
    private static final String JMBE_AUDIO_LIBRARY = "JMBE";
    private static final List<String> mLibraryLoadStatusLogged = new ArrayList<>();
    private IAudioCodec mAudioCodec;
    private final UserPreferences mUserPreferences;
    //Loaded JMBE library classes, keyed by library path, so that IMBE and AMBE audio can use different libraries.
    private static final Map<Path,Class> sLoadedJmbeAudioConverterClasses = new ConcurrentHashMap<>();
    //Optional audio boost for JMBE 2.x decoded audio.  Null when no boost is applied.
    private NonClippingGain mAudioBoostGain;

    public JmbeAudioModule(UserPreferences userPreferences, AliasList aliasList, int timeslot)
    {
        super(aliasList, timeslot, DEFAULT_MAX_SEGMENT_LENGTH_MILLIS);
        mUserPreferences = userPreferences;
        MyEventBus.getGlobalEventBus().register(this);
        loadConverter();
    }

    /**
     * Honors the user's patch group streaming preference (Individual Talkgroups vs Patch Group) for the PCM stream.
     */
    @Override
    protected PatchGroupStreamingOption getPatchGroupStreamingOption()
    {
        return mUserPreferences.getCallManagementPreference().getPatchGroupStreamingOption();
    }

    /**
     * Indicates if the loaded JMBE audio library is version 2.0 or later.  Version 1.x libraries keep the original
     * fixed audio gain stages in the protocol audio modules, so switching libraries restores the prior audio levels.
     * @return true if a version 2.0+ audio codec is loaded.
     */
    protected boolean isJmbeVersion2()
    {
        return getAudioCodec() instanceof IAudioCodecV2;
    }

    /**
     * Sets the audio boost applied to decoded voice audio.  The boost only takes effect when a JMBE 2.x library is
     * loaded.  Version 1.x libraries keep their original fixed gain stages unchanged.
     * @param boost multiplier, 1.0 (no change) to 2.0 (double the signal level, +6 dB)
     */
    public void setAudioBoost(float boost)
    {
        if(boost > 1.0f)
        {
            mAudioBoostGain = new NonClippingGain(Math.min(boost, 2.0f), 0.95f);
        }
        else
        {
            mAudioBoostGain = null;
        }
    }

    /**
     * Applies the optional audio boost to JMBE 2.x decoded audio before adding it to the current audio segment.
     */
    @Override
    public void addAudio(float[] audioBuffer)
    {
        NonClippingGain boost = mAudioBoostGain;

        if(boost != null && audioBuffer != null && isJmbeVersion2())
        {
            boost.apply(audioBuffer);
        }

        super.addAudio(audioBuffer);
    }

    /**
     * List of features supported by the JMBE audio library.  Note: features are only supported in Version 2.0+
     * @return list of features or an empty list if the current library version doesn't support features.
     */
    public List<String> getFeatures()
    {
        if(mAudioCodec instanceof IAudioCodecV2 v2)
        {
            return v2.getFeatures();
        }

        return Collections.emptyList();
    }

    /**
     * Sets the JMBE feature
     * @param name of the feature
     * @param value for the feature
     */
    public void setFeature(String name, Object value)
    {
        if(mAudioCodec instanceof IAudioCodecV2 v2)
        {
            v2.setFeature(name, value);
        }
        else
        {
            LOG.info("Feature [" + name + "] is not supported by the current JMBE audio codec library version");
        }
    }

    @Override
    protected void closeAudioSegment()
    {
        super.closeAudioSegment();

        //Reset the audio codec to clear any leftover frame data from the previous call.
        if(mAudioCodec != null)
        {
            mAudioCodec.reset();
        }
    }

    @Override
    public void dispose()
    {
        super.dispose();
        MyEventBus.getGlobalEventBus().unregister(this);
    }

    public IAudioCodec getAudioCodec()
    {
        return mAudioCodec;
    }

    /**
     * Indicates that the JMBE audio library has been loaded and a suitable audio codec is usable (ie non-null)
     */
    protected boolean hasAudioCodec()
    {
        return getAudioCodec() != null;
    }

    @Override
    public Listener<IMessage> getMessageListener()
    {
        return this;
    }

    /**
     * Receives notifications that the JMBE library preference has been updated via the Guava event bus
     *
     * @param preferenceType that was updated
     */
    @Subscribe
    public void preferenceUpdated(PreferenceType preferenceType)
    {
        if(preferenceType == PreferenceType.JMBE_LIBRARY)
        {
            mLibraryLoadStatusLogged.clear();
            loadConverter();
        }
    }

    /**
     * Path to the JMBE library used by this audio module.  AMBE audio modules override this to use the optional AMBE
     * library.
     * @return library path or null if not set.
     */
    protected Path getJmbeLibraryPath()
    {
        return mUserPreferences.getJmbeLibraryPreference().getPathJmbeLibrary();
    }

    /**
     * User preferences
     */
    protected UserPreferences getUserPreferences()
    {
        return mUserPreferences;
    }

    /**
     * Name of the CODEC to use from the JMBE library
     */
    protected abstract String getCodecName();

    /**
     * Loads JMBE audio converter library class and then instantiates new converter instances from the loaded class.
     */
    protected void loadConverter()
    {
        IAudioCodec audioConverter = null;

        Path path = getJmbeLibraryPath();
        String logKey = JMBE_AUDIO_LIBRARY + ":" + getCodecName();
        Class loadedClass = path != null ? sLoadedJmbeAudioConverterClasses.get(path) : null;

        if(loadedClass == null)
        {
            if(path != null)
            {
                if(!mLibraryLoadStatusLogged.contains(logKey))
                {
                    LOG.info("Loading JMBE library for [" + getCodecName() + "] from [" + path + "]");
                }

                try
                {
                    URLClassLoader childClassLoader = new URLClassLoader(new URL[]{path.toUri().toURL()},
                            this.getClass().getClassLoader());

                    try
                    {
                        loadedClass = Class.forName("jmbe.JMBEAudioLibraryV2", true, childClassLoader);
                    }
                    catch(ClassNotFoundException legacyLibrary)
                    {
                        try
                        {
                            loadedClass = Class.forName("jmbe.JMBEAudioLibrary", true, childClassLoader);
                        }
                        catch(ClassNotFoundException missingLibrary)
                        {
                            if(!mLibraryLoadStatusLogged.contains(logKey))
                            {
                                LOG.error("Couldn't load JMBE audio conversion library from [" + path +
                                    "] - neither V2 nor V1 library class was found");
                                mLibraryLoadStatusLogged.add(logKey);
                            }
                        }
                    }
                }
                catch(IllegalArgumentException iae)
                {
                    if(!mLibraryLoadStatusLogged.contains(logKey))
                    {
                        LOG.error("Couldn't load JMBE audio conversion library - " + iae.getMessage());
                        mLibraryLoadStatusLogged.add(logKey);
                    }
                }
                catch(MalformedURLException mue)
                {
                    if(!mLibraryLoadStatusLogged.contains(logKey))
                    {
                        LOG.error("Couldn't load JMBE audio conversion library from path [" + path + "]");
                        mLibraryLoadStatusLogged.add(logKey);
                    }
                }

            }
        }

        if(loadedClass != null && path != null)
        {
            sLoadedJmbeAudioConverterClasses.putIfAbsent(path, loadedClass);
        }

        if(loadedClass != null)
        {
            try
            {
                Object instance = loadedClass.getDeclaredConstructor().newInstance();

                if(instance instanceof IAudioCodecLibrary library)
                {
                    if((library.getMajorVersion() == 1 && library.getMinorVersion() >= 0 &&
                            library.getBuildVersion() >= 0) || library.getMajorVersion() >= 1)
                    {
                        audioConverter = library instanceof IAudioCodecLibraryV2 libraryV2 ?
                            libraryV2.getAudioConverterV2(getCodecName()) :
                            library.getAudioConverter(getCodecName());

                        String codecStatusKey = JMBE_AUDIO_LIBRARY + ":codec:" + getCodecName();
                        if(!mLibraryLoadStatusLogged.contains(codecStatusKey))
                        {
                            LOG.info("JMBE codec [" + getCodecName() + "] class [" +
                                audioConverter.getClass().getName() + "] features [" +
                                (audioConverter instanceof IAudioCodecV2 v2 ?
                                    v2.getFeatures() : Collections.emptyList()) + "]");
                            mLibraryLoadStatusLogged.add(codecStatusKey);
                        }

                        if(!mLibraryLoadStatusLogged.contains(logKey))
                        {
                            LOG.info("JMBE audio conversion library loaded for [" + getCodecName() + "]: " + library.getVersion());
                            mLibraryLoadStatusLogged.add(logKey);
                        }
                    }
                    else
                    {
                        if(!mLibraryLoadStatusLogged.contains(logKey))
                        {
                            LOG.warn("JMBE library version 1.0.0 or higher is required - found: " + library.getVersion());
                            mLibraryLoadStatusLogged.add(logKey);
                        }
                    }
                }
                else
                {
                    if(!mLibraryLoadStatusLogged.contains(logKey))
                    {
                        LOG.info("JMBE audio conversion library NOT FOUND");
                        mLibraryLoadStatusLogged.add(logKey);
                    }
                }
            }
            catch(InvocationTargetException ite)
            {
                if(!mLibraryLoadStatusLogged.contains(logKey))
                {
                    LOG.error("Couldn't load JMBE audio conversion library - invocation target exception", ite);
                    mLibraryLoadStatusLogged.add(logKey);
                }
            }
            catch(InstantiationException e1)
            {
                if(!mLibraryLoadStatusLogged.contains(logKey))
                {
                    LOG.error("Couldn't load JMBE audio conversion library - instantiation exception", e1);
                    mLibraryLoadStatusLogged.add(logKey);
                }
            }
            catch(IllegalAccessException e1)
            {
                if(!mLibraryLoadStatusLogged.contains(logKey))
                {
                    LOG.error("Couldn't load JMBE audio conversion library - security restrictions");
                    mLibraryLoadStatusLogged.add(logKey);
                }
            }
            catch(NoSuchMethodException nsme)
            {
                if(!mLibraryLoadStatusLogged.contains(logKey))
                {
                    LOG.error("Couldn't load JMBE audio conversion library - no such method exception");
                    mLibraryLoadStatusLogged.add(logKey);
                }
            }
        }
        else
        {
            if(!mLibraryLoadStatusLogged.contains(logKey))
            {
                LOG.warn("JMBE audio library path is NOT SET in your User Preferences.");
                mLibraryLoadStatusLogged.add(logKey);
            }
        }

        mAudioCodec = audioConverter;
    }
}
