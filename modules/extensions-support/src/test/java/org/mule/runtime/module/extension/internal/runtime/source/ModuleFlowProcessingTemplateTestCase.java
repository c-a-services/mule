/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.extension.internal.runtime.source;

import static java.util.Collections.emptyList;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.junit.Assert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mule.runtime.core.internal.util.ConcurrencyUtils.exceptionallyCompleted;
import static reactor.core.publisher.Mono.just;

import org.mule.runtime.api.util.Reference;
import org.mule.runtime.core.api.event.CoreEvent;
import org.mule.runtime.core.api.processor.Processor;
import org.mule.runtime.core.internal.exception.MessagingException;
import org.mule.runtime.core.internal.execution.SourceResultAdapter;
import org.mule.tck.junit4.AbstractMuleTestCase;
import org.mule.tck.size.SmallTest;

import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.reactivestreams.Publisher;

@SmallTest
@RunWith(MockitoJUnitRunner.class)
public class ModuleFlowProcessingTemplateTestCase extends AbstractMuleTestCase {

  @Mock
  private SourceResultAdapter message;

  @Mock
  private CoreEvent event;

  @Mock
  private Processor messageProcessor;

  @Mock
  private SourceCompletionHandler completionHandler;

  @Mock(lenient = true)
  private MessagingException messagingException;

  @Mock
  private Map<String, Object> mockParameters;

  private final RuntimeException runtimeException = new RuntimeException();

  private ModuleFlowProcessingTemplate template;

  @Before
  public void before() throws Exception {
    template = new ModuleFlowProcessingTemplate(message, messageProcessor, emptyList(), completionHandler);
    when(completionHandler.onCompletion(any(), any())).thenReturn(completedFuture(null));
    when(completionHandler.onFailure(any(), any())).thenReturn(completedFuture(null));
  }

  @Test
  public void getMuleEvent() throws Exception {
    assertThat(template.getSourceMessage(), is(sameInstance(message)));
  }

  @Test
  public void routeEvent() throws Exception {
    template.routeEvent(event);
    verify(messageProcessor).process(event);
  }

  @Test
  public void routeEventAsync() throws Exception {
    when(messageProcessor.apply(any(Publisher.class))).thenReturn(just(event));
    template.routeEventAsync(event);
    verify(messageProcessor).apply(any(Publisher.class));
  }

  @Test
  public void sendResponseToClient() throws Exception {
    template.sendResponseToClient(event, mockParameters).get();
    verify(completionHandler).onCompletion(same(event), same(mockParameters));
  }

  @Test
  public void failedToSendResponseToClient() throws Exception {
    Reference<Throwable> exceptionReference = new Reference<>();
    when(completionHandler.onCompletion(same(event), same(mockParameters))).thenReturn(exceptionallyCompleted(runtimeException));
    template.sendResponseToClient(event, mockParameters).handle((v, e) -> exceptionReference.set(e)).get();

    verify(completionHandler, never()).onFailure(any(MessagingException.class), same(mockParameters));
    assertThat(exceptionReference.get(), equalTo(runtimeException));
  }

  @Test
  public void sendFailureResponseToClient() throws Exception {
    template.sendFailureResponseToClient(messagingException, mockParameters).get();
    verify(completionHandler).onFailure(messagingException, mockParameters);
  }

  @Test
  public void failedToSendFailureResponseToClient() throws Exception {
    Reference<Throwable> exceptionReference = new Reference<>();
    when(messagingException.getEvent()).thenReturn(event);
    when(completionHandler.onFailure(messagingException, mockParameters)).thenReturn(exceptionallyCompleted(runtimeException));
    template.sendFailureResponseToClient(messagingException, mockParameters).handle((v, e) -> exceptionReference.set(e)).get();
    assertThat(exceptionReference.get(), equalTo(runtimeException));
  }
}
