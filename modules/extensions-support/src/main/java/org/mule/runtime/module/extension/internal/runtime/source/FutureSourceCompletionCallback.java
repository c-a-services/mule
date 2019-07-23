/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.extension.internal.runtime.source;

import org.mule.runtime.extension.api.runtime.source.SourceCompletionCallback;

import java.util.concurrent.CompletableFuture;

/**
 * Implementation of {@link SourceCompletionCallback} which works using a supplied {@link CompletableFuture}
 *
 * @since 4.3.0
 */
final class FutureSourceCompletionCallback implements SourceCompletionCallback {

  private final CompletableFuture<Void> future;

  /**
   * Creates a new instance
   * @param future a {@link CompletableFuture} to be completed through this callback
   */
  public FutureSourceCompletionCallback(CompletableFuture<Void> future) {
    this.future = future;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void success() {
    future.complete(null);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void error(Throwable t) {
    future.completeExceptionally(t);
  }
}
