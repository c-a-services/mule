/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.config.internal.factories;

import static java.lang.String.format;
import static java.util.Collections.emptyMap;
import static org.mule.runtime.core.privileged.processor.MessageProcessors.getProcessingStrategy;
import static org.mule.runtime.core.privileged.processor.chain.DefaultMessageProcessorChainBuilder.newLazyProcessorChainBuilder;

import org.mule.runtime.api.component.location.ConfigurationComponentLocator;
import org.mule.runtime.api.meta.model.ExtensionModel;
import org.mule.runtime.api.meta.model.operation.OperationModel;
import org.mule.runtime.core.api.processor.Processor;
import org.mule.runtime.core.internal.processor.chain.ModuleOperationMessageProcessorChainBuilder;
import org.mule.runtime.core.privileged.processor.chain.MessageProcessorChain;
import org.mule.runtime.core.privileged.processor.chain.MessageProcessorChainBuilder;
import org.mule.runtime.core.privileged.processor.objectfactory.MessageProcessorChainObjectFactory;

import java.util.Map;

import javax.inject.Inject;

public class ModuleOperationMessageProcessorChainFactoryBean extends MessageProcessorChainObjectFactory {

  private Map<String, String> properties = emptyMap();
  private Map<String, String> parameters = emptyMap();

  private ExtensionModel extensionModel;
  private OperationModel operationModel;

  @Inject
  protected ConfigurationComponentLocator locator;

  @Override
  public MessageProcessorChain doGetObject() throws Exception {
    MessageProcessorChainBuilder builder = getBuilderInstance();
    for (Object processor : processors) {
      if (processor instanceof Processor) {
        builder.chain((Processor) processor);
      } else {
        throw new IllegalArgumentException(format("MessageProcessorBuilder should only have MessageProcessor's or MessageProcessorBuilder's configured. Found a %s",
                                                  processor.getClass().getName()));
      }
    }
    final MessageProcessorChain messageProcessorChain =
        newLazyProcessorChainBuilder((ModuleOperationMessageProcessorChainBuilder) builder,
                                     muleContext,
                                     () -> getProcessingStrategy(locator, getRootContainerLocation()).orElse(null));
    messageProcessorChain.setAnnotations(getAnnotations());
    messageProcessorChain.setMuleContext(muleContext);
    return messageProcessorChain;
  }

  @Override
  protected MessageProcessorChainBuilder getBuilderInstance() {
    return new ModuleOperationMessageProcessorChainBuilder(properties, parameters, extensionModel,
                                                           operationModel,
                                                           muleContext.getExpressionManager());
  }

  public void setProperties(Map<String, String> properties) {
    this.properties = properties;
  }

  public void setParameters(Map<String, String> parameters) {
    this.parameters = parameters;
  }

  public void setExtensionModel(ExtensionModel extensionModel) {
    this.extensionModel = extensionModel;
  }

  public void setOperationModel(OperationModel operationModel) {
    this.operationModel = operationModel;
  }
}
