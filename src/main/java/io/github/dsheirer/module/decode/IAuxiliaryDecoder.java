package io.github.dsheirer.module.decode;

/**
 * Marker interface for auxiliary decoders that decode data bursts from a channel's demodulated audio.  The processing
 * chain feeds these decoders from the primary decoder's unprocessed auxiliary audio feed when one is available.
 */
public interface IAuxiliaryDecoder
{
}
