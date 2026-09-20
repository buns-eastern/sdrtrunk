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

import io.github.dsheirer.buffer.INativeBuffer;
import io.github.dsheirer.dsp.fm.FmDemodulatorFactory;
import io.github.dsheirer.dsp.fm.IDemodulator;
import io.github.dsheirer.dsp.window.WindowFactory;
import io.github.dsheirer.dsp.window.WindowType;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.message.EmptyTimeslotPlaceholderMessage;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.message.SyncLossMessage;
import io.github.dsheirer.module.decode.Decoder;
import io.github.dsheirer.module.decode.ctcss.CTCSSCode;
import io.github.dsheirer.module.decode.dmr.DMRDecoder;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.dmr.message.DMRBurst;
import io.github.dsheirer.module.decode.dmr.message.data.DataMessage;
import io.github.dsheirer.module.decode.dmr.message.voice.VoiceEMBMessage;
import io.github.dsheirer.module.decode.dcs.DCSCode;
import io.github.dsheirer.module.decode.nbfm.CTCSSDetector;
import io.github.dsheirer.module.decode.nbfm.DCSDetector;
import io.github.dsheirer.module.decode.nxdn.DecodeConfigNXDN;
import io.github.dsheirer.module.decode.nxdn.NXDNDecoder;
import io.github.dsheirer.module.decode.nxdn.NXDNMessage;
import io.github.dsheirer.module.decode.nxdn.layer3.type.TransmissionMode;
import io.github.dsheirer.dsp.symbol.ISyncDetectListener;
import io.github.dsheirer.module.decode.p25.phase1.P25P1DecoderC4FM;
import io.github.dsheirer.module.decode.p25.phase1.P25P1DecoderLSM;
import io.github.dsheirer.module.decode.p25.phase2.DecodeConfigP25Phase2;
import io.github.dsheirer.module.decode.p25.phase2.P25P2DecoderHDQPSK;
import io.github.dsheirer.module.decode.p25.phase1.message.P25P1Message;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.source.wave.ComplexWaveSource;
import org.jtransforms.fft.FloatFFT_1D;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * Offline identification of a captured discovery clip.  Locates the carrier within the clip, mixes it to baseband,
 * runs each supported digital decoder against it and picks the one that produces valid frames.  Falls back to an
 * analog (FM) classification with CTCSS detection and occupied bandwidth when nothing syncs.
 */
public class ClipAnalyzer
{
    private static final Logger mLog = LoggerFactory.getLogger(ClipAnalyzer.class);
    private static final int FFT_SIZE = 1024;
    private static final int CHUNK = 2048;
    /**
     * Valid messages required before a decoder is allowed to claim the clip.  Front-end overload and strong adjacent
     * signals can produce a handful of accidentally-valid frames, so this bar is deliberately well above the noise.
     */
    private static final int MINIMUM_VALID_MESSAGES = 8;

    /**
     * A real signal repeats the same color code / RAN / NAC on every frame.  Decoded noise scatters them.  An
     * identification must observe at least this many keyed values, and the most common one must hold the share below.
     */
    private static final int MINIMUM_KEYED_OBSERVATIONS = 4;
    private static final double MINIMUM_KEY_DOMINANCE = 0.6;

    /**
     * P25 Phase 2 traffic cannot be message-decoded without the system's scramble parameters, which a standalone clip
     * does not carry.  Its sync pattern is not scrambled though, so sync detections identify the waveform.  A Phase 2
     * channel transmits a sync roughly every 180ms per timeslot, so a few seconds of signal yields dozens.
     */
    private static final int MINIMUM_P25P2_SYNC_DETECTS = 10;

    /**
     * Evidence count beyond which a candidate is treated as certain and the remaining decoders are skipped.  A real
     * control channel produces hundreds of frames in a few seconds, so this is only reached on unambiguous signals.
     */
    private static final int DECISIVE_SCORE = 60;

    /**
     * Channel raster used to clean up the measured carrier.  1.25 kHz is a common divisor of the 2.5/6.25/7.5/12.5/25
     * kHz channel spacings in land mobile use, so narrowband VHF channels such as 154.9725 land exactly.  The carrier
     * measurement itself is good to well under 100 Hz, so this only removes measurement jitter.
     */
    private static final long RASTER_HZ = 1250;

    public record Result(boolean identified, String protocol, String details, long carrierFrequency, double bandwidthHz,
                         int messageCount) {}

    private final boolean mIncludeP25;

    public ClipAnalyzer(boolean includeP25)
    {
        mIncludeP25 = includeP25;
    }

    public Result analyze(Path clip, long binFrequency) throws IOException
    {
        Loaded loaded = load(clip);

        if(loaded.count < FFT_SIZE * 4)
        {
            return new Result(false, "", "Clip too short", 0, 0, 0);
        }

        Spectrum spectrum = measure(loaded);
        long carrier = snap(binFrequency + Math.round(spectrum.offsetHz));

        //Mix the carrier to baseband using the exact measured offset
        mix(loaded, -spectrum.offsetHz);

        List<Candidate> candidates = new ArrayList<>();
        candidates.add(new Candidate("DMR", new DMRDecoder(new DecodeConfigDMR(), false)));
        candidates.add(new Candidate("NXDN 4800", new NXDNDecoder(new DecodeConfigNXDN(TransmissionMode.M4800))));
        candidates.add(new Candidate("NXDN 9600", new NXDNDecoder(new DecodeConfigNXDN(TransmissionMode.M9600))));

        if(mIncludeP25)
        {
            //C4FM and LSM are different waveforms for the same Phase 1 protocol - a simulcast system will not decode
            //with the C4FM demodulator and vice versa, so both are offered and the better score wins.
            candidates.add(new Candidate("P25 Phase 1 C4FM", new P25P1DecoderC4FM()));
            candidates.add(new Candidate("P25 Phase 1 LSM", new P25P1DecoderLSM()));
            candidates.add(new Candidate("P25 Phase 2", new P25P2Probe(phase2Config())));
        }

        Candidate best = null;

        for(Candidate candidate : candidates)
        {
            try
            {
                candidate.run(loaded);
            }
            catch(Throwable t)
            {
                mLog.debug("Decoder [" + candidate.mName + "] failed on clip", t);
            }

            if(candidate.isIdentified() && (best == null || candidate.score() > best.score()))
            {
                best = candidate;
            }

            //A decisive result means no other waveform is going to beat it.  Skipping the remaining decoders saves
            //most of the analysis cost on the clips that matter, which keeps low-powered machines usable.
            if(best != null && best.score() >= DECISIVE_SCORE)
            {
                break;
            }
        }

        String bandwidth = String.format("BW %.1f kHz", spectrum.bandwidthHz / 1000.0);

        if(best != null)
        {
            String details = best.describe();
            return new Result(true, best.mName, (details.isEmpty() ? "" : details + " ") + bandwidth, carrier,
                spectrum.bandwidthHz, best.score());
        }

        //Analog fallback.  Report the best digital near-miss so a decode that fell short of the confidence bar is
        //visible rather than silently becoming "analog".
        String analog = analyzeAnalog(loaded);
        String protocol = spectrum.bandwidthHz > 0 ? "Analog / Unknown" : "Unknown";
        Candidate nearest = null;

        for(Candidate candidate : candidates)
        {
            if(candidate.score() > 0 && (nearest == null || candidate.score() > nearest.score()))
            {
                nearest = candidate;
            }
        }

        String nearMiss = nearest != null ? " (" + nearest.mName + " " + nearest.score() + " partial)" : "";
        return new Result(false, protocol, (analog.isEmpty() ? "" : analog + " ") + bandwidth + nearMiss, carrier,
            spectrum.bandwidthHz, nearest != null ? nearest.score() : 0);
    }

    //---------------------------------------------------------------------------------------------------------------

    private static class Loaded
    {
        float[] i;
        float[] q;
        int count;
        double sampleRate;
    }

    private Loaded load(Path clip) throws IOException
    {
        Loaded loaded = new Loaded();
        List<float[]> iChunks = new ArrayList<>();
        List<float[]> qChunks = new ArrayList<>();
        int[] total = {0};

        try(ComplexWaveSource source = new ComplexWaveSource(clip.toFile(), false))
        {
            source.setListener(nativeBuffer -> {
                Iterator<ComplexSamples> it = nativeBuffer.iterator();

                while(it.hasNext())
                {
                    ComplexSamples samples = it.next();
                    iChunks.add(samples.i());
                    qChunks.add(samples.q());
                    total[0] += samples.i().length;
                }
            });
            source.start();
            loaded.sampleRate = source.getSampleRate();

            try
            {
                while(true)
                {
                    source.next(CHUNK, true);
                }
            }
            catch(IOException eof)
            {
                //End of file
            }
        }
        catch(Exception e)
        {
            throw new IOException("Couldn't read clip: " + e.getMessage(), e);
        }

        loaded.count = total[0];
        loaded.i = new float[loaded.count];
        loaded.q = new float[loaded.count];
        int pointer = 0;

        for(int x = 0; x < iChunks.size(); x++)
        {
            float[] ic = iChunks.get(x);
            float[] qc = qChunks.get(x);
            System.arraycopy(ic, 0, loaded.i, pointer, ic.length);
            System.arraycopy(qc, 0, loaded.q, pointer, qc.length);
            pointer += ic.length;
        }

        return loaded;
    }

    private record Spectrum(double offsetHz, double bandwidthHz, double peakDb, double floorDb) {}

    /**
     * Averaged power spectrum of the clip: locates the carrier offset from the clip center and its occupied bandwidth.
     */
    private Spectrum measure(Loaded loaded)
    {
        FloatFFT_1D fft = new FloatFFT_1D(FFT_SIZE);
        float[] window = WindowFactory.getWindow(WindowType.BLACKMAN_HARRIS_7, FFT_SIZE);
        double[] accumulator = new double[FFT_SIZE];
        float[] buffer = new float[FFT_SIZE * 2];
        int frames = 0;

        for(int start = 0; start + FFT_SIZE <= loaded.count; start += FFT_SIZE)
        {
            for(int x = 0; x < FFT_SIZE; x++)
            {
                buffer[2 * x] = loaded.i[start + x] * window[x];
                buffer[2 * x + 1] = loaded.q[start + x] * window[x];
            }

            fft.complexForward(buffer);

            for(int k = 0; k < FFT_SIZE; k++)
            {
                double re = buffer[2 * k];
                double im = buffer[2 * k + 1];
                //Shift so index 0 is the most negative frequency
                int shifted = (k + FFT_SIZE / 2) % FFT_SIZE;
                accumulator[shifted] += re * re + im * im;
            }

            frames++;
        }

        double[] db = new double[FFT_SIZE];
        double[] sorted = new double[FFT_SIZE];

        for(int k = 0; k < FFT_SIZE; k++)
        {
            double p = accumulator[k] / Math.max(1, frames);
            db[k] = p > 0 ? 10.0 * Math.log10(p) : -200;
            sorted[k] = db[k];
        }

        java.util.Arrays.sort(sorted);
        double floor = sorted[FFT_SIZE / 4]; //lower quartile as floor estimate

        //The channelizer bin is 2x oversampled: only the center half of the clip spectrum is inside the bin's passband
        int lo = FFT_SIZE / 4;
        int hi = FFT_SIZE * 3 / 4;
        int peak = lo;

        for(int k = lo; k < hi; k++)
        {
            if(db[k] > db[peak])
            {
                peak = k;
            }
        }

        double peakDb = db[peak];
        double threshold = Math.max(floor + 6.0, peakDb - 20.0);

        //Contiguous region around the peak above threshold defines the occupied bandwidth and power centroid
        int left = peak;
        int right = peak;

        while(left > 0 && db[left - 1] > threshold)
        {
            left--;
        }

        while(right < FFT_SIZE - 1 && db[right + 1] > threshold)
        {
            right++;
        }

        double weight = 0;
        double centroid = 0;

        for(int k = left; k <= right; k++)
        {
            double linear = Math.pow(10.0, db[k] / 10.0);
            weight += linear;
            centroid += linear * k;
        }

        double centerBin = weight > 0 ? centroid / weight : peak;
        double binHz = loaded.sampleRate / FFT_SIZE;
        double offsetHz = (centerBin - FFT_SIZE / 2.0) * binHz;
        double bandwidthHz = (right - left + 1) * binHz;

        return new Spectrum(offsetHz, bandwidthHz, peakDb, floor);
    }

    private static long snap(long frequency)
    {
        return Math.round((double)frequency / RASTER_HZ) * RASTER_HZ;
    }

    private void mix(Loaded loaded, double shiftHz)
    {
        if(Math.abs(shiftHz) < 1.0)
        {
            return;
        }

        double phaseIncrement = 2.0 * Math.PI * shiftHz / loaded.sampleRate;
        double phase = 0;

        for(int x = 0; x < loaded.count; x++)
        {
            float cos = (float)Math.cos(phase);
            float sin = (float)Math.sin(phase);
            float i = loaded.i[x];
            float q = loaded.q[x];
            loaded.i[x] = i * cos - q * sin;
            loaded.q[x] = i * sin + q * cos;
            phase += phaseIncrement;

            if(phase > Math.PI)
            {
                phase -= 2.0 * Math.PI;
            }
            else if(phase < -Math.PI)
            {
                phase += 2.0 * Math.PI;
            }
        }
    }

    /**
     * FM demodulates the clip, resamples to 8 kHz and looks for CTCSS and DCS squelch codes.
     */
    private String analyzeAnalog(Loaded loaded)
    {
        try
        {
            IDemodulator demodulator = FmDemodulatorFactory.getFmDemodulator();
            float[] audio = demodulator.demodulate(loaded.i, loaded.q);
            float[] audio8k = resampleTo8k(audio, loaded.sampleRate);

            List<String> found = new ArrayList<>();

            CTCSSDetector ctcss = new CTCSSDetector(null, 8000.0f);
            ctcss.process(audio8k);
            CTCSSCode tone = ctcss.getDetectedCode();

            if(tone == null)
            {
                tone = ctcss.getRawDetectedCode();
            }

            if(tone != null)
            {
                found.add("CTCSS " + tone);
            }

            try
            {
                DCSDetector dcs = new DCSDetector(DCSCode.STANDARD_CODES);

                //Feed in blocks so the detector's periodic checks run as they would on a live channel
                for(int start = 0; start < audio8k.length; start += 1024)
                {
                    int length = Math.min(1024, audio8k.length - start);
                    float[] block = new float[length];
                    System.arraycopy(audio8k, start, block, 0, length);
                    dcs.process(block);
                }

                DCSCode code = dcs.getDetectedCode();

                if(code != null)
                {
                    found.add("DCS " + code);
                }
            }
            catch(Throwable t)
            {
                mLog.debug("DCS analysis failed", t);
            }

            return String.join(" ", found);
        }
        catch(Throwable t)
        {
            mLog.debug("Analog analysis failed", t);
        }

        return "";
    }

    /**
     * Resamples demodulated audio to 8 kHz: boxcar low-pass over the decimation ratio followed by linear
     * interpolation.  Sub-audible squelch codes are all below 300 Hz so this is plenty.
     */
    private static float[] resampleTo8k(float[] audio, double inputRate)
    {
        double ratio = inputRate / 8000.0;

        if(Math.abs(ratio - 1.0) < 1e-6)
        {
            return audio;
        }

        //Boxcar pre-filter to knock down content above 4 kHz before interpolating
        int box = Math.max(1, (int)Math.floor(ratio));
        float[] filtered = new float[audio.length];
        double sum = 0;

        for(int x = 0; x < audio.length; x++)
        {
            sum += audio[x];

            if(x >= box)
            {
                sum -= audio[x - box];
            }

            filtered[x] = (float)(sum / Math.min(box, x + 1));
        }

        int outputLength = (int)(audio.length / ratio);
        float[] output = new float[outputLength];

        for(int x = 0; x < outputLength; x++)
        {
            double position = x * ratio;
            int index = (int)position;
            double fraction = position - index;

            if(index + 1 < filtered.length)
            {
                output[x] = (float)(filtered[index] * (1.0 - fraction) + filtered[index + 1] * fraction);
            }
            else if(index < filtered.length)
            {
                output[x] = filtered[index];
            }
        }

        return output;
    }

    //---------------------------------------------------------------------------------------------------------------

    /**
     * Scramble parameter configuration for the Phase 2 probe.  Auto-detect is enabled so the decoder does not apply a
     * fixed sequence; identification relies on sync detection rather than message decoding.
     */
    private static DecodeConfigP25Phase2 phase2Config()
    {
        DecodeConfigP25Phase2 config = new DecodeConfigP25Phase2();
        config.setAutoDetectScrambleParameters(true);
        return config;
    }

    /**
     * Phase 2 decoder that exposes its message framer's sync detect callback.  A standalone clip has no control
     * channel to supply WACN/SYSTEM/NAC, so Phase 2 payloads cannot be descrambled offline - but the sync pattern is
     * not scrambled, so counting sync detections reliably identifies the waveform.
     */
    private static class P25P2Probe extends P25P2DecoderHDQPSK
    {
        P25P2Probe(DecodeConfigP25Phase2 config)
        {
            super(config);
        }

        void countSyncDetects(ISyncDetectListener listener)
        {
            if(mMessageFramer != null)
            {
                mMessageFramer.setSyncDetectListener(listener);
            }
        }
    }

    /**
     * One decoder run against the clip, collecting valid messages and identifiers.
     */
    private static class Candidate
    {
        private final String mName;
        private final Decoder mDecoder;
        private int mValid = 0;
        private final Set<String> mTalkgroups = new LinkedHashSet<>();
        private final Set<String> mRadios = new LinkedHashSet<>();
        private final TreeMap<String,Integer> mExtras = new TreeMap<>();
        private int mDirectModeBursts = 0;
        private int mMobileBursts = 0;
        private int mBaseBursts = 0;
        private int mSyncDetects = 0;

        Candidate(String name, Decoder decoder)
        {
            mName = name;
            mDecoder = decoder;
        }

        /**
         * Evidence count used both to rank candidates and to show the user how solid an identification is.
         */
        int score()
        {
            return mDecoder instanceof P25P2Probe ? mSyncDetects : mValid;
        }

        /**
         * A candidate may claim the clip only with enough evidence AND a consistent identifying parameter.  Decoded
         * noise produces a few valid-looking frames carrying scattered color codes / RANs / NACs; a real signal
         * repeats one value.
         */
        boolean isIdentified()
        {
            if(mDecoder instanceof P25P2Probe)
            {
                return mSyncDetects >= MINIMUM_P25P2_SYNC_DETECTS;
            }

            if(mValid < MINIMUM_VALID_MESSAGES)
            {
                return false;
            }

            int total = 0;
            int dominant = 0;

            for(Integer count : mExtras.values())
            {
                total += count;
                dominant = Math.max(dominant, count);
            }

            //No keyed parameter was observed at all - fall back to the message count alone.
            if(total == 0)
            {
                return true;
            }

            return total >= MINIMUM_KEYED_OBSERVATIONS && (double)dominant / total >= MINIMUM_KEY_DOMINANCE;
        }

        void run(Loaded loaded)
        {
            mDecoder.setMessageListener(this::receive);

            //Phase 2 rebuilds its demodulator and message framer inside setSampleRate, so the sample rate must be set
            //and the sync listener installed before start() - otherwise start() primes objects that are thrown away.
            if(mDecoder instanceof P25P2Probe p2)
            {
                p2.setSampleRate(loaded.sampleRate);
                p2.countSyncDetects(new ISyncDetectListener()
                {
                    @Override
                    public void syncDetected(int bitErrors)
                    {
                        mSyncDetects++;
                    }

                    @Override
                    public void syncLost(int bitsProcessed)
                    {
                        //No action - only detections are counted.
                    }
                });
                p2.start();
                feed(loaded, p2::receive);
                p2.stop();
                return;
            }

            mDecoder.start();

            if(mDecoder instanceof DMRDecoder dmr)
            {
                dmr.setSampleRate(loaded.sampleRate);
                feed(loaded, dmr::receive);
            }
            else if(mDecoder instanceof NXDNDecoder nxdn)
            {
                nxdn.setSampleRate(loaded.sampleRate);
                feed(loaded, nxdn::receive);
            }
            else if(mDecoder instanceof P25P1DecoderC4FM p25)
            {
                p25.setSampleRate(loaded.sampleRate);
                feed(loaded, p25::receive);
            }
            else if(mDecoder instanceof P25P1DecoderLSM lsm)
            {
                lsm.setSampleRate(loaded.sampleRate);
                feed(loaded, lsm::receive);
            }

            mDecoder.stop();
        }

        private void feed(Loaded loaded, java.util.function.Consumer<ComplexSamples> sink)
        {
            long timestamp = System.currentTimeMillis();

            for(int start = 0; start < loaded.count; start += CHUNK)
            {
                int length = Math.min(CHUNK, loaded.count - start);
                float[] i = new float[length];
                float[] q = new float[length];
                System.arraycopy(loaded.i, start, i, 0, length);
                System.arraycopy(loaded.q, start, q, 0, length);
                sink.accept(new ComplexSamples(i, q, timestamp));
            }
        }

        private void receive(IMessage message)
        {
            if(message == null || message instanceof SyncLossMessage || message instanceof EmptyTimeslotPlaceholderMessage)
            {
                return;
            }

            if(!message.isValid())
            {
                return;
            }

            mValid++;

            if(message instanceof DMRBurst burst)
            {
                if(burst.getSyncPattern().isDirect())
                {
                    mDirectModeBursts++;
                }
                else if(burst.getSyncPattern().isMobileSyncPattern())
                {
                    mMobileBursts++;
                }
                else
                {
                    mBaseBursts++;
                }

                if(message instanceof DataMessage data && data.getSlotType() != null && data.getSlotType().isValid())
                {
                    count("CC " + data.getSlotType().getColorCode());
                }
                else if(message instanceof VoiceEMBMessage voice && voice.getEMB() != null && voice.getEMB().isValid())
                {
                    count("CC " + voice.getEMB().getColorCode());
                }
            }
            else if(message instanceof NXDNMessage nxdn)
            {
                count("RAN " + nxdn.getRAN());
            }
            else if(message instanceof P25P1Message p25 && p25.getNAC() != null)
            {
                count("NAC " + p25.getNAC());
            }

            List<Identifier> identifiers = message.getIdentifiers();

            if(identifiers != null)
            {
                for(Identifier identifier : identifiers)
                {
                    if(identifier == null || identifier.getIdentifierClass() != IdentifierClass.USER)
                    {
                        continue;
                    }

                    Form form = identifier.getForm();

                    if(form == Form.TALKGROUP || form == Form.PATCH_GROUP)
                    {
                        mTalkgroups.add(String.valueOf(identifier.getValue()));
                    }
                    else if(form == Form.RADIO && identifier.getRole() == Role.FROM)
                    {
                        mRadios.add(String.valueOf(identifier.getValue()));
                    }
                    else if(form == Form.RADIO && identifier.getRole() == Role.TO)
                    {
                        mTalkgroups.add("R" + identifier.getValue());
                    }
                }
            }
        }

        private void count(String key)
        {
            mExtras.merge(key, 1, Integer::sum);
        }

        String describe()
        {
            StringBuilder sb = new StringBuilder();

            //Most frequent color code / RAN / NAC
            String bestExtra = null;
            int bestCount = 0;

            for(var entry : mExtras.entrySet())
            {
                if(entry.getValue() > bestCount)
                {
                    bestCount = entry.getValue();
                    bestExtra = entry.getKey();
                }
            }

            if(bestExtra != null)
            {
                sb.append(bestExtra);
            }

            if(mDecoder instanceof DMRDecoder)
            {
                if(mDirectModeBursts > mBaseBursts && mDirectModeBursts > mMobileBursts)
                {
                    append(sb, "DIRECT MODE");
                }
                else if(mMobileBursts > mBaseBursts)
                {
                    append(sb, "MOBILE/TALKAROUND");
                }
                else if(mBaseBursts > 0)
                {
                    append(sb, "REPEATER");
                }
            }

            if(!mTalkgroups.isEmpty())
            {
                append(sb, "TO " + String.join("/", limit(mTalkgroups, 4)));
            }

            if(!mRadios.isEmpty())
            {
                append(sb, "FROM " + String.join("/", limit(mRadios, 4)));
            }

            append(sb, mValid + " frames");
            return sb.toString();
        }

        private static List<String> limit(Set<String> values, int max)
        {
            List<String> list = new ArrayList<>(values);
            return list.size() > max ? list.subList(0, max) : list;
        }

        private static void append(StringBuilder sb, String value)
        {
            if(sb.length() > 0)
            {
                sb.append(" | ");
            }

            sb.append(value);
        }
    }
}
