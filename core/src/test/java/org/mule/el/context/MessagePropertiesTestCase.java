/*
 * $Id$
 * --------------------------------------------------------------------------------------
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.el.context;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.mule.DefaultMuleMessage;
import org.mule.api.MuleMessage;
import org.mule.api.transport.PropertyScope;

import java.util.Map;

import org.junit.Test;

public class MessagePropertiesTestCase extends AbstractELTestCase
{
    public MessagePropertiesTestCase(Variant variant)
    {
        super(variant);
    }

    @Test
    public void inboundPropertyMap() throws Exception
    {
        MuleMessage message = new DefaultMuleMessage("", muleContext);
        message.setProperty("foo", "bar", PropertyScope.INBOUND);
        assertTrue(evaluate("message.inboundProperties", message) instanceof Map);
    }

    @Test
    public void assignToInboundPropertyMap() throws Exception
    {
        MuleMessage message = new DefaultMuleMessage("", muleContext);
        assertFinalProperty("message.inboundProperties='foo'", message);
    }

    @Test
    public void inboundProperty() throws Exception
    {
        MuleMessage message = new DefaultMuleMessage("", muleContext);
        message.setProperty("foo", "bar", PropertyScope.INBOUND);
        assertEquals("bar", evaluate("message.inboundProperties['foo']", message));
    }

    @Test
    public void assignValueToInboundProperty() throws Exception
    {
        MuleMessage message = new DefaultMuleMessage("", muleContext);
        message.setProperty("foo", "bar", PropertyScope.INBOUND);
        assertUnsupportedOperation("message.inboundProperties['foo']='bar'", message);
    }

    @Test
    public void assignValueToNewInboundProperty() throws Exception
    {
        MuleMessage message = new DefaultMuleMessage("", muleContext);
        assertUnsupportedOperation("message.inboundProperties['foo_new']='bar'", message);
    }

    @Test
    public void outboundPropertyMap() throws Exception
    {
        MuleMessage message = new DefaultMuleMessage("", muleContext);
        message.setProperty("foo", "bar", PropertyScope.OUTBOUND);
        assertTrue(evaluate("message.outboundProperties", message) instanceof Map);
    }

    @Test
    public void assignToOutboundPropertyMap() throws Exception
    {
        MuleMessage message = new DefaultMuleMessage("", muleContext);
        assertFinalProperty("message.outboundProperties='foo'", message);
    }

    @Test
    public void outboundProperty() throws Exception
    {
        MuleMessage message = new DefaultMuleMessage("", muleContext);
        message.setOutboundProperty("foo", "bar");
        assertEquals("bar", evaluate("message.outboundProperties['foo']", message));
    }

    @Test
    public void assignValueToOutboundProperty() throws Exception
    {
        MuleMessage message = new DefaultMuleMessage("", muleContext);
        message.setOutboundProperty("foo", "bar_old");
        evaluate("message.outboundProperties['foo']='bar'", message);
        assertEquals("bar", message.getOutboundProperty("foo"));
    }

    @Test
    public void assignValueToNewOutboundProperty() throws Exception
    {
        MuleMessage message = new DefaultMuleMessage("", muleContext);
        evaluate("message.outboundProperties['foo']='bar'", message);
        assertEquals("bar", message.getOutboundProperty("foo"));
    }

}
