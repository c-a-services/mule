/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.internal.execution;

import static org.mule.runtime.api.metadata.MediaType.ANY;
import static org.mule.runtime.api.notification.ConnectorMessageNotification.MESSAGE_ERROR_RESPONSE;
import static org.mule.runtime.api.notification.ConnectorMessageNotification.MESSAGE_RECEIVED;
import static org.mule.runtime.api.notification.ConnectorMessageNotification.MESSAGE_RESPONSE;
import static org.mule.runtime.core.api.event.CoreEvent.builder;
import static org.mule.runtime.core.api.event.EventContextFactory.create;
import static org.mule.runtime.core.api.exception.Errors.ComponentIdentifiers.Handleable.SOURCE_ERROR_RESPONSE_GENERATE;
import static org.mule.runtime.core.api.exception.Errors.ComponentIdentifiers.Handleable.SOURCE_ERROR_RESPONSE_SEND;
import static org.mule.runtime.core.api.exception.Errors.ComponentIdentifiers.Handleable.SOURCE_RESPONSE_GENERATE;
import static org.mule.runtime.core.api.exception.Errors.ComponentIdentifiers.Handleable.SOURCE_RESPONSE_SEND;
import static org.mule.runtime.core.api.exception.Errors.ComponentIdentifiers.Unhandleable.FLOW_BACK_PRESSURE;
import static org.mule.runtime.core.api.functional.Either.left;
import static org.mule.runtime.core.api.functional.Either.right;
import static org.mule.runtime.core.api.lifecycle.LifecycleUtils.disposeIfNeeded;
import static org.mule.runtime.core.api.rx.Exceptions.unwrap;
import static org.mule.runtime.core.internal.event.EventQuickCopy.quickCopy;
import static org.mule.runtime.core.internal.message.ErrorBuilder.builder;
import static org.mule.runtime.core.internal.util.FunctionalUtils.safely;
import static org.mule.runtime.core.internal.util.InternalExceptionUtils.createErrorEvent;
import static org.mule.runtime.core.internal.util.message.MessageUtils.toMessage;
import static org.mule.runtime.core.internal.util.message.MessageUtils.toMessageCollection;
import static org.mule.runtime.core.internal.util.rx.RxUtils.createRoundRobinFluxSupplier;
import static org.mule.runtime.core.privileged.processor.MessageProcessors.applyWithChildContext;
import static org.mule.runtime.core.privileged.processor.MessageProcessors.processToApply;
import static org.slf4j.LoggerFactory.getLogger;
import static reactor.core.publisher.Flux.from;
import static reactor.core.publisher.Mono.empty;
import static reactor.core.publisher.Mono.error;
import static reactor.core.publisher.Mono.fromFuture;
import static reactor.core.publisher.Mono.just;
import static reactor.core.publisher.Mono.when;

import org.mule.runtime.api.component.Component;
import org.mule.runtime.api.component.location.ComponentLocation;
import org.mule.runtime.api.component.location.ConfigurationComponentLocator;
import org.mule.runtime.api.component.location.Location;
import org.mule.runtime.api.exception.DefaultMuleException;
import org.mule.runtime.api.exception.ErrorTypeRepository;
import org.mule.runtime.api.exception.MuleException;
import org.mule.runtime.api.exception.MuleRuntimeException;
import org.mule.runtime.api.lifecycle.Initialisable;
import org.mule.runtime.api.lifecycle.InitialisationException;
import org.mule.runtime.api.lifecycle.Startable;
import org.mule.runtime.api.lifecycle.Stoppable;
import org.mule.runtime.api.message.ErrorType;
import org.mule.runtime.api.message.Message;
import org.mule.runtime.api.metadata.TypedValue;
import org.mule.runtime.core.api.construct.FlowConstruct;
import org.mule.runtime.core.api.event.CoreEvent;
import org.mule.runtime.core.api.exception.NullExceptionHandler;
import org.mule.runtime.core.api.functional.Either;
import org.mule.runtime.core.api.processor.Processor;
import org.mule.runtime.core.api.processor.strategy.ProcessingStrategy;
import org.mule.runtime.core.api.source.MessageSource;
import org.mule.runtime.core.internal.construct.FlowBackPressureException;
import org.mule.runtime.core.internal.exception.MessagingException;
import org.mule.runtime.core.internal.interception.InterceptorManager;
import org.mule.runtime.core.internal.message.ErrorBuilder;
import org.mule.runtime.core.internal.message.InternalEvent;
import org.mule.runtime.core.internal.message.InternalEvent.Builder;
import org.mule.runtime.core.internal.policy.PolicyManager;
import org.mule.runtime.core.internal.policy.SourcePolicy;
import org.mule.runtime.core.internal.policy.SourcePolicyFailureResult;
import org.mule.runtime.core.internal.policy.SourcePolicyResult;
import org.mule.runtime.core.internal.policy.SourcePolicySuccessResult;
import org.mule.runtime.core.internal.processor.interceptor.CompletableInterceptorSourceCallbackAdapter;
import org.mule.runtime.core.internal.util.MessagingExceptionResolver;
import org.mule.runtime.core.internal.util.mediatype.MediaTypeDecoratedResultCollection;
import org.mule.runtime.core.internal.util.rx.FluxSinkSupplier;
import org.mule.runtime.core.privileged.PrivilegedMuleContext;
import org.mule.runtime.core.privileged.event.BaseEventContext;
import org.mule.runtime.core.privileged.exception.EventProcessingException;
import org.mule.runtime.core.privileged.execution.MessageProcessContext;
import org.mule.runtime.core.privileged.execution.MessageProcessTemplate;
import org.mule.runtime.extension.api.runtime.operation.Result;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.inject.Inject;
import javax.xml.namespace.QName;

import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import reactor.core.publisher.Mono;

/**
 * This phase routes the message through the flow.
 * <p>
 * To participate of this phase, {@link MessageProcessTemplate} must implement {@link ModuleFlowProcessingPhaseTemplate}
 * <p>
 * This implementation will know how to process messages from extension's sources
 */
public class ModuleFlowProcessingPhase
    extends NotificationFiringProcessingPhase<ModuleFlowProcessingPhaseTemplate> implements Initialisable, Startable, Stoppable {

  private static final Logger LOGGER = getLogger(ModuleFlowProcessingPhase.class);
  private static final String PHASE_CONTEXT = "moduleProcessingPhase.phaseContext";

  private ErrorType sourceResponseGenerateErrorType;
  private ErrorType sourceResponseSendErrorType;
  private ErrorType sourceErrorResponseGenerateErrorType;
  private ErrorType sourceErrorResponseSendErrorType;
  private ConfigurationComponentLocator componentLocator;

  private FluxSinkSupplier<PhaseContext> dispatchFlux;

  private final PolicyManager policyManager;

  private final List<CompletableInterceptorSourceCallbackAdapter> additionalInterceptors = new LinkedList<>();

  @Inject
  private InterceptorManager processorInterceptorManager;

  private ErrorType flowBackPressureErrorType;

  public ModuleFlowProcessingPhase(PolicyManager policyManager) {
    this.policyManager = policyManager;
  }

  @Override
  public void initialise() throws InitialisationException {
    final ErrorTypeRepository errorTypeRepository = muleContext.getErrorTypeRepository();
    componentLocator = muleContext.getConfigurationComponentLocator();

    sourceResponseGenerateErrorType = errorTypeRepository.getErrorType(SOURCE_RESPONSE_GENERATE).get();
    sourceResponseSendErrorType = errorTypeRepository.getErrorType(SOURCE_RESPONSE_SEND).get();
    sourceErrorResponseGenerateErrorType = errorTypeRepository.getErrorType(SOURCE_ERROR_RESPONSE_GENERATE).get();
    sourceErrorResponseSendErrorType = errorTypeRepository.getErrorType(SOURCE_ERROR_RESPONSE_SEND).get();
    flowBackPressureErrorType = errorTypeRepository.getErrorType(FLOW_BACK_PRESSURE).get();

    if (processorInterceptorManager != null) {
      processorInterceptorManager.getSourceInterceptorFactories().stream().forEach(interceptorFactory -> {
        CompletableInterceptorSourceCallbackAdapter reactiveInterceptorAdapter =
            new CompletableInterceptorSourceCallbackAdapter(interceptorFactory);
        try {
          muleContext.getInjector().inject(reactiveInterceptorAdapter);
        } catch (MuleException e) {
          throw new MuleRuntimeException(e);
        }
        additionalInterceptors.add(0, reactiveInterceptorAdapter);
      });
    }
  }

  @Override
  public void start() throws MuleException {
    Function<SourcePolicyFailureResult, CompletableFuture<PhaseContext>> onPolicyFailure = policyFailure();
    Function<SourcePolicySuccessResult, CompletableFuture<PhaseContext>> onPolicySuccess = policySuccess();

    dispatchFlux = createRoundRobinFluxSupplier(flux -> {
      return flux
          .doOnNext(phaseContext -> {
            onMessageReceived(phaseContext);
            try {
              phaseContext.flowConstruct.checkBackpressure(phaseContext.event);
              // Process policy and in turn flow emitting Either<SourcePolicyFailureResult,SourcePolicySuccessResult>> when
              // complete.
              CompletableFuture<Either<SourcePolicyFailureResult, SourcePolicySuccessResult>> process =
                  phaseContext.sourcePolicy.process(phaseContext.event, phaseContext.template);

              process.whenComplete((v, e) -> {
                if (e != null) {
                  e = unwrap(e);
                  if (e instanceof FlowBackPressureException) {
                    // In case back pressure was fired, the exception will be propagated as a SourcePolicyFailureResult, wrapping inside
                    // the back pressure exception
                    onPolicyResult(mapBackPressureExceptionToPolicyFailureResult(e, phaseContext.template, phaseContext.event));
                  } else {
                    onError(e, phaseContext);
                  }
                } else {
                  onPolicyResult(v);
                }
              });
            } catch (Throwable e) {
              e = unwrap(e);
              if (e instanceof FlowBackPressureException) {
                // In case back pressure was fired, the exception will be propagated as a SourcePolicyFailureResult, wrapping inside
                // the back pressure exception
                onPolicyResult(mapBackPressureExceptionToPolicyFailureResult(e, phaseContext.template, phaseContext.event));
              }
              onError(e, phaseContext);
            }
          })
          .onErrorContinue((e, v) -> {
            e = unwrap(e);
            PhaseContext ctx = recoverPhaseContext(v, e);
            try {
              onFailure(ctx, e);
            } finally {
              ctx.responseCompletion.complete(null);
            }
          });
    }, Runtime.getRuntime().availableProcessors() * 1);
  }

  private void onError(Throwable e, PhaseContext ctx) {
    try {
      onFailure(ctx, e);
    } finally {
      ctx.responseCompletion.complete(null);
    }
  }

  private void onPolicyResult(Either<SourcePolicyFailureResult, SourcePolicySuccessResult> policyResult) {
    Function<SourcePolicyFailureResult, CompletableFuture<PhaseContext>> onPolicyFailure = policyFailure();
    Function<SourcePolicySuccessResult, CompletableFuture<PhaseContext>> onPolicySuccess = policySuccess();

    CompletableFuture<Void> future = policyResult.reduce(onPolicyFailure, onPolicySuccess).thenAccept(ctx -> {
      try {
        ctx.phaseResultNotifier.phaseSuccessfully();
      } finally {
        ctx.responseCompletion.complete(null);
      }
    });

  }

  @Override
  public void stop() throws MuleException {
    // yes, you read right. At stop, we need to dispose the flux as that's what stops it.
    disposeIfNeeded(dispatchFlux, LOGGER);
  }

  private PhaseContext recoverPhaseContext(Object value, Throwable t) {
    if (t instanceof SourceErrorException) {
      return PhaseContext.from(((SourceErrorException) t).getEvent());
    }

    if (t instanceof EventProcessingException) {
      return PhaseContext.from(((EventProcessingException) t).getEvent());
    }

    if (value instanceof PhaseContext) {
      return (PhaseContext) value;
    }

    if (value instanceof SourcePolicyResult) {
      return PhaseContext.from(((SourcePolicyResult) value).getEvent());
    }

    // this should be impossible. This is just for easily identifying the problem in case of a big bug
    throw new IllegalStateException("Phase context can not be recovered from " + value);
  }

  @Override
  public boolean supportsTemplate(MessageProcessTemplate messageProcessTemplate) {
    return messageProcessTemplate instanceof ModuleFlowProcessingPhaseTemplate;
  }

  @Override
  public void runPhase(final ModuleFlowProcessingPhaseTemplate template, final MessageProcessContext messageProcessContext,
                       final PhaseResultNotifier phaseResultNotifier) {
    try {

      final MessageSource messageSource = messageProcessContext.getMessageSource();
      final FlowConstruct flowConstruct = (FlowConstruct) componentLocator.find(messageSource.getRootContainerLocation()).get();
      final CompletableFuture<Void> responseCompletion = new CompletableFuture<>();
      final FlowProcessor flowExecutionProcessor = new FlowProcessor(template, flowConstruct);
      CoreEvent event = createEvent(template, messageSource, responseCompletion, flowConstruct);

      try {
        final SourcePolicy policy =
            policyManager.createSourcePolicyInstance(messageSource, event, flowExecutionProcessor, template);

        final PhaseContext phaseContext = new PhaseContext(template,
                                                           messageSource,
                                                           messageProcessContext,
                                                           phaseResultNotifier,
                                                           flowConstruct,
                                                           getTerminateConsumer(messageSource, template),
                                                           responseCompletion,
                                                           event,
                                                           policy);

        Map<String, Object> internalParams = new HashMap<>();
        internalParams.put(PHASE_CONTEXT, phaseContext);
        event = quickCopy(event, internalParams);
        phaseContext.event = event;

        dispatchFlux.get().next(phaseContext);
      } catch (Exception e) {
        template.sendFailureResponseToClient(
                                             new MessagingExceptionResolver(messageSource)
                                                 .resolve(new MessagingException(event, e), muleContext),
                                             template.getFailedExecutionResponseParametersFunction().apply(event))
            .whenComplete((v, e2) -> phaseResultNotifier.phaseFailure(e));
      }
    } catch (Exception t) {
      phaseResultNotifier.phaseFailure(t);
    }
  }

  /**
   * Notifies the {@link FlowConstruct} response listenting party of the backpressure signal raised when trying to inject the
   * event for processing into the {@link ProcessingStrategy}.
   * <p>
   * By wrapping the thrown back pressure exception in an {@link Either} which contains the {@link SourcePolicyFailureResult}, one
   * can consider as if the back pressure signal was fired from inside the policy + flow execution chain, and reuse all handling
   * logic.
   *
   * @param template the processing template being used
   * @param event    the event that caused the back pressure signal to be fired
   * @return an exception mapper that notifies the {@link FlowConstruct} response listener of the back pressure signal
   */
  protected Either<SourcePolicyFailureResult, SourcePolicySuccessResult> mapBackPressureExceptionToPolicyFailureResult(
                                                                                                                       Throwable exception,
                                                                                                                       ModuleFlowProcessingPhaseTemplate template,
                                                                                                                       CoreEvent event) {

    // Build error event
    CoreEvent errorEvent = CoreEvent.builder(event)
        .error(ErrorBuilder.builder(exception)
            .errorType(flowBackPressureErrorType)
            .build())
        .build();

    // Since the decision whether the event is handled by the source onError or onBackPressure callback is made in
    // SourceAdapter by checking the ErrorType, the exception is wrapped
    SourcePolicyFailureResult result =
        new SourcePolicyFailureResult(new MessagingException(errorEvent, exception),
                                      () -> template.getFailedExecutionResponseParametersFunction().apply(errorEvent));

    return left(result);
  }

  /*
   * Consumer invoked for each new execution of this processing phase.
   */
  private void onMessageReceived(PhaseContext phaseContext) {
    fireNotification(phaseContext.messageSource, phaseContext.event, phaseContext.flowConstruct, MESSAGE_RECEIVED);
    phaseContext.template.getNotificationFunctions().forEach(
                                                             notificationFunction -> muleContext.getNotificationManager()
                                                                 .fireNotification(
                                                                                   notificationFunction
                                                                                       .apply(phaseContext.event,
                                                                                              phaseContext.messageSource)));
  }

  /*
   * Process success by attempting to send a response to client handling the case where response sending fails or the resolution
   * of response parameters fails.
   */
  private Function<SourcePolicySuccessResult, CompletableFuture<PhaseContext>> policySuccess() {
    return successResult -> {
      final PhaseContext ctx = PhaseContext.from(successResult.getEvent());

      fireNotification(ctx.messageProcessContext.getMessageSource(), successResult.getEvent(),
                       ctx.flowConstruct, MESSAGE_RESPONSE);
      try {
        Function<SourcePolicySuccessResult, CompletableFuture<Void>> sendResponseToClient =
            result -> ctx.template.sendResponseToClient(result.getEvent(), result.getResponseParameters().get());
        for (CompletableInterceptorSourceCallbackAdapter interceptor : additionalInterceptors) {
          sendResponseToClient = interceptor.apply(ctx.messageSource, sendResponseToClient);
        }

        return sendResponseToClient.apply(successResult)
            .thenApply(v -> {
              onTerminate(ctx.flowConstruct, ctx.messageSource, ctx.terminateConsumer, right(successResult.getEvent()));
              return ctx;
            })
            .exceptionally(e -> {
              policySuccessError(new SourceErrorException(successResult.getEvent(), sourceResponseSendErrorType, e),
                                 successResult, ctx, ctx.flowConstruct, ctx.messageSource);
              return ctx;
            });
      } catch (Exception e) {
        return policySuccessError(new SourceErrorException(successResult.getEvent(), sourceResponseGenerateErrorType, e),
                                  successResult, ctx, ctx.flowConstruct, ctx.messageSource);
      }
    };
  }

  /**
   * Process failure success by attempting to send an error response to client handling the case where error response sending
   * fails or the resolution of error response parameters fails.
   */
  private Function<SourcePolicyFailureResult, CompletableFuture<PhaseContext>> policyFailure() {
    return failureResult -> {
      final PhaseContext ctx = PhaseContext.from(failureResult.getEvent());
      fireNotification(ctx.messageProcessContext.getMessageSource(), failureResult.getMessagingException().getEvent(),
                       ctx.flowConstruct, MESSAGE_ERROR_RESPONSE);

      return sendErrorResponse(failureResult.getMessagingException(),
                               event -> failureResult.getErrorResponseParameters().get(),
                               ctx)
                                   .doOnSuccess(v -> onTerminate(ctx.flowConstruct,
                                                                 ctx.messageSource,
                                                                 ctx.terminateConsumer,
                                                                 left(failureResult.getMessagingException())))
                                   .then(just(ctx))
                                   .toFuture();
    };
  }

  /*
   * Handle errors caused when attempting to process a success response by invoking flow error handler and disregarding the result
   * and sending an error response.
   */
  private CompletableFuture<PhaseContext> policySuccessError(SourceErrorException see,
                                                             SourcePolicySuccessResult successResult,
                                                             PhaseContext ctx,
                                                             FlowConstruct flowConstruct,
                                                             MessageSource messageSource) {

    //TODO: Refactor this into ideally readable code, or a flux at the very least
    MessagingException messagingException =
        see.toMessagingException(flowConstruct.getMuleContext().getExceptionContextProviders(), messageSource);

    return when(just(messagingException).flatMapMany(flowConstruct.getExceptionListener()).last()
        .onErrorResume(e -> empty()),
                sendErrorResponse(messagingException, successResult.createErrorResponseParameters(), ctx)
                    .doOnSuccess(v -> onTerminate(flowConstruct, messageSource, ctx.terminateConsumer,
                                                  left(messagingException))))
                                                      .then(just(ctx))
                                                      .toFuture();
  }

  /**
   * Send an error response. This may be due to an error being propagated from the Flow or due to a failure sending a success
   * response. Error caused by failures in the flow error handler do not result in an error message being sent.
   */
  private Mono<Void> sendErrorResponse(MessagingException messagingException,
                                       Function<CoreEvent, Map<String, Object>> errorParameters,
                                       final PhaseContext ctx) {
    CoreEvent event = messagingException.getEvent();

    try {
      return fromFuture(ctx.template
          .sendFailureResponseToClient(messagingException, errorParameters.apply(event)))
              .onErrorMap(e -> new SourceErrorException(builder(event)
                  .error(builder(e).errorType(sourceErrorResponseSendErrorType).build())
                  .build(),
                                                        sourceErrorResponseSendErrorType, e));
    } catch (Exception e) {
      return error(new SourceErrorException(event, sourceErrorResponseGenerateErrorType, e, messagingException));
    }
  }

  /*
   * Consumer invoked when processing fails due to an error sending an error response, of because the error originated from within
   * an error handler.
   */
  private void onFailure(PhaseContext ctx, Throwable throwable) {
    onTerminate(ctx.flowConstruct, ctx.messageSource, ctx.terminateConsumer, left(throwable));
    throwable = throwable instanceof SourceErrorException ? throwable.getCause() : throwable;
    Exception failureException = throwable instanceof Exception ? (Exception) throwable : new DefaultMuleException(throwable);
    ctx.phaseResultNotifier.phaseFailure(failureException);
  }

  private Consumer<Either<MessagingException, CoreEvent>> getTerminateConsumer(MessageSource messageSource,
                                                                               ModuleFlowProcessingPhaseTemplate template) {
    return eventOrException -> template.afterPhaseExecution(eventOrException.mapLeft(messagingException -> {
      messagingException.setProcessedEvent(createErrorEvent(messagingException.getEvent(), messageSource, messagingException,
                                                            ((PrivilegedMuleContext) muleContext).getErrorTypeLocator()));
      return messagingException;
    }));
  }

  private CoreEvent createEvent(ModuleFlowProcessingPhaseTemplate template,
                                MessageSource source,
                                CompletableFuture<Void> responseCompletion,
                                FlowConstruct flowConstruct) {

    SourceResultAdapter adapter = template.getSourceMessage();
    Builder eventBuilder =
        createEventBuilder(source.getLocation(), responseCompletion, flowConstruct, adapter.getCorrelationId().orElse(null));

    return eventBuilder.message(eventCtx -> {
      final Result<?, ?> result = adapter.getResult();
      final Object resultValue = result.getOutput();

      Message eventMessage;
      if (resultValue instanceof Collection && adapter.isCollection()) {
        eventMessage = toMessage(Result.<Collection<Message>, TypedValue>builder()
            .output(toMessageCollection(
                                        new MediaTypeDecoratedResultCollection((Collection<Result>) resultValue,
                                                                               adapter.getPayloadMediaTypeResolver()),
                                        adapter.getCursorProviderFactory(),
                                        ((BaseEventContext) eventCtx).getRootContext()))
            .mediaType(result.getMediaType().orElse(ANY))
            .build());
      } else {
        eventMessage = toMessage(result, adapter.getMediaType(), adapter.getCursorProviderFactory(),
                                 ((BaseEventContext) eventCtx).getRootContext());
      }

      policyManager.addSourcePointcutParametersIntoEvent(source, eventMessage.getAttributes(), eventBuilder);
      return eventMessage;
    }).build();
  }

  private Builder createEventBuilder(ComponentLocation sourceLocation, CompletableFuture<Void> responseCompletion,
                                     FlowConstruct flowConstruct, String correlationId) {
    return InternalEvent
        .builder(create(flowConstruct, NullExceptionHandler.getInstance(), sourceLocation, correlationId,
                        Optional.of(responseCompletion)));
  }

  /**
   * This method will not throw any {@link Exception}.
   *
   * @param flowConstruct     the flow being executed.
   * @param messageSource     the source that triggered the flow execution.
   * @param terminateConsumer the action to perform on the transformed result.
   * @param result            the outcome of trying to send the response of the source through the source. In the case of error, only
   *                          {@link MessagingException} or {@link SourceErrorException} are valid values on the {@code left} side of this
   *                          parameter.
   */
  private void onTerminate(FlowConstruct flowConstruct, MessageSource messageSource,
                           Consumer<Either<MessagingException, CoreEvent>> terminateConsumer,
                           Either<Throwable, CoreEvent> result) {
    safely(() -> terminateConsumer.accept(result.mapLeft(throwable -> {
      if (throwable instanceof MessagingException) {
        return (MessagingException) throwable;
      } else if (throwable instanceof SourceErrorException) {
        return ((SourceErrorException) throwable)
            .toMessagingException(flowConstruct.getMuleContext().getExceptionContextProviders(), messageSource);
      } else {
        return null;
      }
    })));
  }

  @Override
  public int compareTo(MessageProcessPhase messageProcessPhase) {
    if (messageProcessPhase instanceof ValidationPhase) {
      return 1;
    }
    return 0;
  }

  private class FlowProcessor implements Processor, Component {

    private final ModuleFlowProcessingPhaseTemplate template;
    private final FlowConstruct flowConstruct;

    public FlowProcessor(ModuleFlowProcessingPhaseTemplate template, FlowConstruct flowConstruct) {
      this.template = template;
      this.flowConstruct = flowConstruct;
    }

    @Override
    public CoreEvent process(CoreEvent event) throws MuleException {
      return processToApply(event, this);
    }

    @Override
    public Publisher<CoreEvent> apply(Publisher<CoreEvent> publisher) {
      return applyWithChildContext(from(publisher),
                                   template::routeEventAsync,
                                   Optional.empty(),
                                   flowConstruct.getExceptionListener());
    }

    @Override
    public Object getAnnotation(QName name) {
      return flowConstruct.getAnnotation(name);
    }

    @Override
    public Map<QName, Object> getAnnotations() {
      return flowConstruct.getAnnotations();
    }

    @Override
    public void setAnnotations(Map<QName, Object> annotations) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ComponentLocation getLocation() {
      return flowConstruct.getLocation();
    }

    @Override
    public Location getRootContainerLocation() {
      return flowConstruct.getRootContainerLocation();
    }
  }


  /**
   * Container for passing relevant context between private methods to avoid long method signatures everywhere.
   */
  private static final class PhaseContext {

    private final ModuleFlowProcessingPhaseTemplate template;
    private final MessageSource messageSource;
    private final MessageProcessContext messageProcessContext;
    private final PhaseResultNotifier phaseResultNotifier;
    private final FlowConstruct flowConstruct;
    private final Consumer<Either<MessagingException, CoreEvent>> terminateConsumer;
    private final CompletableFuture<Void> responseCompletion;
    private final SourcePolicy sourcePolicy;

    private CoreEvent event;

    private static PhaseContext from(CoreEvent event) {
      return ((InternalEvent) event).getInternalParameter(PHASE_CONTEXT);
    }

    private PhaseContext(ModuleFlowProcessingPhaseTemplate template,
                         MessageSource messageSource,
                         MessageProcessContext messageProcessContext,
                         PhaseResultNotifier phaseResultNotifier,
                         FlowConstruct flowConstruct,
                         Consumer<Either<MessagingException, CoreEvent>> terminateConsumer,
                         CompletableFuture<Void> responseCompletion,
                         CoreEvent event,
                         SourcePolicy sourcePolicy) {
      this.template = template;
      this.messageSource = messageSource;
      this.messageProcessContext = messageProcessContext;
      this.phaseResultNotifier = phaseResultNotifier;
      this.flowConstruct = flowConstruct;
      this.terminateConsumer = terminateConsumer;
      this.responseCompletion = responseCompletion;
      this.event = event;
      this.sourcePolicy = sourcePolicy;
    }
  }
}
