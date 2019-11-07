/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.internal.streaming.bytes;

import static java.lang.Math.min;
import static java.lang.Math.toIntExact;
import static java.lang.System.arraycopy;
import static java.nio.ByteBuffer.wrap;
import static org.mule.runtime.api.i18n.I18nMessageFactory.createStaticMessage;
import org.mule.runtime.api.exception.MuleRuntimeException;
import org.mule.runtime.api.streaming.exception.StreamingBufferSizeExceededException;
import org.mule.runtime.core.api.streaming.bytes.ByteBufferManager;
import org.mule.runtime.core.api.streaming.bytes.InMemoryCursorStreamConfig;
import org.mule.runtime.core.api.streaming.bytes.ManagedByteBuffer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * An implementation of {@link AbstractInputStreamBuffer} which holds the buffered
 * information in memory.
 * <p>
 * If the buffer does not have enough capacity to hold all the data, then it will
 * expanded up to a certain threshold configured in the constructor. Once that threshold
 * is reached, a {@link StreamingBufferSizeExceededException} will be thrown. If no threshold
 * is provided, then the buffer will be allowed to grow indefinitely.
 *
 * @since 4.0
 */
public class InMemoryStreamBuffer extends AbstractInputStreamBuffer {

  private static final int STREAM_FINISHED_PROBE = 10;

  private ManagedByteBuffer managedByteBuffer;
  private ByteBuffer buffer;
  private final int bufferSizeIncrement;
  private final int maxBufferSize;
  private long bufferTip = 0;
  private boolean streamFullyConsumed = false;

  /**
   * Creates a new instance
   *
   * @param stream the stream to be buffered
   * @param config this buffer's configuration.
   */
  public InMemoryStreamBuffer(InputStream stream, InMemoryCursorStreamConfig config, ByteBufferManager bufferManager) {
    super(stream, bufferManager);
    managedByteBuffer = bufferManager.allocateManaged(config.getInitialBufferSize().toBytes());
    buffer = managedByteBuffer.getBuffer();
    this.bufferSizeIncrement = config.getBufferSizeIncrement() != null
        ? config.getBufferSizeIncrement().toBytes()
        : 0;

    this.maxBufferSize = config.getMaxBufferSize().toBytes();
  }

  @Override
  protected ByteBuffer doGet(long position, int length) {
    return doGet(position, length, true);
  }

  private ByteBuffer doGet(long position, int length, boolean consumeStreamIfNecessary) {
    return withReadLock(releaser -> {

      ByteBuffer presentRead = getFromCurrentData(position, length);
      if (presentRead != null) {
        return presentRead;
      }

      if (consumeStreamIfNecessary) {
        releaser.release();
        return withWriteLock(() -> {

          ByteBuffer refetch;
          refetch = getFromCurrentData(position, length);
          if (refetch != null) {
            return refetch;
          }

          final long requiredUpperBound = position + length;
          while (!isStreamFullyConsumed() && bufferTip < requiredUpperBound) {
            try {
              final int read = consumeForwardData();
              if (read > 0) {
                refetch = getFromCurrentData(position, min(length, read));
                if (refetch != null) {
                  return refetch;
                }
              } else {
                streamFullyConsumed();
                buffer.limit(buffer.position());
              }
            } catch (IOException e) {
              throw new MuleRuntimeException(createStaticMessage("Could not read stream"), e);
            }
          }

          return doGet(position, length, false);
        });
      } else {
        return getFromCurrentData(position, length);
      }
    });
  }

  private ByteBuffer getFromCurrentData(long position, int length) {
    if (isStreamFullyConsumed() && position > bufferTip) {
      return null;
    }

    if (position < bufferTip) {
      length = min(length, toIntExact(bufferTip - position));
      return copy(position, length);
    }

    return null;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void doClose() {
    deallocate(managedByteBuffer);
    managedByteBuffer =  null;
    buffer = null;
  }

  /**
   * {@inheritDoc}
   * If {@code buffer} doesn't have any remaining capacity, then {@link #expandBuffer()}
   * is invoked before attempting to consume new information.
   *
   * @throws StreamingBufferSizeExceededException if the buffer is not big enough and cannot be expanded
   */
  @Override
  public int consumeForwardData() throws IOException {
    ByteBuffer b = buffer;
    ByteBuffer readBuffer;
    ManagedByteBuffer managedReadBuffer = null;

    if (b.hasRemaining()) {
      readBuffer = b;
    } else {
      managedReadBuffer = bufferManager.allocateManaged(bufferSizeIncrement > 0 ? bufferSizeIncrement : STREAM_FINISHED_PROBE);
      readBuffer = managedReadBuffer.getBuffer();
    }

    final int read;

    try {
      read = consumeStream(readBuffer);

      if (read > 0) {
        if (managedReadBuffer != null) {
          b = expandBuffer();
          readBuffer.flip();
          b.put(readBuffer);
          buffer = b;
        }

        bufferTip += read;
      } else {
        streamFullyConsumed = true;
      }
    } finally {
      if (managedReadBuffer != null) {
        managedReadBuffer.deallocate();
      }
    }

    return read;
  }

  /**
   * Expands the size of the buffer by {@link #bufferSizeIncrement}
   *
   * @return a new, expanded {@link ByteBuffer}
   */
  private ByteBuffer expandBuffer() {
    ByteBuffer b = buffer;
    int newSize = b.capacity() + bufferSizeIncrement;
    if (!canBeExpandedTo(newSize)) {
      throw new StreamingBufferSizeExceededException(maxBufferSize);
    }

    ManagedByteBuffer newManagedBuffer = bufferManager.allocateManaged(newSize);
    ByteBuffer newBuffer = newManagedBuffer.getBuffer();
    b.position(0);
    newBuffer.put(b);
    ManagedByteBuffer oldBuffer = managedByteBuffer;
    b = newBuffer;

    managedByteBuffer = newManagedBuffer;
    buffer = b;
    deallocate(oldBuffer);

    return b;
  }

  @Override
  protected ByteBuffer copy(long position, int length) {
    return canDoSoftCopy() ? softCopy(position, length) : hardCopy(position, length);
  }

  private ByteBuffer softCopy(long position, int length) {
    final int offset = toIntExact(position);
    return wrap(buffer.array(), offset, min(length, buffer.limit() - offset)).slice();
  }

  protected ByteBuffer hardCopy(long position, int length) {
    final int offset = toIntExact(position);
    length = min(length, buffer.limit() - offset);

    byte[] b = new byte[length];
    arraycopy(buffer.array(), offset, b, 0, length);
    return wrap(b);
  }

  private boolean canDoSoftCopy() {
    return streamFullyConsumed ||
        buffer.capacity() >= maxBufferSize ||
        bufferSizeIncrement == 0;
  }

  private boolean canBeExpandedTo(int newSize) {
    if (bufferSizeIncrement <= 0) {
      return false;
    } else if (maxBufferSize == 0) {
      return true;
    }

    return newSize <= maxBufferSize;
  }
}
