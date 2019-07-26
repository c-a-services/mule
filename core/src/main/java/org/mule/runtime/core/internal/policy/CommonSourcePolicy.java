/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.internal.policy;

import static com.google.common.collect.ImmutableMap.of;
import static java.lang.Runtime.getRuntime;
import static java.util.Optional.empty;
import static org.mule.runtime.api.i18n.I18nMessageFactory.createStaticMessage;
import static org.mule.runtime.core.api.functional.Either.left;
import static org.mule.runtime.core.internal.event.EventQuickCopy.quickCopy;

import org.mule.runtime.core.api.event.CoreEvent;
import org.mule.runtime.core.api.functional.Either;
import org.mule.runtime.core.api.policy.SourcePolicyParametersTransformer;
import org.mule.runtime.core.internal.exception.MessagingException;
import org.mule.runtime.core.internal.message.InternalEvent;
import org.mule.runtime.core.internal.util.rx.FluxSinkSupplier;
import org.mule.runtime.core.internal.util.rx.RoundRobinFluxSinkSupplier;
import org.mule.runtime.core.internal.util.rx.TransactionAwareFluxSinkSupplier;
import org.mule.runtime.core.privileged.event.BaseEventContext;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

import reactor.core.publisher.FluxSink;

/**
 * Common behavior for flow dispatching, whether policies are applied or not.
 */
class CommonSourcePolicy {

  public static final String POLICY_SOURCE_PARAMETERS_PROCESSOR = "policy.source.parametersProcessor";
  public static final String POLICY_SOURCE_COMPLETABLE_FUTURE = "policy.source.completableFuture";

  private final FluxSinkSupplier<CoreEvent> policySink;
  private final AtomicBoolean disposed;
  private final ReadWriteLock readWriteLock = new ReentrantReadWriteLock();
  private final Lock readLock = readWriteLock.readLock();
  private final Lock writeLock = readWriteLock.writeLock();

  private final Optional<SourcePolicyParametersTransformer> sourcePolicyParametersTransformer;

  CommonSourcePolicy(Supplier<FluxSink<CoreEvent>> sinkFactory) {
    this(sinkFactory, empty());
  }

  CommonSourcePolicy(Supplier<FluxSink<CoreEvent>> sinkFactory,
                     Optional<SourcePolicyParametersTransformer> sourcePolicyParametersTransformer) {
    this.policySink =
        new TransactionAwareFluxSinkSupplier<>(sinkFactory,
                                               new RoundRobinFluxSinkSupplier<>(getRuntime().availableProcessors(), sinkFactory));
    this.sourcePolicyParametersTransformer = sourcePolicyParametersTransformer;
    this.disposed = new AtomicBoolean(false);
  }

  public CompletableFuture<Either<SourcePolicyFailureResult, SourcePolicySuccessResult>> process(CoreEvent sourceEvent,
                                                                                                 MessageSourceResponseParametersProcessor respParamProcessor) {
    CompletableFuture<Either<SourcePolicyFailureResult, SourcePolicySuccessResult>> future = new CompletableFuture<>();

    if (!disposed.get()) {
      InternalEvent dispatchEvent = quickCopy(sourceEvent, of(POLICY_SOURCE_PARAMETERS_PROCESSOR, respParamProcessor,
                                                              POLICY_SOURCE_COMPLETABLE_FUTURE, future));
      FluxSink<CoreEvent> sink;
      readLock.lock();
      try {
        sink = policySink.get();
      } finally {
        readLock.unlock();
      }

      if (!disposed.get()) {
        // this should be inside the read lock... taking it out temporarily to trouble shoot weird issue
         sink.next(dispatchEvent);
      }
    } else {
      MessagingException me = new MessagingException(createStaticMessage("Source policy already disposed"), sourceEvent);

      Supplier<Map<String, Object>> errorParameters = sourcePolicyParametersTransformer.isPresent()
          ? (() -> sourcePolicyParametersTransformer.get().fromMessageToErrorResponseParameters(sourceEvent.getMessage()))
          : (() -> respParamProcessor.getFailedExecutionResponseParametersFunction().apply(sourceEvent));

      SourcePolicyFailureResult result = new SourcePolicyFailureResult(me, errorParameters);
      future.complete(left(result));
    }

    return future;
  }

  public MessageSourceResponseParametersProcessor getResponseParamsProcessor(CoreEvent event) {
    return ((InternalEvent) event).getInternalParameter(POLICY_SOURCE_PARAMETERS_PROCESSOR);
  }

  public void finishFlowProcessing(CoreEvent event, Either<SourcePolicyFailureResult, SourcePolicySuccessResult> result) {
    if (!((BaseEventContext) event.getContext()).isComplete()) {
      ((BaseEventContext) event.getContext()).success(event);
    }

    recoverFuture(event).complete(result);
  }

  public void finishFlowProcessing(CoreEvent event, Either<SourcePolicyFailureResult, SourcePolicySuccessResult> result,
                                   Throwable error) {
    if (!((BaseEventContext) event.getContext()).isComplete()) {
      ((BaseEventContext) event.getContext()).error(error);
    }

    recoverFuture(event).complete(result);
  }

  private CompletableFuture<Either<SourcePolicyFailureResult, SourcePolicySuccessResult>> recoverFuture(CoreEvent event) {
    return ((InternalEvent) event).getInternalParameter(POLICY_SOURCE_COMPLETABLE_FUTURE);
  }

  public void dispose() {
    writeLock.lock();
    try {
      disposed.set(true);
      policySink.dispose();
    } finally {
      writeLock.unlock();
    }
  }
}
