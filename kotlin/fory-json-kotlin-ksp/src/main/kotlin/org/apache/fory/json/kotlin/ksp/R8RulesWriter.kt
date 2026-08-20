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

/** Emits one exact consumer-rule resource for one source-owned JSON request. */
internal object R8RulesWriter {
  fun resourcePath(model: JsonModel): String =
    model.mixinBinaryName?.let { "META-INF/proguard/fory-json-mixin-$it.pro" }
      ?: "META-INF/proguard/fory-json-${model.targetBinaryName}.pro"

  fun write(model: JsonModel): String =
    buildString(4096) {
      append("-keepattributes Signature,RuntimeVisibleAnnotations\n")
      append("-keepattributes RuntimeVisibleParameterAnnotations\n")
      append("-keepattributes AnnotationDefault,MethodParameters\n")
      val nestedType =
        model.targetBinaryName.indexOf('$') >= 0 ||
          model.mixinBinaryName?.indexOf('$')?.let { it >= 0 } == true ||
          model.members.any { it.ownerBinaryName.indexOf('$') >= 0 } ||
          model.codecTypes.any { it.indexOf('$') >= 0 } ||
          model.containerTypes.any { it.indexOf('$') >= 0 } ||
          model.annotationOwnerTypes.any { it.indexOf('$') >= 0 } ||
          model.retainedTypes.any { it.indexOf('$') >= 0 }
      if (nestedType) {
        append("-keepattributes InnerClasses,EnclosingMethod\n")
      }
      append('\n')
      val memberRules = memberRules(model)
      memberRules.forEach { (owner, members) ->
        val preserveName =
          owner == model.targetBinaryName ||
            owner == model.mixinBinaryName ||
            owner in model.retainedTypes
        append("-keep,allowoptimization")
        if (!preserveName) append(",allowobfuscation")
        append(" class $owner\n")
        if (members.isNotEmpty()) {
          // Runtime metadata and reflection locate these members by exact JVM descriptor. Allowing
          // optimization would let R8 rewrite method prototypes and break descriptor matching.
          append("-keepclassmembers class $owner {\n")
          members.forEach { append("  $it\n") }
          append("}\n")
        }
        append('\n')
      }
      model.retainedAnnotations.sorted().forEach { annotation ->
        append("-keep,allowoptimization,allowobfuscation @interface $annotation\n")
      }
      model.retainedTypes.sorted().filterNot(memberRules::containsKey).forEach { type ->
        append("-keep,allowoptimization class $type\n")
      }
      model.annotationOwnerTypes
        .sorted()
        .filterNot(memberRules::containsKey)
        .filterNot(model.retainedTypes::contains)
        .forEach { type -> append("-keep,allowoptimization,allowobfuscation class $type\n") }
    }

  private fun memberRules(model: JsonModel): Map<String, List<String>> {
    val result = linkedMapOf<String, MutableSet<String>>()
    fun add(owner: String, declaration: String) {
      result.getOrPut(owner, ::linkedSetOf).add(declaration)
    }
    result[model.targetBinaryName] = linkedSetOf()
    model.mixinBinaryName?.let { result[it] = linkedSetOf() }
    model.members.forEach { member ->
      when (member.kind) {
        MemberKind.FIELD ->
          add(member.ownerBinaryName, "${JvmType(member.descriptor).sourceName} ${member.name};")
        MemberKind.METHOD ->
          add(
            member.ownerBinaryName,
            if (member.name == "<init>") constructorRule(member.descriptor)
            else methodRule(member.name, member.descriptor),
          )
      }
    }
    // JsonSharedRegistry instantiates annotation-selected codecs through Class.getConstructor().
    // Retaining only the class literal does not preserve that reflective constructor under R8.
    model.codecTypes.sorted().forEach { codecType -> add(codecType, "public <init>();") }
    model.containerTypes.sorted().forEach { containerType ->
      add(containerType, "public <init>();")
    }
    return result.mapValues { (_, members) -> members.sorted() }
  }

  private fun methodRule(name: String, descriptor: String): String {
    val method = parseMethodDescriptor(descriptor)
    val result = if (method.result == "V") "void" else JvmType(method.result).sourceName
    return "$result $name(${method.parameters.joinToString(",") { it.sourceName }});"
  }

  private fun constructorRule(descriptor: String): String {
    val parameters = parseMethodDescriptor(descriptor).parameters
    return "<init>(${parameters.joinToString(",") { it.sourceName }});"
  }
}
