/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.internal.streaming.bytes;

import java.nio.ByteBuffer;

import stormpot.Poolable;
import stormpot.Slot;

class PooledByteBuffer implements Poolable {

  private final Slot slot;
  private final ByteBuffer byteBuffer;

  public PooledByteBuffer(Slot slot, ByteBuffer byteBuffer) {
    this.slot = slot;
    this.byteBuffer = byteBuffer;
  }

  @Override
  public void release() {
    byteBuffer.clear();
    slot.release(this);
  }

  public ByteBuffer getByteBuffer() {
    return byteBuffer;
  }
}
