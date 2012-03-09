/*
 * $Id$
 * --------------------------------------------------------------------------------------
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.api.el.mvel;

import org.mvel2.integration.VariableResolverFactory;

public interface MuleVariableResolverFactory extends VariableResolverFactory
{

    void addVariable(String name, Object value);

    void addFinalVariable(String name, Object value);

    <T> T getVariable(String name);

    boolean isResolveable(String name);

}
