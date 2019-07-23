/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.extension.internal.runtime.source;

import static java.util.Collections.emptyMap;
import static java.util.concurrent.CompletableFuture.completedFuture;

import org.mule.runtime.core.api.event.CoreEvent;
import org.mule.runtime.core.api.functional.Either;
import org.mule.runtime.core.internal.exception.MessagingException;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * {@code SourceCompletionHandler} that does nothing.
 *
 * @since 4.0
 */
public class NullSourceCompletionHandler implements SourceCompletionHandler {

  @Override
  public CompletableFuture<Void> onCompletion(CoreEvent event, Map<String, Object> parameters) {
    return completedFuture(null);
  }

  @Override
  public CompletableFuture<Void> onFailure(MessagingException exception, Map<String, Object> parameters) {
    return completedFuture(null);
  }

  @Override
  public void onTerminate(Either<MessagingException, CoreEvent> eventOrException) {
    // Nothing to do.
  }

  @Override
  public Map<String, Object> createResponseParameters(CoreEvent event) {
    return emptyMap();
  }

  @Override
  public Map<String, Object> createFailureResponseParameters(CoreEvent event) {
    return emptyMap();
  }
}
