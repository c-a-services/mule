/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.extension.tooling.internal;

import static java.lang.System.clearProperty;
import static java.lang.System.setProperty;
import static java.util.Collections.emptyList;
import static org.mule.runtime.api.i18n.I18nMessageFactory.createStaticMessage;
import static org.mule.runtime.core.api.config.MuleDeploymentProperties.MULE_LAZY_INIT_DEPLOYMENT_PROPERTY;
import static org.mule.runtime.core.api.config.MuleDeploymentProperties.MULE_LAZY_INIT_ENABLE_XML_VALIDATIONS_DEPLOYMENT_PROPERTY;
import static org.mule.runtime.core.api.util.ClassUtils.withContextClassLoader;
import static org.mule.runtime.module.extension.tooling.internal.util.SdkToolingUtils.stopAndDispose;

import org.mule.runtime.api.connection.ConnectionValidationResult;
import org.mule.runtime.api.exception.MuleException;
import org.mule.runtime.api.exception.MuleRuntimeException;
import org.mule.runtime.api.meta.model.ExtensionModel;
import org.mule.runtime.api.meta.model.connection.ConnectionProviderModel;
import org.mule.runtime.api.service.Service;
import org.mule.runtime.core.api.MuleContext;
import org.mule.runtime.module.extension.tooling.api.SdkToolingExecutor;
import org.mule.runtime.module.extension.tooling.internal.command.SdkToolingCommand;
import org.mule.runtime.module.extension.tooling.internal.command.SdkToolingContext;
import org.mule.runtime.module.extension.tooling.internal.command.connectivity.ConnectivityTestCommand;
import org.mule.runtime.module.extension.tooling.internal.util.bootstrap.ToolingMuleContextFactory;

import java.util.List;
import java.util.Map;

public class DefaultSdkToolingExecutor implements SdkToolingExecutor {

  private final ToolingMuleContextFactory factory = new ToolingMuleContextFactory();
  private final List<Service> services;

  public DefaultSdkToolingExecutor(List<Service> services) {
    this.services = services;
  }

  public DefaultSdkToolingExecutor() {
    this(emptyList());
  }

  @Override
  public ConnectionValidationResult testConnectivity(ExtensionModel extensionModel,
                                                     ConnectionProviderModel connectionProviderModel,
                                                     ClassLoader classLoader,
                                                     Map<String, Object> params) {

    final MuleContext muleContext = createMuleContext();
    muleContext.getExtensionManager().registerExtension(extensionModel);

    SdkToolingContext ctx = new ImmutableSdkToolingContext(extensionModel, params, muleContext, classLoader);
    ConnectivityTestCommand cmd = new ConnectivityTestCommand(connectionProviderModel);

    return doExecute(cmd, ctx);
  }

  private <T> T doExecute(SdkToolingCommand<T> command, SdkToolingContext context) {
    setProperty(MULE_LAZY_INIT_DEPLOYMENT_PROPERTY, "true");
    setProperty(MULE_LAZY_INIT_ENABLE_XML_VALIDATIONS_DEPLOYMENT_PROPERTY, "false");

    try {
      return withContextClassLoader(context.getClassLoader(), () -> {
        try {
          return command.execute(context);
        } catch (Exception e) {
          //TODO: Guille, should we throw a specific tooling exception her?
          throw new MuleRuntimeException(e);
        } finally {
          stopAndDispose(context.getMuleContext());
        }
      });
    } finally {
      clearProperty(MULE_LAZY_INIT_DEPLOYMENT_PROPERTY);
      clearProperty(MULE_LAZY_INIT_ENABLE_XML_VALIDATIONS_DEPLOYMENT_PROPERTY);
    }
  }

  private MuleContext createMuleContext() {
    try {
      return factory.createMuleContext(services);
    } catch (MuleException e) {
      throw new MuleRuntimeException(createStaticMessage("Could not create tooling mule context"), e);
    }
  }
}
