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
package io.github.dsheirer.module.decode.p25.phase1;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import io.github.dsheirer.util.ThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Singleton TCP server that broadcasts decoded PCM audio as NDJSON to connected clients.
 *
 * Three message types are emitted:
 *   call_start — once when a new transmission begins (squelch opens)
 *   pcm        — once per decoded audio chunk (float[] samples converted to 16-bit little-endian PCM)
 *   call_end   — once when a transmission ends (squelch closes)
 *   voice_id   — fast SUID notification, emitted ~180ms after squelch open
 *   heartbeat  — every ~5 seconds while idle, so clients can distinguish quiet air from a dead socket
 *
 * Audio format: 16-bit signed little-endian PCM at 8000 Hz mono, Base64-encoded.
 * No JMBE library is required on the client — the audio has already been decoded by SDRTrunk.
 *
 * Multiple clients can connect simultaneously; each receives a full copy of the stream.
 * Consumers should demultiplex by the "callId" field, which is unique per transmission.
 *
 * ---------------------------------------------------------------------------------------------------
 * EGRESS PACE BUFFER (even-pacing fix)
 * ---------------------------------------------------------------------------------------------------
 * Decoded audio is handed to {@link #broadcastPcm} as it is produced. Locally-decoded (native) calls
 * produce frames in near real-time, but ISSI/bridged calls arrive in network batches — the decoder
 * emits a clump of frames at once. Broadcasting inline therefore produced a "stall, then burst" shape
 * on the wire (e.g. ~548ms of dead air followed by ~27 frames with ~0ms inter-frame gap), which any
 * real-time client audio scheduler chokes on (jitter-buffer overflow then underrun, ~2x/second).
 *
 * To normalize ALL sources in one place, pcm frames are no longer written inline. Each frame is
 * appended to a small per-callId FIFO. A single drain thread pops one frame per active call every
 * {@link #FRAME_INTERVAL_MS} (20ms = one 8kHz/160-sample voice frame) and broadcasts it — so the wire
 * cadence becomes a steady ~1 frame / 20ms for native and ISSI alike, exactly what the existing
 * clients already handle with their default buffer.
 *
 * Properties:
 *   - Metadata (call_start / voice_id) is NOT paced — it is broadcast immediately so caller IDs and
 *     call boundaries stay prompt and correctly ordered ahead of the (delayed) audio.
 *   - call_end is deferred until that call's buffered audio has fully drained, so no frames are ever
 *     truncated (zero audio loss); the tail flushes at the 20ms cadence and call_end follows it.
 *   - Added latency is bounded by buffer depth and drains back toward zero between bursts; it does not
 *     accumulate. A gentle catch-up ({@link #CATCHUP_HIGH_WATER}) and a {@link #HARD_CAP} safety valve
 *     prevent any unbounded growth if a source ever runs persistently faster than real time.
 *   - The JSON schema is unchanged.
 */
public class PcmStreamManager
{
    private static final Logger mLog = LoggerFactory.getLogger(PcmStreamManager.class);

    private static PcmStreamManager sInstance;

    private final CopyOnWriteArrayList<ClientWriter> mClients = new CopyOnWriteArrayList<>();
    private volatile boolean mRunning = false;

    //Idle heartbeat: when no broadcast has occurred within this interval, send a heartbeat line so connected
    //clients can distinguish a quiet system from a dead/half-open socket (prevents client-side stall watchdogs
    //from forcing unnecessary reconnects during quiet air).
    private static final long HEARTBEAT_INTERVAL_MS = 5000;
    private volatile long mLastBroadcast = System.currentTimeMillis();

    //--- Egress pace buffer tuning -------------------------------------------------------------------
    /** One 8kHz / 160-sample voice frame represents 20ms of audio; drain one frame per this interval. */
    private static final long FRAME_INTERVAL_MS = 20;
    /** Drain thread loop interval in nanoseconds. */
    private static final long FRAME_INTERVAL_NANOS = FRAME_INTERVAL_MS * 1_000_000L;
    /** Above this backlog (~800ms) the drain gently accelerates to catch up; well above a normal ISSI burst (~27). */
    private static final int CATCHUP_HIGH_WATER = 40;
    /** Frames drained per tick while above the high-water mark (2 = 2x real time — transient, never bursty). */
    private static final int CATCHUP_RATE = 2;
    /** Absolute per-call backlog cap (~5s). Beyond this the oldest frame is dropped to bound memory (pathological only). */
    private static final int HARD_CAP = 250;
    /** A call with an empty buffer and no call_end seen for this long is purged as a safety net against lost call_end. */
    private static final long STALE_CALL_MS = 60_000;

    /** Active per-call pace buffers, keyed by callId. */
    private final ConcurrentHashMap<String, PacedCall> mPacedCalls = new ConcurrentHashMap<>();
    private volatile boolean mDrainStarted = false;

    private PcmStreamManager() {}

    /**
     * Returns the singleton instance, creating and starting it if necessary.
     * @param port TCP port to listen on (default 9503)
     */
    public static synchronized PcmStreamManager getInstance(int port)
    {
        if (sInstance == null)
        {
            PcmStreamManager mgr = new PcmStreamManager();
            mgr.startAcceptLoop(port);
            mgr.startDrainLoop();
            ThreadPool.SCHEDULED.scheduleAtFixedRate(mgr::sendHeartbeatIfIdle, HEARTBEAT_INTERVAL_MS,
                HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
            sInstance = mgr;
        }
        return sInstance;
    }

    /**
     * Returns the singleton instance if already created, or null if not yet initialized.
     * Used by AbstractAudioModule to null-safely check whether the stream is active.
     */
    public static PcmStreamManager getInstance()
    {
        return sInstance;
    }

    /**
     * Returns true if this manager has been started and is listening for clients.
     */
    public boolean isRunning()
    {
        return mRunning;
    }

    /**
     * Broadcasts a single NDJSON line to all connected clients.
     */
    public void broadcast(String json)
    {
        mLastBroadcast = System.currentTimeMillis();

        Iterator<ClientWriter> it = mClients.iterator();
        while (it.hasNext())
        {
            ClientWriter writer = it.next();
            if (!writer.isAlive())
            {
                mClients.remove(writer);
            }
            else
            {
                writer.offer(json);
            }
        }
    }

    /**
     * Sends a heartbeat line to connected clients when no broadcast has occurred within the heartbeat
     * interval.  Quiet air keeps the line verifiably alive; busy air needs no heartbeat because the
     * audio itself proves liveness.  Exceptions are swallowed so the scheduled task can never die.
     */
    private void sendHeartbeatIfIdle()
    {
        try
        {
            if(mRunning && !mClients.isEmpty() &&
               (System.currentTimeMillis() - mLastBroadcast) >= HEARTBEAT_INTERVAL_MS)
            {
                broadcast("{\"type\":\"heartbeat\"}");
            }
        }
        catch(Throwable t)
        {
            //Never let an exception kill the scheduled heartbeat task
        }
    }

    /**
     * Broadcasts a call_start message.
     *
     * @param callId    unique identifier for this call
     * @param system    system name
     * @param talkgroup talkgroup identifier
     * @param from      source radio unit identifier
     * @param timestamp human-readable timestamp (yyyy-MM-dd HH:mm:ss)
     */
    public void broadcastCallStart(String callId, String system, String site, String talkgroup, String from, String timestamp)
    {
        String json = "{\"type\":\"call_start\"" +
                ",\"callId\":\"" + escape(callId) + "\"" +
                ",\"system\":\"" + escape(system) + "\"" +
                ",\"site\":\"" + escape(site) + "\"" +
                ",\"talkgroup\":\"" + escape(talkgroup) + "\"" +
                ",\"from\":\"" + escape(from) + "\"" +
                ",\"timestamp\":\"" + escape(timestamp) + "\"}";
        //Metadata is not paced — emit promptly, ahead of this call's (paced) audio.
        broadcast(json);
    }

    /**
     * Broadcasts a pcm chunk message.
     *
     * Converts the float[] samples to 16-bit signed little-endian PCM and Base64-encodes them, then
     * appends the line to this call's pace buffer. The drain loop releases it at a steady 20ms cadence;
     * the frame is NOT written to the socket inline (see EGRESS PACE BUFFER in the class javadoc).
     *
     * @param callId    unique identifier for this call
     * @param system    system name
     * @param talkgroup talkgroup identifier
     * @param from      source radio unit identifier
     * @param seq       zero-based chunk sequence number within this call
     * @param samples   decoded PCM float samples (8000 Hz mono, range -1.0 to 1.0)
     */
    public void broadcastPcm(String callId, String system, String site, String talkgroup, String from, int seq, float[] samples)
    {
        ByteBuffer buf = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (float sample : samples)
        {
            short s = (short) Math.max(-32768, Math.min(32767, Math.round(sample * 32767f)));
            buf.putShort(s);
        }
        String samplesB64 = Base64.getEncoder().encodeToString(buf.array());

        String json = "{\"type\":\"pcm\"" +
                ",\"callId\":\"" + escape(callId) + "\"" +
                ",\"system\":\"" + escape(system) + "\"" +
                ",\"site\":\"" + escape(site) + "\"" +
                ",\"talkgroup\":\"" + escape(talkgroup) + "\"" +
                ",\"from\":\"" + escape(from) + "\"" +
                ",\"seq\":" + seq +
                ",\"samples\":\"" + samplesB64 + "\"}";

        //Pace buffer: enqueue rather than broadcast inline.
        PacedCall call = mPacedCalls.computeIfAbsent(callId, PacedCall::new);
        synchronized(call)
        {
            if(call.queue.size() >= HARD_CAP)
            {
                //Pathological safety valve only: a source running persistently faster than real time.
                //Drop the oldest frame to bound memory/latency. Should never occur at ~real-time rates.
                call.queue.pollFirst();
                if(!call.capWarned)
                {
                    call.capWarned = true;
                    mLog.warn("PCM pace buffer hit hard cap ({} frames) for call {}; dropping oldest frame(s)",
                            HARD_CAP, callId);
                }
            }
            call.queue.addLast(json);
            call.lastActivityMs = System.currentTimeMillis();
            call.system = system;
            call.site = site;
            call.talkgroup = talkgroup;
            call.frameCount++;
        }
    }

    /**
     * Broadcasts a call_end message.
     *
     * If audio for this call is still buffered, the call_end is deferred until the buffer has fully
     * drained so no frames are truncated; otherwise it is emitted immediately.
     *
     * @param callId     unique identifier for this call
     * @param system     system name
     * @param talkgroup  talkgroup identifier
     * @param frameCount total number of pcm chunks sent for this call
     */
    public void broadcastCallEnd(String callId, String system, String site, String talkgroup, int frameCount)
    {
        String json = "{\"type\":\"call_end\"" +
                ",\"callId\":\"" + escape(callId) + "\"" +
                ",\"system\":\"" + escape(system) + "\"" +
                ",\"site\":\"" + escape(site) + "\"" +
                ",\"talkgroup\":\"" + escape(talkgroup) + "\"" +
                ",\"frames\":" + frameCount + "}";

        PacedCall call = mPacedCalls.get(callId);
        if(call != null)
        {
            //Audio may still be draining — hand call_end to the drain loop to emit after the last frame.
            synchronized(call)
            {
                call.endJson = json;
                call.ended = true;
                call.lastActivityMs = System.currentTimeMillis();
            }
        }
        else
        {
            //No audio was buffered for this call (e.g. a call with no decoded frames) — emit immediately.
            broadcast(json);
        }
    }

    /**
     * Broadcasts a voice_id message when the first LDU1 Link Control Word is decoded (~180ms after squelch open).
     * This fires BEFORE the audio module releases audio, giving consumers a guaranteed fast SUID notification.
     *
     * @param system    system name from SDRTrunk configuration
     * @param site      site name from SDRTrunk configuration
     * @param talkgroup talkgroup identifier
     * @param from      source radio unit identifier (SUID)
     * @param timestamp human-readable timestamp (yyyy-MM-dd HH:mm:ss)
     */
    public void broadcastVoiceId(String system, String site, String talkgroup, String from, String timestamp)
    {
        String json = "{\"type\":\"voice_id\"" +
                ",\"system\":\"" + escape(system) + "\"" +
                ",\"site\":\"" + escape(site) + "\"" +
                ",\"talkgroup\":\"" + escape(talkgroup) + "\"" +
                ",\"from\":\"" + escape(from) + "\"" +
                ",\"timestamp\":\"" + escape(timestamp) + "\"}";
        //Metadata is not paced — emit promptly.
        broadcast(json);
    }

    private static String escape(String s)
    {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", "");
    }

    /**
     * Starts the single drain thread that releases buffered pcm frames at a steady cadence. The loop
     * uses a monotonic-clock schedule with drift compensation so the cadence stays even regardless of
     * per-tick work, and resynchronizes if it ever falls behind (it never bursts to "make up" time).
     */
    private void startDrainLoop()
    {
        if(mDrainStarted)
        {
            return;
        }
        mDrainStarted = true;

        Thread.ofVirtual().name("pcm-pace-drain").start(() ->
        {
            long next = System.nanoTime();
            while(true)
            {
                next += FRAME_INTERVAL_NANOS;

                try
                {
                    drainOnce();
                }
                catch(Throwable t)
                {
                    //Never let the pacing thread die.
                    mLog.warn("PCM pace drain error: {}", t.getMessage());
                }

                long sleep = next - System.nanoTime();
                if(sleep > 0)
                {
                    LockSupport.parkNanos(sleep);
                }
                else
                {
                    //Fell behind (GC, scheduling). Resync to now so we never deliver a catch-up burst.
                    next = System.nanoTime();
                }
            }
        });
    }

    /**
     * One drain tick: for every active call, release up to one frame (or {@link #CATCHUP_RATE} while the
     * backlog exceeds {@link #CATCHUP_HIGH_WATER}); when a call's buffer empties and call_end has arrived,
     * emit call_end and retire the call. Frames are collected under the per-call lock and broadcast outside
     * it to keep the lock hold minimal.
     */
    private void drainOnce()
    {
        long now = System.currentTimeMillis();

        Iterator<Map.Entry<String, PacedCall>> it = mPacedCalls.entrySet().iterator();
        while(it.hasNext())
        {
            Map.Entry<String, PacedCall> entry = it.next();
            PacedCall call = entry.getValue();

            List<String> toSend = null;
            String endToSend = null;
            boolean retire = false;
            boolean logCatchup = false;
            int catchupBacklog = 0;

            synchronized(call)
            {
                int qsize = call.queue.size();
                int drain;
                if(qsize > CATCHUP_HIGH_WATER)
                {
                    //Backlog exceeded the high-water mark — accelerate to catch the buffer down (never drop).
                    drain = CATCHUP_RATE;
                    if(!call.catchupActive)
                    {
                        call.catchupActive = true;   //log once per engagement, not every tick
                        logCatchup = true;
                        catchupBacklog = qsize;
                    }
                }
                else
                {
                    drain = 1;
                    if(call.catchupActive && qsize <= CATCHUP_HIGH_WATER / 2)
                    {
                        call.catchupActive = false;  //cleared — next engagement will log again
                    }
                }
                for(int i = 0; i < drain && !call.queue.isEmpty(); i++)
                {
                    if(toSend == null)
                    {
                        toSend = new ArrayList<>(drain);
                    }
                    toSend.add(call.queue.pollFirst());
                }

                if(call.queue.isEmpty())
                {
                    if(call.ended)
                    {
                        endToSend = call.endJson;   //may be null in theory; guarded below
                        retire = true;
                    }
                    else if(now - call.lastActivityMs > STALE_CALL_MS)
                    {
                        //Safety net: call_end never arrived. Synthesize one (identical schema) so clients
                        //tear the channel down cleanly instead of waiting on a downstream grant timeout.
                        endToSend = "{\"type\":\"call_end\"" +
                                ",\"callId\":\"" + escape(entry.getKey()) + "\"" +
                                ",\"system\":\"" + escape(call.system) + "\"" +
                                ",\"site\":\"" + escape(call.site) + "\"" +
                                ",\"talkgroup\":\"" + escape(call.talkgroup) + "\"" +
                                ",\"frames\":" + call.frameCount + "}";
                        retire = true;
                    }
                }
            }

            if(logCatchup)
            {
                mLog.info("PCM pace catch-up engaged for call {} (backlog {} frames, ~{}ms); draining {}x/frame until it clears",
                        entry.getKey(), catchupBacklog, catchupBacklog * 20, CATCHUP_RATE);
            }

            if(toSend != null)
            {
                for(String line : toSend)
                {
                    broadcast(line);
                }
            }
            if(endToSend != null)
            {
                broadcast(endToSend);
            }
            if(retire)
            {
                //Remove only if the mapping is still this exact instance (avoids racing a recreated call).
                mPacedCalls.remove(entry.getKey(), call);
            }
        }
    }

    private void startAcceptLoop(int port)
    {
        Thread.ofVirtual().start(() ->
        {
            try (ServerSocket serverSocket = new ServerSocket(port))
            {
                mRunning = true;
                mLog.info("PcmStreamManager listening on port {}", port);
                while (true)
                {
                    try
                    {
                        Socket socket = serverSocket.accept();
                        ClientWriter writer = new ClientWriter(socket);
                        mClients.add(writer);
                        mLog.debug("PCM stream client connected on port {}: {}",
                                port, socket.getRemoteSocketAddress());

                        // Monitor for client disconnect
                        Thread.ofVirtual().start(() ->
                        {
                            try
                            {
                                int read = socket.getInputStream().read();
                                if (read == -1)
                                {
                                    writer.close();
                                }
                            }
                            catch (IOException e)
                            {
                                writer.close();
                            }
                        });
                    }
                    catch (IOException e)
                    {
                        mLog.warn("Error accepting PCM stream client on port {}: {}", port, e.getMessage());
                    }
                }
            }
            catch (IOException e)
            {
                mLog.error("Failed to start PCM stream TCP server on port {}: {}", port, e.getMessage());
            }
        });
    }

    /**
     * Per-call egress pace buffer. A FIFO of pre-serialized pcm NDJSON lines plus the deferred call_end
     * state. Guarded by synchronizing on the instance.
     */
    private static final class PacedCall
    {
        private final ArrayDeque<String> queue = new ArrayDeque<>();
        private volatile boolean ended = false;
        private String endJson = null;
        private long lastActivityMs = System.currentTimeMillis();
        private boolean capWarned = false;
        private boolean catchupActive = false;
        //Last-seen metadata, retained so a synthetic call_end can be built if this call is reaped.
        private String system = "";
        private String site = "";
        private String talkgroup = "";
        private int frameCount = 0;

        private PacedCall(String callId)
        {
            //callId retained implicitly via the map key; constructor kept for computeIfAbsent ergonomics.
        }
    }

    /**
     * Wraps a connected client socket with a non-blocking queue-based writer.
     */
    public static class ClientWriter
    {
        private final Socket mSocket;
        private final PrintWriter mWriter;
        private final ArrayBlockingQueue<String> mQueue = new ArrayBlockingQueue<>(1024);
        private volatile boolean mAlive = true;

        public ClientWriter(Socket socket) throws IOException
        {
            mSocket = socket;
            mWriter = new PrintWriter(socket.getOutputStream(), false);

            Thread.ofVirtual().start(() ->
            {
                while (mAlive)
                {
                    try
                    {
                        String line = mQueue.take();
                        mWriter.println(line);
                        mWriter.flush();
                        if (mWriter.checkError())
                        {
                            mAlive = false;
                        }
                    }
                    catch (InterruptedException e)
                    {
                        Thread.currentThread().interrupt();
                        mAlive = false;
                    }
                }
            });
        }

        public boolean offer(String json)
        {
            return mQueue.offer(json);
        }

        public boolean isAlive()
        {
            return mAlive;
        }

        public void close()
        {
            mAlive = false;
            try
            {
                mSocket.close();
            }
            catch (IOException e)
            {
                // ignore
            }
        }
    }
}
