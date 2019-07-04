/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.extension.tooling.internal.util.bootstrap;

import static org.mule.runtime.core.api.config.MuleProperties.OBJECT_EXTENSION_MANAGER;
import static org.mule.runtime.core.api.util.StringUtils.isEmpty;

import org.mule.runtime.api.exception.MuleException;
import org.mule.runtime.api.exception.MuleRuntimeException;
import org.mule.runtime.api.scheduler.SchedulerService;
import org.mule.runtime.api.service.Service;
import org.mule.runtime.core.api.MuleContext;
import org.mule.runtime.core.api.config.ConfigurationBuilder;
import org.mule.runtime.core.api.config.builders.AbstractConfigurationBuilder;
import org.mule.runtime.core.api.context.DefaultMuleContextFactory;
import org.mule.runtime.core.api.extension.ExtensionManager;
import org.mule.runtime.core.internal.context.DefaultMuleContext;
import org.mule.runtime.core.internal.context.MuleContextWithRegistry;
import org.mule.runtime.core.internal.registry.MuleRegistry;
import org.mule.runtime.core.privileged.registry.RegistrationException;
import org.mule.runtime.module.extension.internal.manager.DefaultExtensionManager;
import org.mule.runtime.module.extension.tooling.internal.service.scheduler.ToolingSchedulerService;

import java.util.List;

public class ToolingMuleContextFactory {

  public MuleContext createMuleContext(List<Service> services) throws MuleException {
    return createMuleContext(true, services);
  }

  public MuleContext createMuleContext(boolean start, List<Service> services) throws MuleException {
    MuleContext muleContext = new DefaultMuleContextFactory().createMuleContext(getConfigurationBuilders(services));

    if (start) {
      muleContext.start();
    }

    return muleContext;
  }

  private ConfigurationBuilder[] getConfigurationBuilders(List<Service> services) {
    return new ConfigurationBuilder[] {
        new ToolingConfigurationBuilder(),
        getServicesConfigurationBuilder(services),
        getExtensionManagerConfigurationBuilder()
    };
  }

  private ConfigurationBuilder getExtensionManagerConfigurationBuilder() {
    return new AbstractConfigurationBuilder() {

      @Override
      protected void doConfigure(MuleContext muleContext) throws Exception {
        ExtensionManager extensionManager = new DefaultExtensionManager();
        DefaultMuleContext ctx = (DefaultMuleContext) muleContext;
        ctx.setExtensionManager(extensionManager);
        ctx.getRegistry().registerObject(OBJECT_EXTENSION_MANAGER, extensionManager);
      }
    };
  }

  private ConfigurationBuilder getServicesConfigurationBuilder(List<Service> services) {
    return new AbstractConfigurationBuilder() {

      @Override
      protected void doConfigure(MuleContext muleContext) {
        MuleRegistry registry = ((MuleContextWithRegistry) muleContext).getRegistry();
        SchedulerService schedulerService = new ToolingSchedulerService();
        registerObject(registry, schedulerService);
        services.stream().forEach(service -> registerObject(registry, service));
      }

      private void registerObject(MuleRegistry registry, Service service) {
        try {
          registry.registerObject(getServiceId(service), service);
        } catch (RegistrationException e) {
          throw new MuleRuntimeException(e);
        }
      }
    };
  }

  private <T extends Service> String getServiceId(T service) {
    String name = service.getName();
    String contract = service.getContractName();
    if (!isEmpty(contract)) {
      name += " - " + contract;
    }
    return name;
  }

}
