/*
 * #%L
 * wcm.io
 * %%
 * Copyright (C) 2017 wcm.io
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
package io.wcm.devops.conga.plugins.aem.maven;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.CharEncoding;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.yaml.snakeyaml.Yaml;

import io.wcm.tooling.commons.packmgr.install.PackageFile;
import io.wcm.tooling.commons.packmgr.install.PackageInstaller;

/**
 * Installs all AEM content packages to AEM which are referenced in a model.yaml generated by CONGA for a node.
 */
@Mojo(name = "package-install", threadSafe = true, requiresProject = false)
public final class InstallPackagesMojo extends AbstractContentPackageMojo {

  private static final String MODEL_FILE = "model.yaml";

  /**
   * Directory with the generated CONGA configuration containing the model.yaml.
   */
  @Parameter(required = true, property = "conga.nodeDirectory")
  private File nodeDirectory;

  /**
   * Whether to install (unpack) the uploaded package automatically or not.
   */
  @Parameter(property = "vault.install", defaultValue = "true")
  private boolean install;

  /**
   * Force upload and install of content package. If set to:
   * <ul>
   * <li><code>true</code>: Package is always installed, even if it was already uploaded before.</li>
   * <li><code>false</code>: Package is only installed if it was not already uploade before.</li>
   * <li>nothing (default): Force is applied to packages with the string "-SNAPSHOT" in it's filename.</li>
   * </ul>
   */
  @Parameter(property = "vault.force")
  private Boolean force;

  /**
   * If set to true nested packages get installed as well.
   */
  @Parameter(property = "vault.recursive", defaultValue = "true")
  private boolean recursive;

  /**
   * Delay further steps after package installation by this amount of seconds
   */
  @Parameter(property = "vault.delayAfterInstallSec")
  private Integer delayAfterInstallSec;

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    if (isSkip()) {
      return;
    }

    if (!nodeDirectory.exists() || !nodeDirectory.isDirectory()) {
      throw new MojoFailureException("Node directory not found: " + getCanonicalPath(nodeDirectory));
    }
    File modelFile = new File(nodeDirectory, MODEL_FILE);
    if (!modelFile.exists() || !modelFile.isFile()) {
      throw new MojoFailureException("Model file not found: " + getCanonicalPath(modelFile));
    }

    getLog().info("Get AEM content packages from " + getCanonicalPath(modelFile));
    Map<String, Object> data = parseYaml(modelFile);
    List<PackageFile> items = collectPackagesForNode(data, nodeDirectory);

    if (items.isEmpty()) {
      getLog().warn("No file found for installing.");
    }
    else {
      PackageInstaller installer = new PackageInstaller(getPackageManagerProperties(), getLoggerWrapper());
      installer.installFiles(items);
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> parseYaml(File modelFile) {
    try {
      try (InputStream is = new FileInputStream(modelFile);
          Reader reader = new InputStreamReader(is, CharEncoding.UTF_8)) {
        Yaml yaml = YamlUtil.createYaml();
        return yaml.loadAs(reader, Map.class);
      }
    }
    catch (IOException ex) {
      throw new RuntimeException("Unable to parse " + getCanonicalPath(modelFile), ex);
    }
  }

  @SuppressWarnings("unchecked")
  private List<PackageFile> collectPackagesForNode(Map<String, Object> data, File parentDir) {
    List<PackageFile> items = new ArrayList<>();

    List<Map<String, Object>> roles = (List<Map<String, Object>>)data.get("roles");
    for (Map<String, Object> role : roles) {
      List<Map<String, Object>> files = (List<Map<String, Object>>)role.get("files");
      for (Map<String, Object> file : files) {
        if (file.get("aemContentPackageProperties") != null) {
          String path = (String)file.get("path");
          Boolean itemInstall = (Boolean)file.get("install");
          Boolean itemForce = (Boolean)file.get("force");
          Boolean itemRecursive = (Boolean)file.get("recursive");
          Integer itemDelayAfterInstallSec = (Integer)file.get("delayAfterInstallSec");
          Integer httpSocketTimeoutSec = (Integer)file.get("httpSocketTimeoutSec");

          File packageFile = new File(parentDir, path);
          items.add(toPackageFile(packageFile, itemInstall, itemForce, itemRecursive, itemDelayAfterInstallSec, httpSocketTimeoutSec));
        }
      }
    }

    return items;
  }

  private PackageFile toPackageFile(File file,
      Boolean itemInstall, Boolean itemForce, Boolean itemRecursive,
      Integer itemDelayAfterInstallSec, Integer httpSocketTimeoutSec) {
    PackageFile output = new PackageFile();

    output.setFile(file);
    if (itemInstall != null) {
      output.setInstall(itemInstall);
    }
    else {
      output.setInstall(this.install);
    }
    if (itemForce != null) {
      output.setForce(itemForce);
    }
    else {
      output.setForce(this.force);
    }
    if (itemRecursive != null) {
      output.setRecursive(itemRecursive);
    }
    else {
      output.setRecursive(this.recursive);
    }
    if (itemDelayAfterInstallSec != null) {
      output.setDelayAfterInstallSec(itemDelayAfterInstallSec);
    }
    else if (this.delayAfterInstallSec != null) {
      output.setDelayAfterInstallSec(this.delayAfterInstallSec);
    }
    else {
      output.setDelayAfterInstallSecAutoDetect();
    }
    output.setHttpSocketTimeoutSec(httpSocketTimeoutSec);

    return output;
  }

  private String getCanonicalPath(File file) {
    try {
      return file.getCanonicalPath();
    }
    catch (IOException ex) {
      throw new RuntimeException(ex);
    }
  }

}
