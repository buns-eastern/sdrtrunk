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
package io.github.dsheirer.module.decode.p25.phase1;


import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import io.github.dsheirer.module.decode.config.DecodeConfiguration;
import io.github.dsheirer.module.decode.p25.phase2.DecodeConfigP25Phase2;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = DecodeConfigP25Phase1.class, name = "decodeConfigP25Phase1"),
        @JsonSubTypes.Type(value = DecodeConfigP25Phase2.class, name = "decodeConfigP25Phase2"),
})
public abstract class DecodeConfigP25 extends DecodeConfiguration
{
    private int mTrafficChannelPoolSize = TRAFFIC_CHANNEL_LIMIT_DEFAULT;
    private boolean mIgnoreDataCalls = false;

    //Optional control-channel identity filters.  Null = not set = no filtering (legacy behavior preserved).
    private Integer mNacFilter;
    private Integer mSystemFilter;
    private Integer mSiteFilter;

    public DecodeConfigP25()
    {
    }

    @JacksonXmlProperty(isAttribute = true, localName = "ignore_data_calls")
    public boolean getIgnoreDataCalls()
    {
        return mIgnoreDataCalls;
    }

    public void setIgnoreDataCalls(boolean ignore)
    {
        mIgnoreDataCalls = ignore;
    }


    @JacksonXmlProperty(isAttribute = true, localName = "traffic_channel_pool_size")
    public int getTrafficChannelPoolSize()
    {
        return mTrafficChannelPoolSize;
    }

    /**
     * Sets the traffic channel pool size which is the maximum number of
     * simultaneous traffic channels that can be allocated.
     *
     * This limits the maximum calls so that busy systems won't cause more
     * traffic channels to be allocated than the decoder/software/host computer
     * can support.
     */
    public void setTrafficChannelPoolSize(int size)
    {
        mTrafficChannelPoolSize = size;
    }

    /**
     * Optional NAC identity filter.  When set, the decoder ignores any P25 message whose NAC does not match this
     * value.  Null (empty) means no filtering.  Stored only when non-null so unconfigured channels are unchanged.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JacksonXmlProperty(isAttribute = true, localName = "nac_filter")
    public Integer getNacFilter()
    {
        return mNacFilter;
    }

    public void setNacFilter(Integer nac)
    {
        mNacFilter = nac;
    }

    /**
     * Optional System ID identity filter.  Null (empty) means no filtering.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JacksonXmlProperty(isAttribute = true, localName = "system_filter")
    public Integer getSystemFilter()
    {
        return mSystemFilter;
    }

    public void setSystemFilter(Integer system)
    {
        mSystemFilter = system;
    }

    /**
     * Optional Site ID identity filter.  Null (empty) means no filtering.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JacksonXmlProperty(isAttribute = true, localName = "site_filter")
    public Integer getSiteFilter()
    {
        return mSiteFilter;
    }

    public void setSiteFilter(Integer site)
    {
        mSiteFilter = site;
    }

    /**
     * Indicates if any identity filter (NAC, System, or Site) is configured for this channel.
     */
    @JsonIgnore
    public boolean hasIdentityFilter()
    {
        return mNacFilter != null || mSystemFilter != null || mSiteFilter != null;
    }

    /**
     * Indicates if the NAC value is allowed by the (optional) NAC filter.  An unset filter allows all values.
     */
    @JsonIgnore
    public boolean isNacAllowed(int nac)
    {
        return mNacFilter == null || mNacFilter == nac;
    }

    /**
     * Indicates if the System ID value is allowed by the (optional) System filter.  An unset filter allows all values.
     */
    @JsonIgnore
    public boolean isSystemAllowed(int system)
    {
        return mSystemFilter == null || mSystemFilter == system;
    }

    /**
     * Indicates if the Site ID value is allowed by the (optional) Site filter.  An unset filter allows all values.
     */
    @JsonIgnore
    public boolean isSiteAllowed(int site)
    {
        return mSiteFilter == null || mSiteFilter == site;
    }

    /**
     * Copies the identity filter values (NAC/System/Site) from this configuration into the target configuration so
     * that dynamically created traffic channels inherit the parent control channel's identity filters.
     */
    public void copyIdentityFiltersTo(DecodeConfigP25 other)
    {
        if(other != null)
        {
            other.setNacFilter(mNacFilter);
            other.setSystemFilter(mSystemFilter);
            other.setSiteFilter(mSiteFilter);
        }
    }
}
