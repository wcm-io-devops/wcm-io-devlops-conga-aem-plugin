/*
 * #%L
 * wcm.io
 * %%
 * Copyright (C) 2015 wcm.io
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package io.wcm.devops.conga.plugins.aem.postprocessor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Map;

import org.apache.commons.lang3.CharEncoding;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import io.wcm.devops.conga.generator.spi.PostProcessorPlugin;
import io.wcm.devops.conga.generator.spi.context.FileContext;
import io.wcm.devops.conga.generator.spi.context.PostProcessorContext;
import io.wcm.devops.conga.generator.util.PluginManager;
import io.wcm.devops.conga.plugins.sling.postprocessor.ProvisioningOsgiConfigPostProcessor;

public class ContentPackagePropertiesPostProcessorTest {

  private PostProcessorPlugin underTest;

  @Before
  public void setUp() {
    underTest = new PluginManager().get(ContentPackagePropertiesPostProcessor.NAME, PostProcessorPlugin.class);
  }

  @SuppressWarnings("unchecked")
  @Test
  public void testPostProcess() throws Exception {

    File contentPackageFile = new File("src/test/resources/package/example.zip");

    // post-process
    FileContext fileContext = new FileContext()
        .file(contentPackageFile)
        .charset(CharEncoding.UTF_8);
    PostProcessorContext context = new PostProcessorContext()
        .pluginManager(new PluginManager())
        .logger(LoggerFactory.getLogger(ProvisioningOsgiConfigPostProcessor.class));

    assertTrue(underTest.accepts(fileContext, context));
    underTest.apply(fileContext, context);

    // validate
    Map<String, Object> props = (Map<String, Object>)fileContext.getModelOptions().get("packageProperties");
    assertEquals("mapping-sample", props.get("name"));
    assertEquals(false, props.get("requiresRoot"));
    assertEquals(2, props.get("packageFormatVersion"));
  }

}