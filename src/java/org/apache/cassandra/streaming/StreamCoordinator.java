/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.streaming;

import java.net.InetAddress;
import java.util.*;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import org.apache.cassandra.concurrent.DebuggableThreadPoolExecutor;
import org.apache.cassandra.utils.FBUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link StreamCoordinator} is a helper class that abstracts away maintaining multiple
 * StreamSession and ProgressInfo instances per peer.
 *
 * This class coordinates multiple SessionStreams per peer in both the outgoing StreamPlan context and on the
 * inbound StreamResultFuture context.
 */
public class StreamCoordinator
{
    private static final Logger logger = LoggerFactory.getLogger(StreamCoordinator.class);
    private Map<InetAddress, HostStreamingData> peerSessions = new HashMap<>();
    private int connectionsPerHost = 1;

    // Executor strictly for establishing the initial connections. Once we're connected to the other end the rest of the
    // streaming is handled directly by the ConnectionHandler's incoming and outgoing threads.
    private static final DebuggableThreadPoolExecutor streamExecutor = DebuggableThreadPoolExecutor.createWithFixedPoolSize("StreamConnectionEstablisher",
            FBUtilities.getAvailableProcessors());

    public StreamCoordinator(int connectionsPerHost)
    {
        this.connectionsPerHost = connectionsPerHost;
    }

    public synchronized boolean hasActiveSessions()
    {
        for (Map.Entry<InetAddress, HostStreamingData> pair : peerSessions.entrySet())
        {
            if (pair.getValue().hasActiveSessions())
                return true;
        }
        return false;
    }

    public synchronized void setConnectionsPerHost(int value)
    {
        connectionsPerHost = value;
    }

    public synchronized Collection<StreamSession> getAllStreamSessions()
    {
        Collection<StreamSession> results = new ArrayList<>();
        for (Map.Entry<InetAddress, HostStreamingData> pair : peerSessions.entrySet())
        {
            results.addAll(pair.getValue().getAllStreamSessions());
        }
        return results;
    }

    public void connectAllStreamSessions()
    {
        for (Map.Entry<InetAddress, HostStreamingData> pair : peerSessions.entrySet())
            pair.getValue().connectAllStreamSessions();
    }

    public synchronized Set<InetAddress> getPeers()
    {
        return new HashSet<>(peerSessions.keySet());
    }

    public synchronized StreamSession getOrCreateNextSession(InetAddress peer)
    {
        return getOrCreateHostData(peer).getOrCreateNextSession(peer);
    }

    public synchronized StreamSession getOrCreateSessionById(InetAddress peer, int id)
    {
        return getOrCreateHostData(peer).getOrCreateSessionById(peer, id);
    }

    public synchronized void addStreamSession(StreamSession session)
    {
        getOrCreateHostData(session.peer).addStreamSession(session);
    }

    public synchronized void updateProgress(ProgressInfo info)
    {
        getHostData(info.peer).updateProgress(info);
    }

    public synchronized Collection<ProgressInfo> getSessionProgress(InetAddress peer, int index, ProgressInfo.Direction dir)
    {
        return getHostData(peer).getSessionProgress(index, dir);
    }

    public synchronized void addSessionInfo(SessionInfo session)
    {
        HostStreamingData data = getOrCreateHostData(session.peer);
        data.addSessionInfo(session);
    }

    public synchronized Collection<SessionInfo> getHostSessionInfo(InetAddress peer)
    {
        return ImmutableSet.copyOf(getHostData(peer).getAllSessionInfo());
    }

    public synchronized Set<SessionInfo> getAllSessionInfo()
    {
        Set<SessionInfo> result = new HashSet<>();
        for (HostStreamingData data : peerSessions.values())
        {
            result.addAll(data.getAllSessionInfo());
        }
        return result;
    }

    public synchronized void transferFiles(InetAddress to, Collection<StreamSession.SSTableStreamingSections> sstableDetails)
    {
        HostStreamingData sessionList = getOrCreateHostData(to);

        if (connectionsPerHost > 1)
        {
            ArrayList<ArrayList<StreamSession.SSTableStreamingSections>> buckets = sliceSSTableDetails(sstableDetails);

            for (ArrayList<StreamSession.SSTableStreamingSections> subList : buckets)
            {
                StreamSession session = sessionList.getOrCreateNextSession(to);
                session.addTransferFiles(subList);
            }
        }
        else
        {
            StreamSession session = sessionList.getOrCreateNextSession(to);
            session.addTransferFiles(sstableDetails);
        }
    }

    private ArrayList<ArrayList<StreamSession.SSTableStreamingSections>> sliceSSTableDetails(
        Collection<StreamSession.SSTableStreamingSections> sstableDetails)
    {
        // There's no point in divvying things up into more buckets than we have sstableDetails
        int targetSlices = connectionsPerHost > sstableDetails.size() ? sstableDetails.size() : connectionsPerHost;
        int step = Math.round((float) sstableDetails.size() / (float) targetSlices);
        int index = 0;

        ArrayList<ArrayList<StreamSession.SSTableStreamingSections>> result = new ArrayList<>();
        ArrayList<StreamSession.SSTableStreamingSections> slice = null;
        for (StreamSession.SSTableStreamingSections streamSession : sstableDetails)
        {
            if (index % step == 0)
            {
                slice = new ArrayList<>();
                result.add(slice);
            }
            slice.add(streamSession);
            ++index;
        }

        return result;
    }

    private HostStreamingData getHostData(InetAddress peer)
    {
        HostStreamingData data = peerSessions.get(peer);
        if (data == null)
            throw new IllegalArgumentException("Unknown peer requested: " + peer.toString());
        return data;
    }

    private HostStreamingData getOrCreateHostData(InetAddress peer)
    {
        HostStreamingData data = peerSessions.get(peer);
        if (data == null)
        {
            data = new HostStreamingData();
            peerSessions.put(peer, data);
        }
        return data;
    }

    private class StreamSessionConnector implements Runnable
    {
        private final StreamSession session;
        public StreamSessionConnector(StreamSession session)
        {
            this.session = session;
        }

        public void run()
        {
            session.start();
            logger.info("[Stream #{}, ID#{}] Beginning stream session with {}", session.planId(), session.sessionIndex(), session.peer);
        }
    }

    private class HostStreamingData
    {
        private Map<Integer, StreamSession> streamSessions = new HashMap<>();
        private Map<Integer, SessionInfo> sessionInfos = new HashMap<>();

        private int lastReturned = -1;

        public boolean hasActiveSessions()
        {
            for (Map.Entry<Integer, StreamSession> pair : streamSessions.entrySet())
            {
                StreamSession.State state = pair.getValue().state();
                if (state != StreamSession.State.COMPLETE && state != StreamSession.State.FAILED)
                    return true;
            }
            return false;
        }

        public StreamSession getOrCreateNextSession(InetAddress peer)
        {
            // create
            if (streamSessions.size() < connectionsPerHost)
            {
                StreamSession session = new StreamSession(peer, streamSessions.size());
                streamSessions.put(++lastReturned, session);
                return session;
            }
            // get
            else
            {
                if (lastReturned == streamSessions.size() - 1)
                    lastReturned = 0;

                return streamSessions.get(lastReturned++);
            }
        }

        public void addStreamSession(StreamSession session)
        {
            streamSessions.put(streamSessions.size(), session);
        }

        public void connectAllStreamSessions()
        {
            for (Map.Entry<Integer, StreamSession> pair : streamSessions.entrySet())
            {
                StreamSessionConnector runnable = new StreamSessionConnector(pair.getValue());
                streamExecutor.execute(runnable);
            }
        }

        public Collection<StreamSession> getAllStreamSessions()
        {
            return new ArrayList<>(streamSessions.values());
        }

        public StreamSession getOrCreateSessionById(InetAddress peer, int id)
        {
            StreamSession session = streamSessions.get(id);
            if (session == null)
            {
                session = new StreamSession(peer, id);
                streamSessions.put(id, session);
            }
            return session;
        }

        public void updateProgress(ProgressInfo info)
        {
            sessionInfos.get(info.sessionIndex).updateProgress(info);
        }

        public Collection<ProgressInfo> getSessionProgress(int index, ProgressInfo.Direction dir)
        {
            SessionInfo info = sessionInfos.get(index);
            return dir == ProgressInfo.Direction.IN ?
                    info.getReceivingFiles() :
                    info.getSendingFiles();
        }

        public void addSessionInfo(SessionInfo info)
        {
            sessionInfos.put(info.sessionIndex, info);
        }

        public Collection<SessionInfo> getAllSessionInfo()
        {
            return sessionInfos.values();
        }
    }
}
