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
import java.util.UUID;

public abstract class StreamEvent
{
    public static enum Type
    {
        STREAM_PREPARED,
        STREAM_COMPLETE,
        FILE_PROGRESS,
    }

    public final Type eventType;
    public final UUID planId;
    public final int sessionIndex;

    protected StreamEvent(Type eventType, UUID planId, int sessionIndex)
    {
        this.eventType = eventType;
        this.planId = planId;
        this.sessionIndex = sessionIndex;
    }

    public static class SessionCompleteEvent extends StreamEvent
    {
        public final InetAddress peer;
        public final boolean success;

        public SessionCompleteEvent(StreamSession session)
        {
            super(Type.STREAM_COMPLETE, session.planId(), session.sessionIndex());
            this.peer = session.peer;
            this.success = session.isSuccess();
        }
    }

    public static class ProgressEvent extends StreamEvent
    {
        public final ProgressInfo progress;

        public ProgressEvent(UUID planId, ProgressInfo progress)
        {
            super(Type.FILE_PROGRESS, planId, progress.sessionIndex);
            this.progress = progress;
        }

        @Override
        public String toString()
        {
            return "<ProgressEvent " + progress.toString() + ">";
        }
    }

    public static class SessionPreparedEvent extends StreamEvent
    {
        public final SessionInfo session;

        public SessionPreparedEvent(UUID planId, SessionInfo session)
        {
            super(Type.STREAM_PREPARED, planId, session.sessionIndex);
            this.session = session;
        }
    }
}
