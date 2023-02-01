/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2022 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.network;

import io.questdb.std.LongIntHashMap;
import io.questdb.std.MemoryTag;
import io.questdb.std.Unsafe;
import io.questdb.std.Vect;
import org.jetbrains.annotations.Nullable;

public class IODispatcherWindows<C extends IOContext> extends AbstractIODispatcher<C> {
    private final LongIntHashMap fds = new LongIntHashMap();
    private final FDSet readFdSet;
    private final SelectFacade sf;
    private final FDSet writeFdSet;
    private boolean listenerRegistered;
    private int readFdCount;
    private int writeFdCount;

    public IODispatcherWindows(
            IODispatcherConfiguration configuration,
            IOContextFactory<C> ioContextFactory
    ) {
        super(configuration, ioContextFactory);
        this.sf = configuration.getSelectFacade();
        this.readFdSet = new FDSet(configuration.getEventCapacity());
        this.writeFdSet = new FDSet(configuration.getEventCapacity());
        readFdSet.add(serverFd);
        readFdSet.setCount(1);
        writeFdSet.setCount(0);
        listenerRegistered = true;
    }

    @Override
    public void close() {
        super.close();
        readFdSet.close();
        writeFdSet.close();
        LOG.info().$("closed").$();
    }

    private boolean processRegistrations(long timestamp) {
        long cursor;
        boolean useful = false;
        while ((cursor = interestSubSeq.next()) > -1) {
            IOEvent<C> evt = interestQueue.get(cursor);
            C context = evt.context;
            int operation = evt.operation;
            interestSubSeq.done(cursor);
            useful = true;

            final SuspendEvent suspendEvent = context.getSuspendEvent();
            if (suspendEvent != null) {
                // if the operation was suspended, we request a read to be able to detect a client disconnect
                operation = IOOperation.READ;
            }
            int r = pending.addRow();
            pending.set(r, OPM_TIMESTAMP, timestamp);
            int fd = context.getFd();
            pending.set(r, OPM_FD, fd);
            pending.set(r, OPM_OPERATION, operation);
            pending.set(r, context);

            if (IOOperation.isRead(operation)) {
                readFdSet.add(fd);
                readFdCount++;
            } else {
                writeFdSet.add(fd);
                writeFdCount++;
            }

        }
        return useful;
    }

    private void queryFdSets(long timestamp) {
        // collect reads into hash map
        for (int i = 0, n = readFdSet.getCount(); i < n; i++) {
            final long fd = readFdSet.get(i);
            if (fd == serverFd) {
                accept(timestamp);
            } else {
                fds.put(fd, SelectAccessor.FD_READ);
            }
        }

        // collect writes into hash map
        for (int i = 0, n = writeFdSet.getCount(); i < n; i++) {
            final long fd = writeFdSet.get(i);
            final int index = fds.keyIndex(fd);
            if (fds.valueAt(index) == -1) {
                fds.putAt(index, fd, SelectAccessor.FD_WRITE);
            } else {
                fds.putAt(index, fd, SelectAccessor.FD_READ | SelectAccessor.FD_WRITE);
            }
        }
    }

    @Override
    protected void pendingAdded(int index) {
        pending.set(index, OPM_OPERATION, initialBias == IODispatcherConfiguration.BIAS_READ ? IOOperation.READ : IOOperation.WRITE);
    }

    @Override
    protected void registerListenerFd() {
        listenerRegistered = true;
    }

    @Override
    protected boolean runSerially() {
        final long timestamp = clock.getTicks();
        processDisconnects(timestamp);

        int count;
        if (readFdSet.getCount() > 0 || writeFdSet.getCount() > 0) {
            count = sf.select(readFdSet.address, writeFdSet.address, 0);
            if (count < 0) {
                LOG.error().$("select failure [err=").$(nf.errno()).I$();
                return false;
            }
        } else {
            count = 0;
        }

        boolean useful = false;
        fds.clear();

        int watermark = pending.size();
        // collect reads into hash map
        if (count > 0) {
            queryFdSets(timestamp);
            useful = true;
        }

        // re-arm select() fds
        readFdCount = 0;
        writeFdCount = 0;
        readFdSet.reset();
        writeFdSet.reset();
        for (int i = 0, n = pending.size(); i < n; ) {
            final C context = pending.get(i);

            final int fd = (int) pending.get(i, OPM_FD);
            final int newOp = fds.get(fd);
            assert fd != serverFd;

            if (newOp != -1) {
                // select()'ed operation case

                // check if the context is waiting for a suspend event
                final SuspendEvent suspendEvent = getSuspendEvent(timestamp, context);
                if (suspendEvent != null) {
                    // the event is still pending, check if we have a client disconnect
                    if (testConnection(context.getFd())) {
                        doDisconnect(context, DISCONNECT_SRC_PEER_DISCONNECT);
                        pending.deleteRow(i);
                        n--;
                        watermark--;
                    } else {
                        i++; // just skip to the next operation
                    }
                    continue;
                }

                // publish event and remove from pending
                boolean timeout = context.isTimeout(timestamp);
                int op = 0;
                if ((newOp & SelectAccessor.FD_READ) > 0) {
                    op |= IOOperation.READ;
                    if (timeout) {
                        op |= IOOperation.TIMEOUT;
                        timeout = false;
                    }
                    publishOperation(op, context);
                }
                if ((newOp & SelectAccessor.FD_WRITE) > 0) {
                    op |= IOOperation.WRITE;
                    if (timeout) {
                        op |= IOOperation.TIMEOUT;
                    }
                    publishOperation(op, context);
                }

                pending.deleteRow(i);
                useful = true;
                n--;
                watermark--;
            }
        }

        // process rows over watermark (new connections)
        if (watermark < pending.size()) {
            enqueuePending(watermark, timestamp);
        }

        // process timed out connections
        final long deadline = timestamp - idleConnectionTimeout;
        if (pending.size() > 0 && pending.get(0, OPM_TIMESTAMP) < deadline) {
            watermark -= processIdleConnections(deadline);
            useful = true;
        }

        // process timers
        for (int row = watermark - 1; row >= 0; --row) {
            final C context = pending.get(row);
            if (context.isTimeout(timestamp)) {
                publishOperation(IOOperation.TIMEOUT, context);
                pending.deleteRow(row);
                useful = true;
            }
        }

        // process returned fds
        useful |= processRegistrations(timestamp);

        if (listenerRegistered) {
            assert serverFd >= 0;
            readFdSet.add(serverFd);
            readFdCount++;
        }

        readFdSet.setCount(readFdCount);
        writeFdSet.setCount(writeFdCount);
        return useful;
    }

    @Nullable
    private SuspendEvent getSuspendEvent(long timestamp, C context) {
        // check if the context is waiting for a suspend event
        final SuspendEvent suspendEvent = context.getSuspendEvent();
        if (suspendEvent != null) {
            if (suspendEvent.checkTriggered() || suspendEvent.isDeadlineMet(timestamp)) {
                // the event has been triggered or expired already, clear it and proceed
                context.clearSuspendEvent();
            }
        }
        return suspendEvent;
    }

    private void enqueuePending(int watermark, long timestamp) {
        for (int i = watermark, sz = pending.size(); i < sz; i++) {
            final C context = pending.get(i);
            final SuspendEvent suspendEvent = getSuspendEvent(timestamp, context);

            int operation = (int) pending.get(i, OPM_OPERATION);
            final int fd = (int) pending.get(i, OPM_FD);
            if (suspendEvent != null) {
                // if the operation was suspended, we request a read to be able to detect a client disconnect
                operation = IOOperation.READ;
            }

            if (IOOperation.isRead(operation)) {
                readFdSet.add(fd);
                readFdCount++;
            } else {
                writeFdSet.add(fd);
                writeFdCount++;
            }
        }
    }
    
    private int processIdleConnections(long deadline) {
        int count = 0;
        for (int i = 0, n = pending.size(); i < n && pending.get(i, OPM_TIMESTAMP) < deadline; i++, count++) {
            doDisconnect(pending.get(i), DISCONNECT_SRC_PEER_DISCONNECT);
        }
        pending.zapTop(count);
        return count;
    }
    
    @Override
    protected void unregisterListenerFd() {
        listenerRegistered = false;
    }

    private static class FDSet {
        private long _wptr;
        private long address;
        private long lim;
        private int size;

        private FDSet(int size) {
            int l = SelectAccessor.ARRAY_OFFSET + 8 * size;
            this.address = Unsafe.malloc(l, MemoryTag.NATIVE_IO_DISPATCHER_RSS);
            this.size = size;
            this._wptr = address + SelectAccessor.ARRAY_OFFSET;
            this.lim = address + l;
        }

        private void add(int fd) {
            if (_wptr == lim) {
                resize();
            }
            long p = _wptr;
            Unsafe.getUnsafe().putLong(p, fd);
            _wptr = p + 8;
        }

        private void close() {
            if (address != 0) {
                address = Unsafe.free(address, lim - address, MemoryTag.NATIVE_IO_DISPATCHER_RSS);
            }
        }

        private long get(int index) {
            return Unsafe.getUnsafe().getLong(address + SelectAccessor.ARRAY_OFFSET + index * 8L);
        }

        private int getCount() {
            return Unsafe.getUnsafe().getInt(address + SelectAccessor.COUNT_OFFSET);
        }

        private void reset() {
            _wptr = address + SelectAccessor.ARRAY_OFFSET;
        }

        private void resize() {
            int sz = size * 2;
            int l = SelectAccessor.ARRAY_OFFSET + 8 * sz;
            long _addr = Unsafe.malloc(l, MemoryTag.NATIVE_IO_DISPATCHER_RSS);
            Vect.memcpy(_addr, address, lim - address);
            Unsafe.free(address, lim - address, MemoryTag.NATIVE_IO_DISPATCHER_RSS);
            lim = _addr + l;
            size = sz;
            _wptr = _addr + (_wptr - address);
            address = _addr;
        }

        private void setCount(int count) {
            Unsafe.getUnsafe().putInt(address + SelectAccessor.COUNT_OFFSET, count);
        }
    }
}

