package org.tikv.cdc;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tikv.cdc.RegionCDCClient.RegionCDCClientBuilder;
import org.tikv.common.TiConfiguration;
import org.tikv.common.region.TiRegion;
import org.tikv.kvproto.Cdcpb.Event;
import org.tikv.kvproto.Cdcpb.Event.Row;
import org.tikv.kvproto.Kvrpcpb.IsolationLevel;
import shade.io.grpc.stub.StreamObserver;

public class CDCClient implements AutoCloseable, StreamObserver<Event> {
    private final Logger logger = LoggerFactory.getLogger(CDCClient.class);

    private final boolean started = false;
    private final ResolvedTsManager tsManager;
    private final List<RegionCDCClient> regionClients;
    private final BlockingQueue<Event> eventsBuffer;
    private Iterator<Row> currentIter = null;

    public CDCClient(final TiConfiguration conf, final RegionCDCClientBuilder clientBuilder,
            final List<TiRegion> regions, final long startTs) {
        assert(conf.getIsolationLevel().equals(IsolationLevel.SI)); // only support SI for now
        this.tsManager = new ResolvedTsManager(regions);
        this.regionClients = regions.stream()
            .map(region -> clientBuilder.build(startTs, region, this))
            .collect(Collectors.toList());

        // TODO: Add a dedicated config to TiConfiguration for queue size.
        this.eventsBuffer = new ArrayBlockingQueue<>(conf.getTableScanConcurrency() * conf.getScanBatchSize());
    }

    synchronized public void start() {
        if (!started) {
            for (final RegionCDCClient client: regionClients) {
                client.start();
            }
        }
    }

    synchronized public Row get() throws InterruptedException {
        if (currentIter != null && currentIter.hasNext()) {
            return currentIter.next();
        }

        final Event event = eventsBuffer.poll(1, TimeUnit.SECONDS);
        switch (event.getEventCase()) {
            case ENTRIES:
                currentIter = event.getEntries().getEntriesList().iterator();
                // fallthrough, let caller retry
            case RESOLVED_TS:
                tsManager.update(event.getRegionId(), event.getResolvedTs());
                // fallthrough, let caller retry
            default:
                return null;
        }
    }

    synchronized public void close() {
        for (final RegionCDCClient client : regionClients) {
            try {
                client.close();
            } catch (final Throwable e) {
                logger.error(String.format("failed to close region client %d", client.getRegion().getId()), e);
            }
        }
    }

    synchronized public long getResolvedTs() {
        return tsManager.getMinResolvedTs();
    }

    synchronized public boolean isStarted() {
        return started;
    }

    @Override
    public void onCompleted() {
        // should never trigger for CDC request
    }

    @Override
    public void onError(final Throwable err) {
        logger.error("error:", err);
    }

    @Override
    public void onNext(final Event event) {
        eventsBuffer.offer(event);
    }
}
