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
import io.github.dsheirer.module.decode.nbfm.CTCSSDetector;
import io.github.dsheirer.module.decode.nxdn.DecodeConfigNXDN;
import io.github.dsheirer.module.decode.nxdn.NXDNDecoder;
import io.github.dsheirer.module.decode.nxdn.NXDNMessage;
import io.github.dsheirer.module.decode.nxdn.layer3.type.TransmissionMode;
import io.github.dsheirer.module.decode.p25.phase1.P25P1DecoderC4FM;
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
    private static final int MINIMUM_VALID_MESSAGES = 3;
    private static final long RASTER_HZ = 6250;

    public record Result(boolean identified, String protocol, String details, long carrierFrequency, double bandwidthHz) {}

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
            return new Result(false, "", "Clip too short", 0, 0);
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
            candidates.add(new Candidate("P25 Phase 1", new P25P1DecoderC4FM()));
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

            if(candidate.mValid >= MINIMUM_VALID_MESSAGES && (best == null || candidate.mValid > best.mValid))
            {
                best = candidate;
            }
        }

        String bandwidth = String.format("BW %.1f kHz", spectrum.bandwidthHz / 1000.0);

        if(best != null)
        {
            String details = best.describe();
            return new Result(true, best.mName, (details.isEmpty() ? "" : details + " ") + bandwidth, carrier,
                spectrum.bandwidthHz);
        }

        //Analog fallback
        String analog = analyzeAnalog(loaded);
        String protocol = spectrum.bandwidthHz > 0 ? "Analog / Unknown" : "Unknown";
        return new Result(false, protocol, (analog.isEmpty() ? "" : analog + " ") + bandwidth, carrier, spectrum.bandwidthHz);
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
     * FM demodulates the clip and looks for a CTCSS tone.
     */
    private String analyzeAnalog(Loaded loaded)
    {
        try
        {
            IDemodulator demodulator = FmDemodulatorFactory.getFmDemodulator();
            float[] audio = demodulator.demodulate(loaded.i, loaded.q);

            //Crude decimation to ~8 kHz for the tone detector (CTCSS is below 260 Hz so boxcar averaging is fine)
            int decimation = Math.max(1, (int)Math.round(loaded.sampleRate / 8000.0));
            float toneRate = (float)(loaded.sampleRate / decimation);
            float[] decimated = new float[audio.length / decimation];

            for(int x = 0; x < decimated.length; x++)
            {
                float sum = 0;
                int base = x * decimation;

                for(int y = 0; y < decimation; y++)
                {
                    sum += audio[base + y];
                }

                decimated[x] = sum / decimation;
            }

            CTCSSDetector detector = new CTCSSDetector(null, toneRate);
            detector.process(decimated);
            CTCSSCode code = detector.getDetectedCode();

            if(code == null)
            {
                code = detector.getRawDetectedCode();
            }

            if(code != null)
            {
                return "CTCSS " + code;
            }
        }
        catch(Throwable t)
        {
            mLog.debug("Analog analysis failed", t);
        }

        return "";
    }

    //---------------------------------------------------------------------------------------------------------------

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

        Candidate(String name, Decoder decoder)
        {
            mName = name;
            mDecoder = decoder;
        }

        void run(Loaded loaded)
        {
            mDecoder.setMessageListener(this::receive);
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
