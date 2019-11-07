/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.internal.streaming.bytes;

import static java.lang.Long.MAX_VALUE;
import static java.lang.String.format;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.mule.runtime.api.i18n.I18nMessageFactory.createStaticMessage;
import static org.mule.runtime.core.internal.util.ConcurrencyUtils.withLock;

import org.mule.runtime.core.api.util.func.CheckedRunnable;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import stormpot.Allocator;
import stormpot.BlazePool;
import stormpot.Config;
import stormpot.PoolException;
import stormpot.Slot;
import stormpot.Timeout;

public class ByteBufferPool {

  private static final Timeout WAIT_FOREVER = new Timeout(MAX_VALUE, MILLISECONDS);

  private final BlazePool<PooledByteBuffer> pool;
  private final Lock lock = new ReentrantLock();
  private final Condition poolNotFull = lock.newCondition();
  private final AtomicLong streamingMemory = new AtomicLong(0);

  private final int bufferCapacity;
  private final long maxStreamingMemory;
  private final long waitTimeoutMillis;

  public ByteBufferPool(int bufferCapacity, long maxStreamingMemory, long waitTimeoutMillis) {
    this.bufferCapacity = bufferCapacity;
    this.maxStreamingMemory = maxStreamingMemory;
    this.waitTimeoutMillis = waitTimeoutMillis;

    pool = new BlazePool<>(new Config<>()
                               .setAllocator(new ByteBufferAllocator())
                               .setMetricsRecorder(null)
                               .setPreciseLeakDetectionEnabled(false));
  }

  public PooledByteBuffer take() throws Exception {
    PooledByteBuffer buffer = null;
    do {
      try {
        buffer = pool.claim(WAIT_FOREVER);
      } catch (PoolException e) {
        if (e.getCause() instanceof MaxStreamingMemoryExceededException) {
          signal(() -> {
            while (streamingMemory.get() >= maxStreamingMemory) {
              if (!poolNotFull.await(waitTimeoutMillis, MILLISECONDS)) {
                throw (MaxStreamingMemoryExceededException) e.getCause();
              }
            }
          });
        }
      }
    } while (buffer == null);

    return buffer;
  }

  public void returnBuffer(PooledByteBuffer buffer) {
    buffer.release();
    signalPoolNotFull();
  }

  private void signalPoolNotFull() {
    signal(poolNotFull::signal);
  }

  public void close() {
    streamingMemory.addAndGet(-bufferCapacity * pool.getAllocationCount());
    try {
      pool.shutdown();
    } finally {
      signal(poolNotFull::signalAll);
    }
  }

  private void signal(CheckedRunnable task) {
    withLock(lock, task);
  }


  private class ByteBufferAllocator implements Allocator<PooledByteBuffer> {

    @Override
    public PooledByteBuffer allocate(Slot slot) throws Exception {
      if (streamingMemory.addAndGet(bufferCapacity) <= maxStreamingMemory) {
        return new PooledByteBuffer(slot, ByteBuffer.allocate(bufferCapacity));
      }

      streamingMemory.addAndGet(-bufferCapacity);
      throw new MaxStreamingMemoryExceededException(createStaticMessage(format(
          "Max streaming memory limit of %d bytes was exceeded",
          maxStreamingMemory)));
    }

    @Override
    public void deallocate(PooledByteBuffer poolable) throws Exception {
      if (streamingMemory.addAndGet(-bufferCapacity) < maxStreamingMemory) {
        signalPoolNotFull();
      }
    }
  }
}
