package io.github.dsheirer.sample.real;

import io.github.dsheirer.sample.Listener;

/**
 * Provides demodulated audio for auxiliary decoders (MDC-1200, Fleetsync, DCS, etc.) as a separate feed from the
 * audio sent to the audio module.  This allows a primary decoder to apply tone gating, audio filters or head and tail
 * trimming to the call audio, while the auxiliary decoders still receive the unprocessed demodulated audio that
 * contains the data bursts they decode.
 */
public interface IAuxiliaryAudioProvider
{
    /**
     * Sets the listener to receive unprocessed demodulated audio for auxiliary decoders.
     * @param listener to receive audio, or null to stop the feed.
     */
    void setAuxiliaryAudioListener(Listener<float[]> listener);
}
