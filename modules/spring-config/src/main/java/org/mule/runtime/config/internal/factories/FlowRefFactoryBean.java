/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.config.internal.factories;

import static java.util.Collections.singletonMap;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;
import static org.mule.runtime.api.i18n.I18nMessageFactory.createStaticMessage;
import static org.mule.runtime.core.api.lifecycle.LifecycleUtils.disposeIfNeeded;
import static org.mule.runtime.core.api.lifecycle.LifecycleUtils.initialiseIfNeeded;
import static org.mule.runtime.core.api.lifecycle.LifecycleUtils.startIfNeeded;
import static org.mule.runtime.core.api.lifecycle.LifecycleUtils.stopIfNeeded;
import static org.mule.runtime.core.internal.event.EventQuickCopy.quickCopy;
import static org.mule.runtime.core.internal.util.rx.Operators.outputToTarget;
import static org.mule.runtime.core.privileged.processor.MessageProcessors.applyWithChildContext;
import static org.mule.runtime.core.privileged.processor.MessageProcessors.processWithChildContext;
import static org.slf4j.LoggerFactory.getLogger;
import static reactor.core.publisher.Flux.error;
import static reactor.core.publisher.Flux.from;
import org.mule.runtime.api.component.Component;
import org.mule.runtime.api.component.location.ConfigurationComponentLocator;
import org.mule.runtime.api.exception.MuleException;
import org.mule.runtime.api.exception.MuleRuntimeException;
import org.mule.runtime.api.util.LazyValue;
import org.mule.runtime.config.internal.MuleArtifactContext;
import org.mule.runtime.core.api.MuleContext;
import org.mule.runtime.core.api.construct.Flow;
import org.mule.runtime.core.api.construct.FlowConstruct;
import org.mule.runtime.core.api.el.ExtendedExpressionManager;
import org.mule.runtime.core.api.event.CoreEvent;
import org.mule.runtime.core.api.processor.Processor;
import org.mule.runtime.core.api.processor.ReactiveProcessor;
import org.mule.runtime.core.api.processor.Sink;
import org.mule.runtime.core.api.processor.strategy.ProcessingStrategy;
import org.mule.runtime.core.internal.message.InternalEvent;
import org.mule.runtime.core.internal.processor.chain.SubflowMessageProcessorChainBuilder;
import org.mule.runtime.core.internal.rx.FluxSinkRecorder;
import org.mule.runtime.core.privileged.processor.chain.MessageProcessorChainBuilder;
import org.mule.runtime.core.privileged.routing.RoutePathNotFoundException;
import org.mule.runtime.dsl.api.component.AbstractComponentFactory;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.UncheckedExecutionException;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.xml.namespace.QName;

import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class FlowRefFactoryBean extends AbstractComponentFactory<Processor> implements ApplicationContextAware {

  private static final Logger LOGGER = getLogger(FlowRefFactoryBean.class);

  private String refName;
  private String target;
  private String targetValue = "#[payload]";

  private ApplicationContext applicationContext;

  @Inject
  private MuleContext muleContext;

  @Inject
  private ExtendedExpressionManager expressionManager;

  @Inject
  private ConfigurationComponentLocator locator;

  public void setName(String name) {
    this.refName = name;
  }

  /**
   * The variable where the result from this router should be stored. If this is not set then the result is set in the payload.
   *
   * @param target a variable name.
   */
  public void setTarget(String target) {
    this.target = target;
  }

  /**
   * Defines the target value expression
   *
   * @param targetValue the target value expression
   */
  public void setTargetValue(String targetValue) {
    this.targetValue = targetValue;
  }

  @Override
  public Processor doGetObject() throws Exception {
    if (refName.isEmpty()) {
      throw new IllegalArgumentException("flow-ref name is empty");
    }

    if (expressionManager.isExpression(refName)) {
      return new DynamicFlowRefMessageProcessor(this);
    } else {
      return new StaticFlowRefMessageProcessor(this);
    }
  }

  protected Processor getReferencedFlow(String name, FlowRefMessageProcessor flowRefMessageProcessor) throws MuleException {
    if (name == null) {
      throw new RoutePathNotFoundException(createStaticMessage("flow-ref name expression returned 'null'"),
                                           flowRefMessageProcessor);
    }

    Component referencedFlow = getReferencedProcessor(name);
    if (referencedFlow == null) {
      throw new RoutePathNotFoundException(createStaticMessage("No flow/sub-flow with name '%s' found", name),
                                           flowRefMessageProcessor);
    }

    // for subflows, we create a new one so it must be initialised manually
    if (!(referencedFlow instanceof Flow)) {
      if (referencedFlow instanceof SubflowMessageProcessorChainBuilder) {
        MessageProcessorChainBuilder chainBuilder = (MessageProcessorChainBuilder) referencedFlow;

        locator.find(flowRefMessageProcessor.getRootContainerLocation()).filter(c -> c instanceof Flow).map(c -> (Flow) c)
            .ifPresent(f -> {
              ProcessingStrategy callerFlowPs = f.getProcessingStrategy();
              chainBuilder.setProcessingStrategy(new ProcessingStrategy() {

                @Override
                public Sink createSink(FlowConstruct flowConstruct, ReactiveProcessor pipeline) {
                  return callerFlowPs.createSink(flowConstruct, pipeline);
                }

                @Override
                public ReactiveProcessor onPipeline(ReactiveProcessor pipeline) {
                  // Do not make any change in `onPipeline`, so it emulates the behavior of copy/pasting the content of the
                  // sub-flow into the caller flow, without applying any additional logic.
                  return pipeline;
                }

                @Override
                public ReactiveProcessor onProcessor(ReactiveProcessor processor) {
                  return callerFlowPs.onProcessor(processor);
                }
              });
            });

        referencedFlow = chainBuilder.build();
      }
      initialiseIfNeeded(referencedFlow, muleContext);

      Map<QName, Object> annotations = new HashMap<>(referencedFlow.getAnnotations());
      annotations.put(ROOT_CONTAINER_NAME_KEY, getRootContainerLocation().toString());
      referencedFlow.setAnnotations(annotations);
      startIfNeeded(referencedFlow);
    }

    return (Processor) referencedFlow;
  }

  private Component getReferencedProcessor(String name) {
    if (applicationContext instanceof MuleArtifactContext) {
      MuleArtifactContext muleArtifactContext = (MuleArtifactContext) applicationContext;

      try {
        if (muleArtifactContext.getBeanFactory().getBeanDefinition(name).isPrototype()) {
          muleArtifactContext.getPrototypeBeanWithRootContainer(name, getRootContainerLocation().toString());
        }
      } catch (NoSuchBeanDefinitionException e) {
        // Null is handled by the caller method
        return null;
      }
    }
    return (Component) applicationContext.getBean(name);
  }

  @Override
  public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
    this.applicationContext = applicationContext;
  }

  private class StaticFlowRefMessageProcessor extends FlowRefMessageProcessor {

    protected StaticFlowRefMessageProcessor(FlowRefFactoryBean owner) {
      super(owner);
    }

    private final LazyValue<ReactiveProcessor> resolvedReferencedProcessorSupplier = new LazyValue<>(() -> {
      try {
        return getReferencedFlow(refName, StaticFlowRefMessageProcessor.this);
      } catch (MuleException e) {
        throw new MuleRuntimeException(e);
      }
    });

    @Override
    public Publisher<CoreEvent> apply(Publisher<CoreEvent> publisher) {
      final ReactiveProcessor resolvedReferencedProcessor = resolvedReferencedProcessorSupplier.get();

      Flux<CoreEvent> pub = from(publisher);

      if ((target != null)) {
        pub = pub.map(event -> quickCopy(event, singletonMap(originalEventKey(event), event)))
            .cast(CoreEvent.class);
      }

      if (resolvedReferencedProcessor instanceof Flow) {
        pub = pub.transform(eventPub -> from(applyWithChildContext(eventPub, ((Flow) resolvedReferencedProcessor).referenced(),
                                                                   ofNullable(getLocation()),
                                                                   ((Flow) resolvedReferencedProcessor).getExceptionListener())));
      } else {
        pub = pub.transform(eventPub -> from(applyWithChildContext(eventPub, resolvedReferencedProcessor,
                                                                   ofNullable(getLocation()))));
      }

      return (target != null)
          ? pub.map(eventAfter -> outputToTarget(((InternalEvent) eventAfter)
              .getInternalParameter(originalEventKey(eventAfter)), target, targetValue,
                                                 expressionManager).apply(eventAfter))
          : pub;
    }

    protected String originalEventKey(CoreEvent event) {
      return "flowRef.originalEvent." + event.getContext().getId();
    }

    @Override
    public void stop() throws MuleException {
      resolvedReferencedProcessorSupplier.ifComputed(resolved -> {
        if (!(resolved instanceof Flow)) {
          try {
            stopIfNeeded(resolved);
          } catch (MuleException e) {
            throw new MuleRuntimeException(e);
          }
        }
      });
    }

    @Override
    public void dispose() {
      resolvedReferencedProcessorSupplier.ifComputed(resolved -> {
        if (!(resolved instanceof Flow)) {
          disposeIfNeeded(resolved, LOGGER);
        }
      });
    }
  }

  private class SinkAndPublisherPair {

    private final Publisher<CoreEvent> publisher;
    private final FluxSinkRecorder recorder;

    SinkAndPublisherPair(Processor processor) {
      recorder = new FluxSinkRecorder();
      publisher = applyWithChildContext(Flux.create(recorder), processor, ofNullable(getLocation()));
    }

    public void execute(CoreEvent event) {
      recorder.next(event);
    }

    public void complete() {
      recorder.complete();
    }

    public Publisher<CoreEvent> getPublisher() {
      return publisher;
    }
  }

  private class DynamicFlowRefMessageProcessor extends FlowRefMessageProcessor {

    private LoadingCache<String, Processor> cache;

    public DynamicFlowRefMessageProcessor(FlowRefFactoryBean owner) {
      super(owner);
      this.cache = CacheBuilder.newBuilder()
          .maximumSize(20)
          .build(new CacheLoader<String, Processor>() {

            @Override
            public Processor load(String key) throws Exception {
              return getReferencedFlow(key, DynamicFlowRefMessageProcessor.this);
            }
          });
    }

    @Override
    public Publisher<CoreEvent> apply(Publisher<CoreEvent> publisher) {
      // TODO: This is a horrible way of looking for the registered subFlows. CHANGE ME!
      List<String> subFlowNames = Arrays
          .asList(FlowRefFactoryBean.this.applicationContext.getBeanNamesForType(SubflowMessageProcessorChainFactoryBean.class))
          .stream()
          .map(aName -> aName.startsWith("&") ? aName.substring(1) : aName)
          .collect(toList());

      Map<String, SinkAndPublisherPair> subFlowRouter = new HashMap<>();

      subFlowNames.stream().forEach(subFlowName -> {
        try {
          Processor subFlowProcessor = getReferencedFlow(subFlowName, this);
          subFlowRouter.put(subFlowName, new SinkAndPublisherPair(subFlowProcessor));
        } catch (MuleException e) {
          e.printStackTrace();
        }
      });

      return from(publisher).flatMap(event -> {
        ReactiveProcessor resolvedReferencedProcessor;
        String resolvedName = resolveTargetFlowName(event);

        if (subFlowRouter.keySet().contains(resolvedName)) {
          SinkAndPublisherPair resolvedPair = subFlowRouter.get(resolvedName);
          resolvedPair.execute(event);
          return resolvedPair.getPublisher();
        }

        try {
          resolvedReferencedProcessor = resolveReferencedProcessor(resolvedName);
        } catch (MuleException e) {
          return error(e);
        }

        Mono<CoreEvent> pub;
        if (resolvedReferencedProcessor instanceof Flow) {
          pub = Mono.from(processWithChildContext(event, ((Flow) resolvedReferencedProcessor).referenced(),
                                                  ofNullable(getLocation()),
                                                  ((Flow) resolvedReferencedProcessor).getExceptionListener()));
        } else {
          pub = Mono.from(processWithChildContext(event, resolvedReferencedProcessor,
                                                  ofNullable(getLocation())));
        }

        return (target != null)
            ? pub.map(outputToTarget(event, target, targetValue, expressionManager))
            : pub;
      }).doOnComplete(() -> subFlowRouter.values().stream().forEach(sinkAndPublisherPair -> sinkAndPublisherPair.complete()));
    }

    protected String resolveTargetFlowName(CoreEvent event) {
      return (String) expressionManager.evaluate(refName, event, getLocation()).getValue();
    }

    protected Processor resolveReferencedProcessor(String targetFlowName) throws MuleException {
      try {
        return cache.getUnchecked(targetFlowName);

      } catch (UncheckedExecutionException e) {
        if (e.getCause() instanceof MuleRuntimeException) {
          throw (MuleRuntimeException) e.getCause();
        } else if (e.getCause() instanceof MuleException) {
          throw (MuleException) e.getCause();
        } else {
          throw e;
        }
      }
    }

    @Override
    public void stop() throws MuleException {
      for (Processor p : cache.asMap().values()) {
        if (!(p instanceof Flow)) {
          stopIfNeeded(p);
        }
      }
    }

    @Override
    public void dispose() {
      for (Processor p : cache.asMap().values()) {
        if (!(p instanceof Flow)) {
          disposeIfNeeded(p, LOGGER);
        }
      }
      cache.invalidateAll();
      cache.cleanUp();
    }

  }
}
