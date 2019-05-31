/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.internal.processor.strategy;

import static org.mule.runtime.core.api.lifecycle.LifecycleUtils.disposeIfNeeded;
import static org.mule.runtime.core.api.transaction.TransactionCoordination.isTransactionActive;

import org.mule.runtime.api.lifecycle.Disposable;
import org.mule.runtime.core.api.event.CoreEvent;
import org.mule.runtime.core.api.processor.Sink;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * Delegate {@link Sink} that uses one of two {@link Sink}'s depending on if a transaction is in context or not.
 */
final class TransactionalDelegateSink implements Sink, Disposable {

  private static final Logger LOGGER = LoggerFactory.getLogger(TransactionalDelegateSink.class);

  private final Sink transactionalSink;
  private final Sink sink;
  private final Supplier<Sink> transactionalSinkSupplier;

  public TransactionalDelegateSink(Sink transactionalSink, Sink sink) {
    this.transactionalSink = transactionalSink;
    this.sink = sink;
    transactionalSinkSupplier = null;
  }

  public TransactionalDelegateSink(Supplier<Sink> sinkSupplier, Sink sink) {
    transactionalSinkSupplier = sinkSupplier;
    this.sink = sink;
    this.transactionalSink = null;
  }

  @Override
  public void accept(CoreEvent event) {
    if (isTransactionActive()) {
      LOGGER.error("PROCESS TX");
      if (transactionalSinkSupplier == null) {
        LOGGER.error("WITH SINK");
        transactionalSink.accept(event);
      } else {
        LOGGER.error("WITH SUPPLIER");
        transactionalSinkSupplier.get().accept(event);
      }
    } else {
      LOGGER.error("ESTA ROTITO");
      sink.accept(event);
    }
  }

  @Override
  public boolean emit(CoreEvent event) {
    if (isTransactionActive()) {
      return transactionalSink.emit(event);
    } else {
      return sink.emit(event);
    }
  }

  @Override
  public void dispose() {
    disposeIfNeeded(transactionalSink, LOGGER);
    disposeIfNeeded(sink, LOGGER);
  }
}
