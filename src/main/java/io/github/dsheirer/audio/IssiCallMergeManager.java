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
package io.github.dsheirer.audio;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.alias.id.broadcast.BroadcastChannel;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelModel;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.configuration.SystemConfigurationIdentifier;
import io.github.dsheirer.identifier.talkgroup.TalkgroupIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.duplicate.IssiCallMergeEntry;
import io.github.dsheirer.sample.Listener;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ISSI Call Merge runtime.  Registered as the first audio-segment listener, ahead of the duplicate-call
 * detector, playback, recording and streaming.  For a call whose decoded (system, talkgroup) matches a
 * configured redirect, it re-points the segment to the primary (system, talkgroup): it replaces the SYSTEM and
 * TO identifiers, points the segment at the primary system's alias list, and attaches the primary alias's
 * broadcast (stream) channels.  The result is that the existing per-system duplicate rules collapse it with the
 * primary's copy and it streams using the primary's stream configuration - exactly as two channels sharing one
 * system name already behave.
 *
 * The manager is completely inert unless the feature is enabled with at least one redirect configured, so with
 * nothing set up the audio, de-duplication and streaming path is unaffected.  All work is wrapped so a failure
 * can never affect audio.
 */
public class IssiCallMergeManager implements Listener<AudioSegment>
{
    private static final Logger mLog = LoggerFactory.getLogger(IssiCallMergeManager.class);

    private final UserPreferences mUserPreferences;
    private final AliasModel mAliasModel;
    private final ChannelModel mChannelModel;

    public IssiCallMergeManager(UserPreferences userPreferences, AliasModel aliasModel, ChannelModel channelModel)
    {
        mUserPreferences = userPreferences;
        mAliasModel = aliasModel;
        mChannelModel = channelModel;
    }

    @Override
    public void receive(AudioSegment audioSegment)
    {
        try
        {
            if(audioSegment == null || !mUserPreferences.getIssiCallMergePreference().isActive())
            {
                return;
            }

            String system = getSystem(audioSegment);
            int talkgroup = getToTalkgroup(audioSegment);

            if(system == null || talkgroup == 0)
            {
                return;
            }

            IssiCallMergeEntry redirect = mUserPreferences.getIssiCallMergePreference().findRedirect(system, talkgroup);

            if(redirect == null)
            {
                return;
            }

            apply(audioSegment, redirect);
        }
        catch(Throwable t)
        {
            mLog.warn("ISSI Call Merge redirect error (ignored): {}", t.getMessage());
        }
    }

    /**
     * Re-points the segment to the primary identity.
     */
    private void apply(AudioSegment audioSegment, IssiCallMergeEntry redirect)
    {
        //Replace SYSTEM and TO talkgroup with the primary values (same class/form/role, so they replace in place)
        audioSegment.silentUpdateIdentifier(SystemConfigurationIdentifier.create(redirect.getPrimarySystem()));
        audioSegment.silentUpdateIdentifier(new APCO25Talkgroup(redirect.getPrimaryTalkgroup(), Role.TO));

        //Point the segment at the primary system's alias list and attach its stream (broadcast) channels so the
        //call streams using the primary's configuration even though the source alias was not set to stream.
        AliasList primaryAliasList = resolvePrimaryAliasList(redirect.getPrimarySystem());

        if(primaryAliasList != null)
        {
            audioSegment.setAliasList(primaryAliasList);

            TalkgroupIdentifier primaryTo = new APCO25Talkgroup(redirect.getPrimaryTalkgroup(), Role.TO);
            List<Alias> aliases = primaryAliasList.getAliases(primaryTo);

            if(aliases != null)
            {
                for(Alias alias: aliases)
                {
                    for(BroadcastChannel channel: alias.getBroadcastChannels())
                    {
                        audioSegment.broadcastChannelsProperty().add(channel);
                    }
                }
            }
        }
    }

    /**
     * Resolves the alias list used by the primary system (via one of its channels).
     */
    private AliasList resolvePrimaryAliasList(String primarySystem)
    {
        if(mChannelModel == null || mAliasModel == null || primarySystem == null)
        {
            return null;
        }

        for(Channel channel: mChannelModel.getChannels())
        {
            if(primarySystem.equalsIgnoreCase(channel.getSystem()))
            {
                String aliasListName = channel.getAliasListName();

                if(aliasListName != null && !aliasListName.isBlank())
                {
                    return mAliasModel.getAliasList(aliasListName);
                }
            }
        }

        return null;
    }

    private static String getSystem(AudioSegment audioSegment)
    {
        Identifier identifier = audioSegment.getIdentifierCollection()
                .getIdentifier(IdentifierClass.CONFIGURATION, Form.SYSTEM, Role.ANY);

        if(identifier instanceof SystemConfigurationIdentifier system)
        {
            return system.getValue();
        }

        return null;
    }

    private static int getToTalkgroup(AudioSegment audioSegment)
    {
        List<Identifier> toIdentifiers = audioSegment.getIdentifierCollection().getIdentifiers(Role.TO);

        if(toIdentifiers != null)
        {
            for(Identifier identifier: toIdentifiers)
            {
                if(identifier instanceof TalkgroupIdentifier talkgroup)
                {
                    return talkgroup.getValue();
                }
            }
        }

        return 0;
    }
}
