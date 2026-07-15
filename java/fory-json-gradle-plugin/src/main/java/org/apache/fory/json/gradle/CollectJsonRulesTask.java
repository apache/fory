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

import java.io.File;
import java.io.IOException;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.Directory;
import org.gradle.api.file.RegularFile;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/** Collects generated rule carriers from the complete application variant class graph. */
@CacheableTask
public abstract class CollectJsonRulesTask extends DefaultTask {
  @InputFiles
  @PathSensitive(PathSensitivity.RELATIVE)
  public abstract ListProperty<RegularFile> getInputJars();

  @InputFiles
  @PathSensitive(PathSensitivity.RELATIVE)
  public abstract ListProperty<Directory> getInputDirectories();

  @OutputFile
  public abstract RegularFileProperty getOutputRules();

  @TaskAction
  public final void collect() throws IOException {
    File output = getOutputRules().get().getAsFile();
    RuleCarrierFiles.writeRules(
        RuleCarrierFiles.collect(getInputJars().get(), getInputDirectories().get()), output);
  }
}
