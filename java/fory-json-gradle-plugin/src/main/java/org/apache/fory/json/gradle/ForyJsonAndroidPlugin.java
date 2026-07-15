/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.fory.json.gradle;

import com.android.build.api.AndroidPluginVersion;
import com.android.build.api.artifact.ScopedArtifact;
import com.android.build.api.artifact.SingleArtifact;
import com.android.build.api.variant.AndroidComponentsExtension;
import com.android.build.api.variant.ScopedArtifacts;
import com.android.build.api.variant.Variant;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.TaskProvider;

/** Publishes annotation-processor-generated Fory JSON rules through Android variants. */
public final class ForyJsonAndroidPlugin implements Plugin<Project> {
  public static final String PLUGIN_ID = "org.apache.fory.json";

  @Override
  public void apply(Project project) {
    AppliedAndroidPlugin applied = new AppliedAndroidPlugin(project);
    project
        .getPluginManager()
        .withPlugin("com.android.application", ignored -> applied.configure(false));
    project
        .getPluginManager()
        .withPlugin("com.android.library", ignored -> applied.configure(true));
    project.afterEvaluate(ignored -> applied.requireConfigured());
  }

  private static void configure(Project project, boolean library) {
    AndroidComponentsExtension<?, ?, ?> androidComponents =
        project.getExtensions().getByType(AndroidComponentsExtension.class);
    requireSupportedAgp(androidComponents.getPluginVersion());
    if (library) {
      configureLibraries(project, androidComponents);
    } else {
      configureApplications(project, androidComponents);
    }
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static void configureApplications(
      Project project, AndroidComponentsExtension<?, ?, ?> androidComponents) {
    AndroidComponentsExtension raw = androidComponents;
    raw.onVariants(
        raw.selector().all(), (Action<Variant>) variant -> configureApplication(project, variant));
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static void configureLibraries(
      Project project, AndroidComponentsExtension<?, ?, ?> androidComponents) {
    AndroidComponentsExtension raw = androidComponents;
    raw.onVariants(
        raw.selector().all(), (Action<Variant>) variant -> configureLibrary(project, variant));
  }

  private static void configureApplication(Project project, Variant variant) {
    // The carrier is a processor-to-plugin ABI, not an application resource. The collector still
    // reads it from the complete class scope, while this independent packaging rule prevents app
    // and dependency carriers from reaching APKs or bundles.
    variant.getPackaging().getResources().getExcludes().add(RuleCarrierFiles.CARRIER_PREFIX + "**");
    String capitalizedName = capitalize(variant.getName());
    TaskProvider<CollectJsonRulesTask> collect =
        project
            .getTasks()
            .register(
                "collect" + capitalizedName + "ForyJsonRules",
                CollectJsonRulesTask.class,
                task ->
                    task.getOutputRules()
                        .set(
                            project
                                .getLayout()
                                .getBuildDirectory()
                                .file(
                                    "intermediates/fory-json-r8/"
                                        + variant.getName()
                                        + "/rules.pro")));
    variant
        .getArtifacts()
        .forScope(ScopedArtifacts.Scope.ALL)
        .use(collect)
        .toGet(
            ScopedArtifact.CLASSES.INSTANCE,
            CollectJsonRulesTask::getInputJars,
            CollectJsonRulesTask::getInputDirectories);
    variant.getProguardFiles().add(collect.flatMap(CollectJsonRulesTask::getOutputRules));
  }

  private static void configureLibrary(Project project, Variant variant) {
    String capitalizedName = capitalize(variant.getName());
    TaskProvider<PublishJsonRulesTask> publish =
        project
            .getTasks()
            .register(
                "publish" + capitalizedName + "ForyJsonRules",
                PublishJsonRulesTask.class,
                task ->
                    task.getOutputAar()
                        .set(
                            project
                                .getLayout()
                                .getBuildDirectory()
                                .file(
                                    "intermediates/fory-json-aar/"
                                        + variant.getName()
                                        + "/published.aar")));
    variant
        .getArtifacts()
        .use(publish)
        .wiredWithFiles(PublishJsonRulesTask::getInputAar, PublishJsonRulesTask::getOutputAar)
        .toTransform(SingleArtifact.AAR.INSTANCE);
  }

  private static void requireSupportedAgp(AndroidPluginVersion version) {
    if (version.getMajor() < 8) {
      throw new GradleException(
          PLUGIN_ID + " requires Android Gradle Plugin 8.0 or later; found " + version);
    }
  }

  private static String capitalize(String value) {
    if (value.isEmpty()) {
      return value;
    }
    char first = value.charAt(0);
    if (Character.isUpperCase(first)) {
      return value;
    }
    return Character.toUpperCase(first) + value.substring(1);
  }

  private static final class AppliedAndroidPlugin {
    private final Project project;
    private String androidPlugin;

    AppliedAndroidPlugin(Project project) {
      this.project = project;
    }

    void configure(boolean library) {
      String plugin = library ? "com.android.library" : "com.android.application";
      if (androidPlugin != null) {
        throw new GradleException(
            PLUGIN_ID
                + " requires exactly one Android plugin, but found "
                + androidPlugin
                + " and "
                + plugin);
      }
      androidPlugin = plugin;
      ForyJsonAndroidPlugin.configure(project, library);
    }

    void requireConfigured() {
      if (androidPlugin == null) {
        throw new GradleException(
            PLUGIN_ID
                + " requires com.android.application or com.android.library in the same project");
      }
    }
  }
}
