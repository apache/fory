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

package org.apache.fory.annotation.processing;

import java.io.IOException;
import java.io.Writer;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import org.apache.fory.json.meta.JsonTypeMetadata;

/** Generates the complete configuration-independent metadata required by Fory JSON on Android. */
public final class ForyJsonProcessor extends AbstractProcessor {
  private static final String JSON_TYPE = "org.apache.fory.json.annotation.JsonType";
  private static final Set<String> SUPPORTED = Collections.singleton(JSON_TYPE);

  private final Set<String> completed = new LinkedHashSet<>();
  private final Map<String, Pending> pending = new LinkedHashMap<>();
  private final Map<String, String> generatedNames = new LinkedHashMap<>();
  private Filer filer;
  private Messager messager;

  @Override
  public synchronized void init(ProcessingEnvironment environment) {
    super.init(environment);
    filer = environment.getFiler();
    messager = environment.getMessager();
  }

  @Override
  public Set<String> getSupportedAnnotationTypes() {
    return SUPPORTED;
  }

  @Override
  public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.latestSupported();
  }

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
    TypeElement annotation = processingEnv.getElementUtils().getTypeElement(JSON_TYPE);
    if (annotation == null) {
      return false;
    }
    for (Element element : round.getElementsAnnotatedWith(annotation)) {
      if (element instanceof TypeElement) {
        TypeElement type = (TypeElement) element;
        pending.putIfAbsent(
            type.getQualifiedName().toString(), new Pending(type, "Unresolved generated metadata"));
      }
    }
    if (round.processingOver()) {
      for (Pending value : pending.values()) {
        messager.printMessage(Diagnostic.Kind.ERROR, value.message, value.element);
      }
      pending.clear();
      return true;
    }
    for (String qualifiedName : new LinkedHashSet<>(pending.keySet())) {
      TypeElement type = processingEnv.getElementUtils().getTypeElement(qualifiedName);
      if (type == null) {
        continue;
      }
      String binaryName = processingEnv.getElementUtils().getBinaryName(type).toString();
      if (completed.contains(binaryName)) {
        pending.remove(qualifiedName);
        continue;
      }
      try {
        generate(type);
        completed.add(binaryName);
        pending.remove(qualifiedName);
      } catch (JsonSourceCollector.DeferredTypeException e) {
        Pending previous = pending.get(qualifiedName);
        if (previous == null || previous.message.equals("Unresolved generated metadata")) {
          pending.put(qualifiedName, new Pending(type, e.getMessage()));
        }
      } catch (ProcessingException e) {
        messager.printMessage(
            Diagnostic.Kind.ERROR, e.getMessage(), e.element == null ? type : e.element);
        pending.remove(qualifiedName);
      } catch (RuntimeException e) {
        messager.printMessage(
            Diagnostic.Kind.ERROR,
            "Failed to generate Fory JSON metadata for " + binaryName + ": " + e.getMessage(),
            type);
        pending.remove(qualifiedName);
      }
    }
    return true;
  }

  private void generate(TypeElement type) {
    JsonSourceModel model = new JsonSourceCollector(processingEnv, type).collect();
    String previous = generatedNames.put(model.metadataBinaryName, model.targetBinaryName);
    if (previous != null && !previous.equals(model.targetBinaryName)) {
      throw new ProcessingException(
          "Generated JSON metadata name "
              + model.metadataBinaryName
              + " is ambiguous for "
              + previous
              + " and "
              + model.targetBinaryName,
          type);
    }
    TypeElement existing = processingEnv.getElementUtils().getTypeElement(model.metadataBinaryName);
    if (existing != null) {
      throw new ProcessingException(
          "Generated JSON metadata name collides with existing type " + model.metadataBinaryName,
          type);
    }
    JsonMetadataEncoder encoder = new JsonMetadataEncoder();
    JsonMetadataEncoder.EncodedSection[] sections =
        new JsonMetadataEncoder.EncodedSection[JsonTypeMetadata.SECTION_COUNT];
    for (int section = 0; section < sections.length; section++) {
      sections[section] = encoder.encode(model.section(section));
    }
    String source = new JsonMetadataSourceWriter(processingEnv, model, sections).write();
    String rules =
        new JsonR8RulesWriter(model, processingEnv.getElementUtils(), processingEnv.getTypeUtils())
            .write();
    writeSource(model, type, source);
    writeRules(model, type, rules);
  }

  private void writeSource(JsonSourceModel model, TypeElement type, String source) {
    try {
      JavaFileObject file = filer.createSourceFile(model.metadataBinaryName, type);
      try (Writer writer = file.openWriter()) {
        writer.write(source);
      }
    } catch (IOException e) {
      throw new ProcessingException("Failed to write " + model.metadataBinaryName, type, e);
    }
  }

  private void writeRules(JsonSourceModel model, TypeElement type, String rules) {
    String resource =
        "META-INF/com.android.tools/r8/fory-json-generated-"
            + JsonTypeMetadata.escapedResourceName(model.targetBinaryName)
            + ".pro";
    try {
      javax.tools.FileObject file =
          filer.createResource(StandardLocation.CLASS_OUTPUT, "", resource, type);
      try (Writer writer = file.openWriter()) {
        writer.write(rules);
      }
    } catch (IOException e) {
      throw new ProcessingException("Failed to write generated JSON R8 rules", type, e);
    }
  }

  private static final class Pending {
    final TypeElement element;
    final String message;

    Pending(TypeElement element, String message) {
      this.element = element;
      this.message = message;
    }
  }
}
