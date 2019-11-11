/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.internal.streaming.bytes;

import static java.lang.Math.round;
import static java.lang.String.format;
import static java.lang.System.getProperty;
import static org.mule.runtime.api.i18n.I18nMessageFactory.createStaticMessage;
import static org.mule.runtime.core.api.config.MuleProperties.MULE_STREAMING_MAX_MEMORY;
import static org.mule.runtime.core.internal.util.ConcurrencyUtils.withLock;
import static org.slf4j.LoggerFactory.getLogger;

import org.mule.runtime.core.api.streaming.bytes.ByteBufferManager;
import org.mule.runtime.core.api.util.func.CheckedRunnable;
import org.mule.runtime.core.internal.streaming.MemoryManager;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;

/**
 * Simple implementation of {@link ByteBufferManager}
 *
 * @since 4.0
 */
public class SimpleByteBufferManager implements ByteBufferManager {

  private static final Logger LOGGER = getLogger(PoolingByteBufferManager.class);
  static final double MAX_STREAMING_PERCENTILE = 0.7;

  private final AtomicLong streamingMemory = new AtomicLong(0);
  private final long maxStreamingMemory;
  private final Lock lock = new ReentrantLock();
  private final Condition poolNotFull = lock.newCondition();

  public SimpleByteBufferManager(MemoryManager memoryManager) {
    maxStreamingMemory = calculateMaxStreamingMemory(memoryManager);
  }

  private long calculateMaxStreamingMemory(MemoryManager memoryManager) {
    String maxMemoryProperty = getProperty(MULE_STREAMING_MAX_MEMORY);
    if (maxMemoryProperty == null) {
      return round(memoryManager.getMaxMemory() * MAX_STREAMING_PERCENTILE);
    } else {
      try {
        return Long.valueOf(maxMemoryProperty);
      } catch (Exception e) {
        throw new IllegalArgumentException(format("Invalid value for system property '%s'. A memory size (in bytes) was "
                                                      + "expected, got '%s' instead",
                                                  MULE_STREAMING_MAX_MEMORY, maxMemoryProperty));
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ByteBuffer allocate(int capacity) {
    if (streamingMemory.addAndGet(capacity) <= maxStreamingMemory) {
      return ByteBuffer.allocate(capacity);
    }

    streamingMemory.addAndGet(-capacity);
    throw new MaxStreamingMemoryExceededException(createStaticMessage(format(
        "Max streaming memory limit of %d bytes was exceeded",
        maxStreamingMemory)));
  }

  /**
   * No - Op operation
   * {@inheritDoc}
   */
  @Override
  public void deallocate(ByteBuffer byteBuffer) {
    if (streamingMemory.addAndGet(-byteBuffer.capacity()) < maxStreamingMemory) {
      signalPoolNotFull();
    }
  }

  private void signalPoolNotFull() {
    signal(poolNotFull::signal);
  }

  private void signal(CheckedRunnable task) {
    withLock(lock, task);
  }
}
