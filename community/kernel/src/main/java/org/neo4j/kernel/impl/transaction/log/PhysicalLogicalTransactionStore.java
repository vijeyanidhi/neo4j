/*
 * Copyright (c) 2002-2015 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
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
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.transaction.log;

import java.io.FileNotFoundException;
import java.io.IOException;

import org.neo4j.kernel.KernelHealth;
import org.neo4j.kernel.impl.transaction.CommittedTransactionRepresentation;
import org.neo4j.kernel.impl.transaction.log.TransactionMetadataCache.TransactionMetadata;
import org.neo4j.kernel.impl.transaction.log.entry.LogEntry;
import org.neo4j.kernel.impl.transaction.log.entry.LogEntryCommit;
import org.neo4j.kernel.impl.transaction.log.entry.LogEntryReader;
import org.neo4j.kernel.impl.transaction.log.entry.LogEntryReaderFactory;
import org.neo4j.kernel.impl.transaction.log.entry.LogEntryStart;
import org.neo4j.kernel.impl.util.IdOrderingQueue;
import org.neo4j.kernel.lifecycle.LifecycleAdapter;

import static org.neo4j.kernel.impl.transaction.log.TransactionIdStore.BASE_TX_ID;
import static org.neo4j.kernel.impl.transaction.log.entry.LogEntryByteCodes.TX_1P_COMMIT;
import static org.neo4j.kernel.impl.transaction.log.entry.LogEntryByteCodes.TX_START;
import static org.neo4j.kernel.impl.transaction.log.entry.LogHeader.LOG_HEADER_SIZE;

public class PhysicalLogicalTransactionStore extends LifecycleAdapter implements LogicalTransactionStore
{
    private final LogFile logFile;
    private final LogRotation logRotation;
    private final TransactionMetadataCache transactionMetadataCache;
    private final TransactionIdStore transactionIdStore;
    private final IdOrderingQueue legacyIndexTransactionOrdering;
    private final KernelHealth kernelHealth;
    private TransactionAppender appender;

    public PhysicalLogicalTransactionStore(
            LogFile logFile,
            LogRotation logRotation,
            TransactionMetadataCache transactionMetadataCache,
            TransactionIdStore transactionIdStore,
            IdOrderingQueue legacyIndexTransactionOrdering,
            KernelHealth kernelHealth )
    {
        this.logFile = logFile;
        this.logRotation = logRotation;
        this.transactionMetadataCache = transactionMetadataCache;
        this.transactionIdStore = transactionIdStore;
        this.legacyIndexTransactionOrdering = legacyIndexTransactionOrdering;
        this.kernelHealth = kernelHealth;
    }

    @Override
    public void start() throws Throwable
    {
        // We can't open the appender until 'start()' because the LogFile needs recovery to have completed first.
        this.appender = new BatchingTransactionAppender( logFile, logRotation, transactionMetadataCache,
                transactionIdStore, legacyIndexTransactionOrdering, kernelHealth );
    }

    @Override
    public TransactionAppender getAppender()
    {
        return appender;
    }

    @Override
    public IOCursor<CommittedTransactionRepresentation> getTransactions( final long transactionIdToStartFrom )
            throws IOException
    {
        // look up in position cache
        try
        {
            TransactionMetadataCache.TransactionMetadata transactionMetadata =
                    transactionMetadataCache.getTransactionMetadata( transactionIdToStartFrom );
            LogEntryReader<ReadableVersionableLogChannel> logEntryReader = new LogEntryReaderFactory().versionable();
            if ( transactionMetadata != null )
            {
                // we're good
                ReadableVersionableLogChannel channel = logFile.getReader( transactionMetadata.getStartPosition() );
                return new PhysicalTransactionCursor<>( channel, logEntryReader );
            }

            // ask LogFile about the version it may be in
            LogVersionLocator headerVisitor = new LogVersionLocator( transactionIdToStartFrom );
            logFile.accept( headerVisitor );

            // ask LogFile
            TransactionPositionLocator transactionPositionLocator =
                    new TransactionPositionLocator( transactionIdToStartFrom, logEntryReader );
            logFile.accept( transactionPositionLocator, headerVisitor.getLogPosition() );
            LogPosition position = transactionPositionLocator.getAndCacheFoundLogPosition( transactionMetadataCache );
            return new PhysicalTransactionCursor<>( logFile.getReader( position ), logEntryReader );
        }
        catch ( FileNotFoundException e )
        {
            throw new NoSuchTransactionException( transactionIdToStartFrom,
                    "Log position acquired, but couldn't find the log file itself. Perhaps it just recently was deleted? [" + e.getMessage() + "]" );
        }
    }

    private static final TransactionMetadataCache.TransactionMetadata METADATA_FOR_EMPTY_STORE =
            new TransactionMetadataCache.TransactionMetadata( -1, -1, new LogPosition( 0, LOG_HEADER_SIZE ),
                    TransactionIdStore.BASE_TX_CHECKSUM );

    @Override
    public TransactionMetadata getMetadataFor( long transactionId ) throws IOException
    {
        if ( transactionId <= BASE_TX_ID )
        {
            return METADATA_FOR_EMPTY_STORE;
        }

        TransactionMetadata transactionMetadata =
                transactionMetadataCache.getTransactionMetadata( transactionId );
        if ( transactionMetadata == null )
        {
            try ( IOCursor<CommittedTransactionRepresentation> cursor = getTransactions( transactionId ) )
            {
                while ( cursor.next() )
                {
                    CommittedTransactionRepresentation tx = cursor.get();
                    long committedTxId = tx.getCommitEntry().getTxId();
                    TransactionMetadata metadata = transactionMetadataCache.cacheTransactionMetadata( committedTxId,
                            tx.getStartEntry().getStartPosition(), tx.getStartEntry().getMasterId(),
                            tx.getStartEntry().getLocalId(), LogEntryStart.checksum( tx.getStartEntry() ) );
                    if ( committedTxId == transactionId )
                    {
                        transactionMetadata = metadata;
                    }
                }
            }
            if ( transactionMetadata == null )
            {
                throw new NoSuchTransactionException( transactionId );
            }
        }

        return transactionMetadata;
    }

    public static class TransactionPositionLocator implements LogFile.LogFileVisitor
    {
        private final long startTransactionId;
        private final LogEntryReader<ReadableVersionableLogChannel> logEntryReader;
        private LogEntryStart startEntryForFoundTransaction;

        public TransactionPositionLocator( long startTransactionId,
                                           LogEntryReader<ReadableVersionableLogChannel> logEntryReader )
        {
            this.startTransactionId = startTransactionId;
            this.logEntryReader = logEntryReader;
        }

        @Override
        public boolean visit( LogPosition position, ReadableVersionableLogChannel channel ) throws IOException
        {
            LogEntry logEntry;
            LogEntryStart startEntry = null;
            while ( (logEntry = logEntryReader.readLogEntry( channel ) ) != null )
            {
                switch ( logEntry.getType() )
                {
                    case TX_START:
                        startEntry = logEntry.as();
                        break;
                    case TX_1P_COMMIT:
                        LogEntryCommit commit = logEntry.as();
                        if ( commit.getTxId() == startTransactionId )
                        {
                            startEntryForFoundTransaction = startEntry;
                            return false;
                        }
                    default: // just skip commands
                        break;
                }
            }
            return true;
        }

        public LogPosition getAndCacheFoundLogPosition( TransactionMetadataCache transactionMetadataCache )
                throws NoSuchTransactionException
        {
            if ( startEntryForFoundTransaction == null )
            {
                throw new NoSuchTransactionException( startTransactionId );
            }
            transactionMetadataCache.cacheTransactionMetadata(
                    startTransactionId,
                    startEntryForFoundTransaction.getStartPosition(),
                    startEntryForFoundTransaction.getMasterId(),
                    startEntryForFoundTransaction.getLocalId(),
                    LogEntryStart.checksum( startEntryForFoundTransaction )
            );
            return startEntryForFoundTransaction.getStartPosition();
        }
    }

    public static final class LogVersionLocator implements LogHeaderVisitor
    {
        private final long transactionId;
        private LogPosition foundPosition;

        public LogVersionLocator( long transactionId )
        {
            this.transactionId = transactionId;
        }

        @Override
        public boolean visit( LogPosition position, long firstTransactionIdInLog, long lastTransactionIdInLog )
        {
            boolean foundIt = transactionId >= firstTransactionIdInLog &&
                    transactionId <= lastTransactionIdInLog;
            if ( foundIt )
            {
                foundPosition = position;
            }
            return !foundIt; // continue as long we don't find it
        }

        public LogPosition getLogPosition() throws NoSuchTransactionException
        {
            if ( foundPosition == null )
            {
                throw new NoSuchTransactionException( transactionId,
                        "Couldn't find any log containing " + transactionId );
            }
            return foundPosition;
        }
    }
}
