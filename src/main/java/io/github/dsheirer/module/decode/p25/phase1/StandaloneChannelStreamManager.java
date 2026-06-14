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

import io.github.dsheirer.util.ThreadPool;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Iterator;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Singleton TCP server that broadcasts standalone (non-trunking) channel liveness as NDJSON to
 * connected clients (default port 9504).
 *
 * Trunking channels already advertise liveness on the raw/event streams (9500/9501) because control
 * traffic flows continuously.  A conventional channel (NBFM, AM, conventional DMR, ...) that is running
 * but quiet emits nothing, so a downstream consumer cannot distinguish "running but idle" from "down".
 * This dedicated stream fixes that: every running standalone channel emits a periodic heartbeat here,
 * plus an immediate channel_up when it starts and a channel_down when it stops.  A consumer connects to
 * this single port and tracks each channel's liveness by the "channel" field.
 *
 * Message types (one JSON object per line, newline-terminated):
 *   channel_up   - emitted once when a standalone channel starts (PLAY)
 *   heartbeat    - emitted every N seconds while the channel runs ({"status":"up"})
 *   channel_down - emitted once when the channel stops
 *   keepalive    - emitted on the socket every ~15s while idle so clients can tell the socket is alive
 *                  even when no standalone channel is currently running
 */
public class StandaloneChannelStreamManager
{
    private static final Logger mLog = LoggerFactory.getLogger(StandaloneChannelStreamManager.class);

    private static StandaloneChannelStreamManager sInstance;

    private final CopyOnWriteArrayList<ClientWriter> mClients = new CopyOnWriteArrayList<>();
    private volatile boolean mRunning = false;

    //Idle socket keepalive: when no message has been sent within this interval, emit a keepalive line so
    //connected clients can distinguish a quiet-but-alive socket from a dead/half-open one.
    private static final long KEEPALIVE_INTERVAL_MS = 15000;
    private volatile long mLastBroadcast = System.currentTimeMillis();

    private StandaloneChannelStreamManager() {}

    /**
     * Returns the singleton instance, creating and starting it if necessary.
     * @param port TCP port to listen on (default 9504)
     */
    public static synchronized StandaloneChannelStreamManager getInstance(int port)
    {
        if(sInstance == null)
        {
            StandaloneChannelStreamManager mgr = new StandaloneChannelStreamManager();
            mgr.startAcceptLoop(port);
            ThreadPool.SCHEDULED.scheduleAtFixedRate(mgr::sendKeepaliveIfIdle, KEEPALIVE_INTERVAL_MS,
                    KEEPALIVE_INTERVAL_MS, TimeUnit.MILLISECONDS);
            sInstance = mgr;
        }
        return sInstance;
    }

    /**
     * Returns the singleton instance if already created, or null if not yet initialized.
     */
    public static StandaloneChannelStreamManager getInstance()
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
        while(it.hasNext())
        {
            ClientWriter writer = it.next();
            if(!writer.isAlive())
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
     * Sends a keepalive line when the socket has been idle, so clients never mistake a quiet system for
     * a dead one.  Exceptions are swallowed so the scheduled task can never die.
     */
    private void sendKeepaliveIfIdle()
    {
        try
        {
            if(mRunning && !mClients.isEmpty() &&
               (System.currentTimeMillis() - mLastBroadcast) >= KEEPALIVE_INTERVAL_MS)
            {
                broadcast("{\"type\":\"keepalive\"}");
            }
        }
        catch(Throwable t)
        {
            //Never let an exception kill the scheduled keepalive task.
        }
    }

    private void startAcceptLoop(int port)
    {
        Thread.ofVirtual().start(() ->
        {
            try(ServerSocket serverSocket = new ServerSocket(port))
            {
                mRunning = true;
                mLog.info("StandaloneChannelStreamManager listening on port {}", port);
                while(true)
                {
                    try
                    {
                        Socket socket = serverSocket.accept();
                        ClientWriter writer = new ClientWriter(socket);
                        mClients.add(writer);
                        mLog.debug("Standalone channel stream client connected on port {}: {}",
                                port, socket.getRemoteSocketAddress());

                        //Monitor for client disconnect.
                        Thread.ofVirtual().start(() ->
                        {
                            try
                            {
                                int read = socket.getInputStream().read();
                                if(read == -1)
                                {
                                    writer.close();
                                }
                            }
                            catch(IOException e)
                            {
                                writer.close();
                            }
                        });
                    }
                    catch(IOException e)
                    {
                        mLog.warn("Error accepting standalone channel stream client on port {}: {}", port, e.getMessage());
                    }
                }
            }
            catch(IOException e)
            {
                mLog.error("Failed to start standalone channel stream TCP server on port {}: {}", port, e.getMessage());
            }
        });
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
                while(mAlive)
                {
                    try
                    {
                        String line = mQueue.take();
                        mWriter.println(line);
                        mWriter.flush();
                        if(mWriter.checkError())
                        {
                            mAlive = false;
                        }
                    }
                    catch(InterruptedException e)
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
            catch(IOException e)
            {
                // ignore
            }
        }
    }
}
