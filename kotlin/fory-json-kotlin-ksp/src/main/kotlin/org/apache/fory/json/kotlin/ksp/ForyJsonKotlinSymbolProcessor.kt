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

package org.apache.fory.json.kotlin.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.validate
import java.nio.charset.StandardCharsets

internal class ForyJsonKotlinSymbolProcessor(environment: SymbolProcessorEnvironment) :
  SymbolProcessor {
  private val codeGenerator: CodeGenerator = environment.codeGenerator
  private val logger: KSPLogger = environment.logger
  private val generatedRequests = linkedSetOf<String>()

  override fun process(resolver: Resolver): List<KSAnnotated> {
    val deferred = ArrayList<KSAnnotated>()
    val modelBuilder = KspModelBuilder(resolver, logger)
    val directSymbols =
      sequenceOf(JSON_TYPE, JSON_SUB_TYPES).flatMap(resolver::getSymbolsWithAnnotation).distinct()
    for (symbol in directSymbols) {
      if (!symbol.validate()) {
        deferred += symbol
        continue
      }
      val declaration = symbol as? KSClassDeclaration
      if (declaration == null) {
        logger.error(
          "@JsonType and @JsonSubTypes can only be used on classes, interfaces, and objects",
          symbol,
        )
        continue
      }
      val model = modelBuilder.direct(declaration) ?: continue
      if (!generatedRequests.add(R8RulesWriter.resourcePath(model))) continue
      write(model)
    }
    for (symbol in resolver.getSymbolsWithAnnotation(JSON_MIXIN)) {
      if (!symbol.validate()) {
        deferred += symbol
        continue
      }
      val declaration = symbol as? KSClassDeclaration
      if (declaration == null) {
        logger.error("@JsonMixin can only be used on classes and interfaces", symbol)
        continue
      }
      val generation = modelBuilder.javaSubtypeGeneration(declaration)
      if (generation != null) {
        val key = "${generation.packageName}.${generation.simpleName}"
        if (generatedRequests.add(key)) write(generation)
        continue
      }
      val model = modelBuilder.mixin(declaration) ?: continue
      if (!generatedRequests.add(R8RulesWriter.resourcePath(model))) continue
      write(model)
    }
    return deferred
  }

  private fun write(model: JsonModel) {
    val dependencies =
      Dependencies(aggregating = model.aggregating, sources = model.originatingFiles.toTypedArray())
    codeGenerator.createNewFileByPath(dependencies, R8RulesWriter.resourcePath(model), "").use {
      output ->
      output.write(R8RulesWriter.write(model).toByteArray(StandardCharsets.UTF_8))
    }
  }

  private fun write(generation: JavaSubtypeGeneration) {
    val dependencies =
      Dependencies(aggregating = true, sources = generation.originatingFiles.toTypedArray())
    codeGenerator
      .createNewFile(dependencies, generation.packageName, generation.simpleName, "java")
      .use { output ->
        output.write(
          JavaSubtypeGenerationWriter.write(generation).toByteArray(StandardCharsets.UTF_8)
        )
      }
  }
}
