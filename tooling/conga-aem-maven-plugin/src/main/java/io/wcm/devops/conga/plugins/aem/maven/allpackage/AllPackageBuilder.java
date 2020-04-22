/*
 * #%L
 * wcm.io
 * %%
 * Copyright (C) 2020 wcm.io
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
package io.wcm.devops.conga.plugins.aem.maven.allpackage;

import static io.wcm.devops.conga.generator.util.FileUtil.getCanonicalPath;
import static io.wcm.devops.conga.plugins.aem.maven.allpackage.RunModeUtil.RUNMODE_AUTHOR;
import static io.wcm.devops.conga.plugins.aem.maven.allpackage.RunModeUtil.RUNMODE_PUBLISH;
import static org.apache.jackrabbit.vault.packaging.PackageProperties.NAME_DEPENDENCIES;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.jackrabbit.vault.packaging.Dependency;
import org.apache.jackrabbit.vault.packaging.DependencyUtil;
import org.apache.jackrabbit.vault.packaging.VersionRange;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugin.logging.SystemStreamLog;

import com.google.common.collect.ImmutableSet;

import io.wcm.devops.conga.plugins.aem.maven.model.ContentPackageFile;
import io.wcm.tooling.commons.contentpackagebuilder.ContentPackage;
import io.wcm.tooling.commons.contentpackagebuilder.ContentPackageBuilder;
import io.wcm.tooling.commons.contentpackagebuilder.PackageFilter;

/**
 * Builds "all" package based on given set of content packages.
 */
public final class AllPackageBuilder {

  private final File targetFile;
  private final String groupName;
  private final String packageName;
  private boolean autoDependencies;
  private boolean autoDependenciesSeparateMutable;
  private Log log;

  private static final String RUNMODE_DEFAULT = "$default$";
  private static final Set<String> ALLOWED_PACKAGE_TYPES = ImmutableSet.of("application", "container", "content");

  /**
   * @param targetFile Target file
   * @param groupName Group name
   * @param packageName Package name
   */
  public AllPackageBuilder(File targetFile, String groupName, String packageName) {
    this.targetFile = targetFile;
    this.groupName = groupName;
    this.packageName = packageName;
  }

  /**
   * @param value Automatically generate dependencies between content packages based on file order in CONGA
   *          configuration.
   * @return this
   */
  public AllPackageBuilder autoDependencies(boolean value) {
    this.autoDependencies = value;
    return this;
  }

  /**
   * @param value Use separate dependency chains for mutable and immutable packages.
   * @return this
   */
  public AllPackageBuilder autoDependenciesSeparateMutable(boolean value) {
    this.autoDependenciesSeparateMutable = value;
    return this;
  }

  /**
   * @param value Maven logger
   * @return this
   */
  public AllPackageBuilder logger(Log value) {
    this.log = value;
    return this;
  }

  private Log getLog() {
    if (this.log == null) {
      this.log = new SystemStreamLog();
    }
    return this.log;
  }

  /**
   * Build "all" content package.
   * @param contentPackages Content packages (invalid will be filtered out)
   * @param cloudManagerTarget Target environments/run modes the packages should be attached to
   * @return true if "all" package was generated, false if not valid package was found.
   * @throws IOException I/O exception
   */
  public boolean build(List<ContentPackageFile> contentPackages, Set<String> cloudManagerTarget) throws IOException {

    // collect list of cloud manager environment run modes
    List<String> environmentRunmodes = new ArrayList<>();
    if (cloudManagerTarget.isEmpty()) {
      environmentRunmodes.add(RUNMODE_DEFAULT);
    }
    else {
      environmentRunmodes.addAll(cloudManagerTarget);
    }

    // generate warnings for each invalid content packages that is skipped
    contentPackages.stream()
        .filter(pkg -> !hasPackageType(pkg))
        .forEach(pkg -> getLog().warn("Skipping content package without package type: " + getCanonicalPath(pkg.getFile())));

    // fail build if content packages with non-allowed package types exist
    List<ContentPackageFile> invalidPackageTypeContentPackages = contentPackages.stream()
        .filter(AllPackageBuilder::hasPackageType)
        .filter(pkg -> !isValidPackageType(pkg))
        .collect(Collectors.toList());
    if (!invalidPackageTypeContentPackages.isEmpty()) {
      throw new IOException("Content packages found with unsupported package types: " +
          invalidPackageTypeContentPackages.stream()
              .map(pkg -> pkg.getName() + " -> " + pkg.getPackageType())
              .collect(Collectors.joining(", ")));
    }

    // collect AEM content packages for this node
    List<ContentPackageFile> validContentPackages = contentPackages.stream()
        .filter(AllPackageBuilder::hasPackageType)
        .collect(Collectors.toList());
    if (validContentPackages.isEmpty()) {
      return false;
    }

    // prepare content package metadata
    ContentPackageBuilder builder = new ContentPackageBuilder()
        .group(groupName)
        .name(packageName)
        .packageType("container");

    // define root path for "all" package
    String rootPath = buildRootPath(groupName, packageName);
    builder.filter(new PackageFilter(rootPath));

    // build content package
    // if auto dependencies is active: build separate "dependency chains" between mutable and immutable packages
    try (ContentPackage contentPackage = builder.build(targetFile)) {
      for (String environmentRunmode : environmentRunmodes) {
        List<ContentPackageFile> previousPackages = new ArrayList<>();
        for (ContentPackageFile pkg : validContentPackages) {
          String path = buildPackagePath(pkg, rootPath, environmentRunmode);

          // get last previous package
          // if autoDependenciesSeparateMutable active only that of the same mutability type
          ContentPackageFile previousPkg = previousPackages.stream()
              .filter(item -> !autoDependenciesSeparateMutable || mutableMatches(item, pkg))
              .reduce((first, second) -> second)
              .orElse(null);

          if (autoDependencies && previousPkg != null) {
            // wire previous package in package dependency
            addFileWithDependency(contentPackage, path, pkg, previousPkg);
          }
          else {
            // add package file directly
            contentPackage.addFile(path, pkg.getFile());
          }
          previousPackages.add(pkg);
        }
      }
    }

    return true;
  }

  private static boolean hasPackageType(ContentPackageFile pkg) {
    // accept only content packages with a package type set
    return pkg.getPackageType() != null;
  }

  private static boolean isValidPackageType(ContentPackageFile pkg) {
    // check if the package type is an allowed package type
    return ALLOWED_PACKAGE_TYPES.contains(pkg.getPackageType());
  }

  private static boolean isMutable(ContentPackageFile pkg) {
    return StringUtils.equals("content", pkg.getPackageType());
  }

  private static boolean mutableMatches(ContentPackageFile pkg1, ContentPackageFile pkg2) {
    if (pkg1 == null || pkg2 == null) {
      return false;
    }
    return isMutable(pkg1) == isMutable(pkg2);
  }

  /**
   * Build root path to be used for embedded package.
   * @param groupName Group name
   * @param packageName Package name
   * @return Package path
   */
  private static String buildRootPath(String groupName, String packageName) {
    return "/apps/" + groupName + "-" + packageName + "-packages";
  }

  /**
   * Build path to be used for embedded package.
   * @param pkg Package
   * @param rootPath Root path
   * @return Package path
   */
  private static String buildPackagePath(ContentPackageFile pkg, String rootPath, String environmentRunmode) {
    StringBuilder runModeSuffix = new StringBuilder();
    if (RunModeUtil.isOnlyAuthor(pkg)) {
      runModeSuffix.append(".").append(RUNMODE_AUTHOR);
    }
    else if (RunModeUtil.isOnlyPublish(pkg)) {
      runModeSuffix.append(".").append(RUNMODE_PUBLISH);
    }
    if (!StringUtils.equals(environmentRunmode, RUNMODE_DEFAULT)) {
      runModeSuffix.append(".").append(environmentRunmode);
    }
    return rootPath + "/" + pkg.getPackageType() + "/install" + runModeSuffix.toString() + "/" + pkg.getFile().getName();
  }

  /**
   * Rewrite content package ZIP file while adding to "all" package:
   * Add dependency to previous package in CONGA configuration file oder.
   * @param contentPackage Target content page
   * @param path Path in target content package
   * @param pkg Package to add
   * @param previousPkg Previous package to get dependency information from
   * @throws IOException I/O error
   */
  private static void addFileWithDependency(ContentPackage contentPackage, String path,
      ContentPackageFile pkg, ContentPackageFile previousPkg) throws IOException {

    // create temp zip file to create rewritten copy of package
    File tempFile = File.createTempFile("pkg", ".zip");

    // open original content package
    try (ZipFile zipFileIn = new ZipFile(pkg.getFile())) {

      // iterate through entries and write them to the temp. zip file
      try (FileOutputStream fos = new FileOutputStream(tempFile);
          ZipOutputStream zipOut = new ZipOutputStream(fos)) {
        Enumeration<? extends ZipEntry> zipInEntries = zipFileIn.entries();
        while (zipInEntries.hasMoreElements()) {
          ZipEntry zipInEntry = zipInEntries.nextElement();
          ZipEntry zipOutEntry = new ZipEntry(zipInEntry.getName());
          if (!zipInEntry.isDirectory()) {
            zipOut.putNextEntry(zipOutEntry);
            if (StringUtils.equals(zipInEntry.getName(), "META-INF/vault/properties.xml")) {
              // if entry is properties.xml, update dependency information
              try (InputStream is = zipFileIn.getInputStream(zipInEntry)) {
                Properties props = new Properties();
                props.loadFromXML(is);
                updateDependencies(props, previousPkg);
                props.storeToXML(zipOut, null);
              }
            }
            else {
              // otherwise transfer the binary data 1:1
              try (InputStream is = zipFileIn.getInputStream(zipInEntry)) {
                IOUtils.copy(is, zipOut);
              }
            }
            zipOut.closeEntry();
          }
        }
      }

      // add temp zip file to "all" content package
      contentPackage.addFile(path, tempFile);
    }
    finally {
      FileUtils.deleteQuietly(tempFile);
    }
  }

  /**
   * Add dependency information to dependencies string in properties (if it does not exist already).
   * @param props Properties
   * @param dependencyFile Dependency package
   */
  private static void updateDependencies(Properties props, ContentPackageFile dependencyFile) {
    String[] existingDepsStrings = StringUtils.split(props.getProperty(NAME_DEPENDENCIES), ",");
    Dependency[] existingDeps = null;
    if (existingDepsStrings != null && existingDepsStrings.length > 0) {
      existingDeps = Dependency.fromString(existingDepsStrings);
    }

    Dependency newDependency = new Dependency(dependencyFile.getGroup(), dependencyFile.getName(),
        VersionRange.fromString(dependencyFile.getVersion()));
    Dependency[] deps;
    if (existingDeps != null) {
      deps = DependencyUtil.add(existingDeps, newDependency);
    }
    else {
      deps = new Dependency[] { newDependency };
    }

    props.put(NAME_DEPENDENCIES, Dependency.toString(deps));
  }

}
