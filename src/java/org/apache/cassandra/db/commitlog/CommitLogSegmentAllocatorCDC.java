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

package org.apache.cassandra.db.commitlog;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.*;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.Mutation;
import org.apache.cassandra.db.commitlog.CommitLogSegment.CDCState;
import org.apache.cassandra.exceptions.CDCWriteException;
import org.apache.cassandra.io.util.FileUtils;
import org.apache.cassandra.utils.DirectorySizeCalculator;
import org.apache.cassandra.utils.NoSpamLogger;

/**
 * A CommitLogSegmentAllocator that respects the configured total allowable CDC space on disk. On allocation of a mutation
 * checks if it's on a table tracked by CDC and, if so, either throws an exception if at CDC limit or flags that segment
 * as containing a CDC mutation if it's a new one.
 *
 * This code path is only exercised if cdc is enabled on a node. We pay the duplication cost of having both CDC and non
 * allocators in order to keep the old allocator code clean and separate from this allocator, as well as to not introduce
 * unnecessary operations on the critical path for nodes / users where they have no interest in CDC. May be worth considering
 * unifying in the future should the perf implications of this be shown to be negligible, though the hard linking and
 * size tracking is somewhat distasteful to have floating around on nodes where cdc is not in use (which we assume to be
 * the majority).
 */
public class CommitLogSegmentAllocatorCDC implements CommitLogSegmentAllocator
{
    static final Logger logger = LoggerFactory.getLogger(CommitLogSegmentAllocatorCDC.class);
    private final CDCSizeTracker cdcSizeTracker;
    private final CommitLogSegmentManager segmentManager;

    CommitLogSegmentAllocatorCDC(CommitLogSegmentManager segmentManager)
    {
        this.segmentManager = segmentManager;
        cdcSizeTracker = new CDCSizeTracker(segmentManager, new File(DatabaseDescriptor.getCDCLogLocation()));
    }

    public void start()
    {
        cdcSizeTracker.start();
    }

    public void discard(CommitLogSegment segment, boolean delete)
    {
        segment.close();
        segmentManager.addSize(-segment.onDiskSize());

        cdcSizeTracker.processDiscardedSegment(segment);

        if (delete)
            FileUtils.deleteWithConfirm(segment.logFile);

        if (segment.getCDCState() != CDCState.CONTAINS)
        {
            // Always delete hard-link from cdc folder if this segment didn't contain CDC data. Note: File may not exist
            // if processing discard during startup.
            File cdcLink = segment.getCDCFile();
            if (cdcLink.exists())
                FileUtils.deleteWithConfirm(cdcLink);

            File cdcIndexFile = segment.getCDCIndexFile();
            if (cdcIndexFile.exists())
                FileUtils.deleteWithConfirm(cdcIndexFile);
        }
    }

    /**
     * Stops the thread pool for CDC on disk size tracking.
     */
    public void shutdown()
    {
        cdcSizeTracker.shutdown();
    }

    /**
     * Reserve space in the current segment for the provided mutation or, if there isn't space available,
     * create a new segment. For CDC mutations, allocation is expected to throw WTE if the segment disallows CDC mutations.
     *
     * @param mutation Mutation to allocate in segment manager
     * @param size total size (overhead + serialized) of mutation
     * @return the created Allocation object
     * @throws CDCWriteException If segment disallows CDC mutations, we throw
     */
    public CommitLogSegment.Allocation allocate(Mutation mutation, int size) throws CDCWriteException
    {
        CommitLogSegment segment = segmentManager.getActiveSegment();
        throwIfForbidden(mutation, segment);

        CommitLogSegment.Allocation alloc = segment.allocate(mutation, size);
        // If we failed to allocate in the segment, prompt for a switch to a new segment and loop on re-attempt. This
        // is expected to succeed or throw, since CommitLog allocation working is central to how a node operates.
        while (alloc == null)
        {
            // Failed to allocate, so move to a new segment with enough room if possible.
            segmentManager.switchToNewSegment(segment);
            segment = segmentManager.getActiveSegment();

            // New segment, so confirm whether or not CDC mutations are allowed on this.
            throwIfForbidden(mutation, segment);
            alloc = segment.allocate(mutation, size);
        }

        if (mutation.trackedByCDC())
            segment.setCDCState(CDCState.CONTAINS);

        return alloc;
    }

    private void throwIfForbidden(Mutation mutation, CommitLogSegment segment) throws CDCWriteException
    {
        if (mutation.trackedByCDC() && segment.getCDCState() == CDCState.FORBIDDEN)
        {
            cdcSizeTracker.submitOverflowSizeRecalculation();
            String logMsg = String.format("Rejecting mutation to keyspace %s. Free up space in %s by processing CDC logs.",
                mutation.getKeyspaceName(), DatabaseDescriptor.getCDCLogLocation());
            NoSpamLogger.log(logger,
                             NoSpamLogger.Level.WARN,
                             10,
                             TimeUnit.SECONDS,
                             logMsg);
            throw new CDCWriteException(logMsg);
        }
    }

    /**
     * On segment creation, flag whether the segment should accept CDC mutations or not based on the total currently
     * allocated unflushed CDC segments and the contents of cdc_raw
     */
    public CommitLogSegment createSegment()
    {
        CommitLogSegment segment = CommitLogSegment.createSegment(segmentManager.commitLog, segmentManager);

        // Hard link file in cdc folder for realtime tracking
        FileUtils.createHardLink(segment.logFile, segment.getCDCFile());

        cdcSizeTracker.processNewSegment(segment);
        return segment;
    }

    /**
     * Delete untracked segment files after replay
     *
     * @param file segment file that is no longer in use.
     */
    public void handleReplayedSegment(final File file)
    {
        // delete untracked cdc segment hard link files if their index files do not exist
        File cdcFile = new File(DatabaseDescriptor.getCDCLogLocation(), file.getName());
        File cdcIndexFile = new File(DatabaseDescriptor.getCDCLogLocation(), CommitLogDescriptor.fromFileName(file.getName()).cdcIndexFileName());
        if (cdcFile.exists() && !cdcIndexFile.exists())
        {
            logger.trace("(Unopened) CDC segment {} is no longer needed and will be deleted now", cdcFile);
            FileUtils.deleteWithConfirm(cdcFile);
        }
    }

    /**
     * For use after replay when replayer hard-links / adds tracking of replayed segments
     */
    void addCDCSize(long size)
    {
        cdcSizeTracker.addSize(size);
    }

    /**
     * Tracks total disk usage of CDC subsystem, defined by the summation of all unflushed CommitLogSegments with CDC
     * data in them and all segments archived into cdc_raw.
     *
     * Allows atomic increment/decrement of unflushed size, however only allows increment on flushed and requires a full
     * directory walk to determine any potential deletions by an external CDC consumer.
     */
    private static class CDCSizeTracker extends DirectorySizeCalculator
    {
        private final RateLimiter rateLimiter = RateLimiter.create(1000.0 / DatabaseDescriptor.getCDCDiskCheckInterval());
        private ExecutorService cdcSizeCalculationExecutor;
        private final CommitLogSegmentManager segmentManager;

        /** Used only in context of file tree walking thread; not read nor mutated outside this context */
        private volatile long sizeInProgress = 0;

        CDCSizeTracker(CommitLogSegmentManager segmentManager, File path)
        {
            super(path);
            this.segmentManager = segmentManager;
        }

        /**
         * Needed for stop/restart during unit tests
         */
        public void start()
        {
            size = 0;
            cdcSizeCalculationExecutor = new ThreadPoolExecutor(1, 1, 1000, TimeUnit.SECONDS, new SynchronousQueue<>(), new ThreadPoolExecutor.DiscardPolicy());
        }

        /**
         * Synchronous size recalculation on each segment creation/deletion call could lead to very long delays in new
         * segment allocation, thus long delays in thread signaling to wake waiting allocation / writer threads.
         *
         * This can be reached either from the segment management thread in CommitLogSegmentManager or from the
         * size recalculation executor, so we synchronize on this object to reduce the race overlap window available for
         * size to drift.
         *
         * Reference DirectorySizerBench for more information about performance of the directory size recalc.
         */
        void processNewSegment(CommitLogSegment segment)
        {
            // See synchronization in CommitLogSegment.setCDCState
            synchronized(segment.cdcStateLock)
            {
                segment.setCDCState(defaultSegmentSize() + totalCDCSizeOnDisk() > allowableCDCBytes()
                                    ? CDCState.FORBIDDEN
                                    : CDCState.PERMITTED);
                if (segment.getCDCState() == CDCState.PERMITTED)
                    size += defaultSegmentSize();
            }

            // Take this opportunity to kick off a recalc to pick up any consumer file deletion.
            submitOverflowSizeRecalculation();
        }

        /**
         * Upon segment discard, we need to adjust our known CDC consumption on disk based on whether or not this segment
         * was flagged to be allowable for CDC.
         */
        void processDiscardedSegment(CommitLogSegment segment)
        {
            // See synchronization in CommitLogSegment.setCDCState
            synchronized(segment.cdcStateLock)
            {
                // Add to flushed size before decrementing unflushed so we don't have a window of false generosity
                if (segment.getCDCState() == CDCState.CONTAINS)
                    size += segment.onDiskSize();
                if (segment.getCDCState() != CDCState.FORBIDDEN)
                    size -= defaultSegmentSize();
            }

            // Take this opportunity to kick off a recalc to pick up any consumer file deletion.
            submitOverflowSizeRecalculation();
        }

        private long allowableCDCBytes()
        {
            return (long)DatabaseDescriptor.getCDCSpaceInMB() * 1024 * 1024;
        }

        /**
         * The overflow size calculation requires walking the flie tree and checking file size for all linked CDC
         * files. As such, we do this async on the executor in the CDCSizeTracker instead of the context of the calling
         * thread. While this can obviously introduce some delay / raciness in the calculation of CDC size consumed,
         * the alternative of significantly long blocks for critical path CL allocation is unacceptable.
         */
        void submitOverflowSizeRecalculation()
        {
            try
            {
                cdcSizeCalculationExecutor.submit(() -> recalculateOverflowSize());
            }
            catch (RejectedExecutionException e)
            {
                // Do nothing. Means we have one in flight so this req. should be satisfied when it completes.
            }
        }

        private void recalculateOverflowSize()
        {
            rateLimiter.acquire();
            calculateSize();
            CommitLogSegment activeCommitLogSegment = segmentManager.getActiveSegment();
            // In the event that the current segment is disallowed for CDC, re-check it as our size on disk may have
            // reduced, thus allowing the segment to accept CDC writes. It's worth noting: this would spin on recalc
            // endlessly if not for the rate limiter dropping looping calls on the floor.
            if (activeCommitLogSegment.getCDCState() == CDCState.FORBIDDEN)
                processNewSegment(activeCommitLogSegment);
        }

        private int defaultSegmentSize()
        {
            return DatabaseDescriptor.getCommitLogSegmentSize();
        }

        private void calculateSize()
        {
            try
            {
                // The Arrays.stream approach is considerably slower
                sizeInProgress = 0;
                Files.walkFileTree(path.toPath(), this);
                size = sizeInProgress;
            }
            catch (IOException ie)
            {
                CommitLog.handleCommitError("Failed CDC Size Calculation", ie);
            }
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException
        {
            sizeInProgress += attrs.size();
            return FileVisitResult.CONTINUE;
        }


        public void shutdown()
        {
            cdcSizeCalculationExecutor.shutdown();
        }

        private void addSize(long toAdd)
        {
            size += toAdd;
        }

        private long totalCDCSizeOnDisk()
        {
            return size;
        }
    }

    /**
     * Only use for testing / validation that size tracker is working. Not for production use.
     */
    @VisibleForTesting
    long updateCDCTotalSize()
    {
        cdcSizeTracker.submitOverflowSizeRecalculation();

        // Give the update time to run
        try
        {
            Thread.sleep(DatabaseDescriptor.getCDCDiskCheckInterval() + 10);
        }
        catch (InterruptedException e) {
            // Expected in test context. no-op.
        }

        return cdcSizeTracker.totalCDCSizeOnDisk();
    }
}
