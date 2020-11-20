/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.config.internal.validation;

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertThat;
import static org.mule.test.allure.AllureConstants.MuleDsl.MULE_DSL;
import static org.mule.test.allure.AllureConstants.MuleDsl.DslValidationStory.DSL_VALIDATION_STORY;

import org.mule.runtime.ast.api.validation.Validation;
import org.mule.tck.size.SmallTest;

import java.util.Optional;

import org.junit.Test;

import io.qameta.allure.Feature;
import io.qameta.allure.Story;

@SmallTest
@Feature(MULE_DSL)
@Story(DSL_VALIDATION_STORY)
public class NameHasValidCharactersTestCase extends AbstractCoreValidationTestCase {

  @Override
  protected Validation getValidation() {
    return new NameHasValidCharacters();
  }

  @Test
  public void flowNameUsingInvalidCharacter() {
    final Optional<String> msg = runValidation("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
        "<mule xmlns=\"http://www.mulesoft.org/schema/mule/core\"\n" +
        "      xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
        "      xsi:schemaLocation=\"\n" +
        "       http://www.mulesoft.org/schema/mule/core http://www.mulesoft.org/schema/mule/core/current/mule.xsd\">\n" +
        "\n" +
        "    <flow name=\"flow/myFlow\">\n" +
        "        <logger/>\n" +
        "        <error-handler>\n" +
        "            <on-error-continue/>\n" +
        "            <on-error-continue/>\n" +
        "        </error-handler>\n" +
        "    </flow>\n" +
        "\n" +
        "</mule>");

    assertThat(msg.get(),
               containsString("Invalid global element name 'flow/myFlow'. Problem is: Invalid character used in location. Invalid characters are /,[,],{,},#"));
  }
}