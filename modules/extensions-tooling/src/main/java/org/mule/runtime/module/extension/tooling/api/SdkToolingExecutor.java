/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.extension.tooling.api;

import org.mule.runtime.api.connection.ConnectionValidationResult;
import org.mule.runtime.api.meta.model.ExtensionModel;
import org.mule.runtime.api.meta.model.connection.ConnectionProviderModel;

import java.util.Map;

public interface SdkToolingExecutor {

  ConnectionValidationResult testConnectivity(ExtensionModel extensionModel,
                                              ConnectionProviderModel connectionProviderModel,
                                              ClassLoader classLoader,
                                              Map<String, Object> params);


}
