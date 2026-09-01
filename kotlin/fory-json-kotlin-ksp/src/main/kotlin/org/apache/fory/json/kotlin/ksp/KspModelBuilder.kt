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

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.FunctionKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSPropertyGetter
import com.google.devtools.ksp.symbol.KSPropertySetter
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Nullability
import com.google.devtools.ksp.symbol.Origin

internal const val JSON_TYPE: String = "org.apache.fory.json.annotation.JsonType"
internal const val JSON_MIXIN: String = "org.apache.fory.json.annotation.JsonMixin"
internal const val JSON_SUB_TYPES: String = "org.apache.fory.json.annotation.JsonSubTypes"
private const val JSON_SUB_TYPE = "org.apache.fory.json.annotation.JsonSubTypes.Type"
private const val JSON_CODEC = "org.apache.fory.json.annotation.JsonCodec"
private const val JSON_BYTE_ARRAY = "org.apache.fory.json.annotation.JsonByteArray"
private const val JSON_ANY_SETTER = "org.apache.fory.json.annotation.JsonAnySetter"
private const val JSON_VALIDATOR = "org.apache.fory.json.annotation.JsonValidator"
private const val JSON_CREATOR = "org.apache.fory.json.annotation.JsonCreator"
private const val JSON_MIXIN_REMOVE = "org.apache.fory.json.annotation.JsonMixinRemove"
private const val JSON_ANNOTATION_PACKAGE = "org.apache.fory.json.annotation."
private const val DEFAULT_MARKER = "Lkotlin/jvm/internal/DefaultConstructorMarker;"
private const val BASE64_CODEC = "org.apache.fory.json.codec.Base64ByteArrayCodec"

/** KSP-side producer for exact runtime-metadata retention of one source declaration. */
@OptIn(KspExperimental::class)
internal class KspModelBuilder(
  private val resolver: Resolver,
  private val logger: KSPLogger,
) {
  private data class CreatorMembers(
    val members: List<JvmMember>,
    val parameters: Map<String, JvmType>,
  )

  private class Retention {
    val annotations = linkedSetOf<String>()
    val annotationOwners = linkedSetOf<String>()
    val types = linkedSetOf<String>()
    val codecs = linkedSetOf<String>()
    val containers = linkedSetOf<String>()
  }

  fun direct(target: KSClassDeclaration): JsonModel? {
    if (target.origin != Origin.KOTLIN || target.containingFile == null) return null
    if (hasAnnotation(target, JSON_MIXIN)) return null
    val typeCodec = hasCompleteTypeCodec(target, null)
    val model = if (typeCodec) typeCodecModel(target, null) else kotlinModel(target, null)
    return model?.let {
      if (emptySubTypes(target, null) && !typeCodec) sealedModel(target, null, it) else it
    }
  }

  fun javaSubtypeGeneration(source: KSClassDeclaration): JavaSubtypeGeneration? {
    if (source.origin != Origin.KOTLIN || source.containingFile == null) return null
    val target = mixinTarget(source) ?: return null
    if (isKotlin(target.origin) || !emptySubTypes(target, source)) return null
    if (hasCompleteTypeCodec(target, source)) return null
    val mixinBinaryName = binaryName(source) ?: return fail(source, "@JsonMixin must be named")
    val mixinSourceName =
      source.qualifiedName?.asString() ?: return fail(source, "@JsonMixin must be named")
    return JavaSubtypeGeneration(
      packageName = source.packageName.asString(),
      simpleName =
        escapeGeneratedName(mixinBinaryName.substringAfterLast('.')) + "_ForyJsonSubtypeGeneration",
      mixinSourceName = mixinSourceName,
      originatingFiles = originatingFiles(target, source),
    )
  }

  fun mixin(source: KSClassDeclaration): JsonModel? {
    if (source.containingFile == null || source.origin !in SOURCE_ORIGINS) return null
    val target = mixinTarget(source) ?: return null
    if (source.origin == Origin.JAVA && !isKotlin(target.origin)) return null
    val typeCodec = hasCompleteTypeCodec(target, source)
    val model =
      if (typeCodec) typeCodecModel(target, source)
      else if (isKotlin(target.origin)) kotlinModel(target, source) else javaModel(target, source)
    return model?.let {
      if (isKotlin(target.origin) && emptySubTypes(target, source) && !typeCodec) {
        sealedModel(target, source, it)
      } else {
        it
      }
    }
  }

  private fun emptySubTypes(
    target: KSClassDeclaration,
    mixin: KSClassDeclaration?,
  ): Boolean {
    val annotation = effectiveAnnotation(target, mixin, JSON_SUB_TYPES) ?: return false
    val value = annotation.arguments.firstOrNull { it.name?.asString() == "value" }?.value
    return value == null || value is Iterable<*> && value.none()
  }

  private fun hasCompleteTypeCodec(
    target: KSClassDeclaration,
    mixin: KSClassDeclaration?,
  ): Boolean {
    if (effectiveAnnotation(target, mixin, JSON_CODEC)?.let(::selectsValueCodec) == true) {
      return true
    }
    if (mixin != null && removesAnnotation(mixin, JSON_CODEC)) return false
    val candidates =
      target
        .getAllSuperTypes()
        .mapNotNull { actual(it.declaration) as? KSClassDeclaration }
        .distinctBy(::binaryName)
        .mapNotNull { declaration ->
          declaration.annotations
            .firstOrNull { annotationName(it) == JSON_CODEC }
            ?.let { declaration to it }
        }
        .toList()
    return candidates.any { candidate ->
      val owner = candidate.first
      val dominated =
        candidates.any { other ->
          other.first != owner &&
            owner.asStarProjectedType().isAssignableFrom(other.first.asStarProjectedType())
        }
      !dominated && selectsValueCodec(candidate.second)
    }
  }

  private fun selectsValueCodec(annotation: KSAnnotation): Boolean {
    val value =
      annotation.arguments.firstOrNull { it.name?.asString() == "value" }?.value as? KSType
        ?: return false
    val declaration = actual(value.declaration) as? KSClassDeclaration ?: return false
    return declaration.qualifiedName?.asString() !in CODEC_SENTINELS
  }

  private fun typeCodecModel(
    target: KSClassDeclaration,
    mixin: KSClassDeclaration?,
  ): JsonModel? {
    val targetName = binaryName(target) ?: return fail(target, "JSON target must be named")
    val retention = Retention()
    val origins = ArrayList(listOfNotNull(target.containingFile, mixin?.containingFile))
    effectiveAnnotation(target, mixin, JSON_CODEC)?.let { annotation ->
      retention.annotations += JSON_CODEC
      collectCodecAnnotation(annotation, retention.codecs)
    }
    if (mixin == null || !removesAnnotation(mixin, JSON_CODEC)) {
      target.getAllSuperTypes().forEach { supertype ->
        val owner = actual(supertype.declaration) as? KSClassDeclaration ?: return@forEach
        owner.annotations
          .firstOrNull { annotationName(it) == JSON_CODEC }
          ?.let { annotation ->
            retention.annotations += JSON_CODEC
            collectCodecAnnotation(annotation, retention.codecs)
            binaryName(owner)?.let(retention.annotationOwners::add)
            owner.containingFile?.let(origins::add)
          }
      }
    }
    if (mixin != null) {
      retention.annotations += JSON_MIXIN
      if (hasAnnotation(mixin, JSON_MIXIN_REMOVE)) retention.annotations += JSON_MIXIN_REMOVE
    }
    return JsonModel(
      targetBinaryName = targetName,
      members = emptyList(),
      mixinBinaryName = mixin?.let(::binaryName),
      originatingFiles = origins.distinct(),
      retainedAnnotations = retention.annotations,
      annotationOwnerTypes = retention.annotationOwners,
      retainedTypes = emptySet(),
      codecTypes = retention.codecs,
      containerTypes = emptySet(),
    )
  }

  private fun escapeGeneratedName(value: String): String {
    val result = java.lang.StringBuilder(value.length + 32)
    var index = 0
    while (index < value.length) {
      val codePoint = value.codePointAt(index)
      when {
        codePoint == '$'.code -> result.append("_d_")
        codePoint == '_'.code -> result.append("_u_")
        Character.isJavaIdentifierPart(codePoint) -> result.appendCodePoint(codePoint)
        else -> result.append("_x").append(Integer.toHexString(codePoint)).append('_')
      }
      index += Character.charCount(codePoint)
    }
    return result.toString()
  }

  private fun sealedModel(
    target: KSClassDeclaration,
    mixin: KSClassDeclaration?,
    root: JsonModel,
  ): JsonModel? {
    if (Modifier.SEALED !in target.modifiers) {
      return fail(target, "Empty @JsonSubTypes requires a sealed type")
    }
    if (target.classKind != ClassKind.INTERFACE && Modifier.ABSTRACT !in target.modifiers) {
      return fail(target, "@JsonSubTypes requires an interface or abstract base type")
    }
    // KSP symbols are trusted build-time schema metadata. Retaining the exact closure keeps
    // runtime Kotlin metadata discovery finite and stable after R8/ProGuard.
    val declarations = ArrayList<KSClassDeclaration>()
    val closure = ArrayList<KSClassDeclaration>()
    val retained = linkedSetOf<String>()
    val visited = linkedSetOf<String>()
    fun collect(sealedType: KSClassDeclaration): Boolean {
      for (subtype in sealedType.getSealedSubclasses()) {
        val binary = binaryName(subtype) ?: return false
        if (!visited.add(binary)) continue
        closure += subtype
        retained += binary
        val concrete =
          subtype.classKind != ClassKind.INTERFACE && Modifier.ABSTRACT !in subtype.modifiers
        if (concrete) declarations += subtype
        if (Modifier.SEALED in subtype.modifiers) {
          if (!collect(subtype)) return false
        } else if (!concrete) {
          fail<Any>(subtype, "Sealed JSON hierarchy has an open abstract branch")
          return false
        }
      }
      return true
    }
    if (!collect(target)) return null
    if (declarations.isEmpty()) {
      fail<Any>(target, "Sealed JSON hierarchy has no concrete subtype")
      return null
    }
    declarations.sortBy { binaryName(it) }
    val models = ArrayList<JsonModel>(declarations.size + 1)
    models += root
    for (declaration in declarations) {
      models +=
        if (isKotlin(declaration.origin)) kotlinModel(declaration, null) ?: return null
        else javaModel(declaration, null) ?: return null
    }
    val closureFiles = closure.mapNotNull(KSClassDeclaration::containingFile)
    return JsonModel(
      targetBinaryName = root.targetBinaryName,
      members = models.flatMap(JsonModel::members).distinct(),
      mixinBinaryName = root.mixinBinaryName,
      originatingFiles =
        (models.flatMap(JsonModel::originatingFiles) +
            closureFiles +
            listOfNotNull(target.containingFile, mixin?.containingFile))
          .distinct(),
      retainedAnnotations = models.flatMap(JsonModel::retainedAnnotations).toSet(),
      annotationOwnerTypes = models.flatMap(JsonModel::annotationOwnerTypes).toSet(),
      retainedTypes = models.flatMap(JsonModel::retainedTypes).toSet() + retained,
      codecTypes = models.flatMap(JsonModel::codecTypes).toSet(),
      containerTypes = models.flatMap(JsonModel::containerTypes).toSet(),
      aggregating = true,
    )
  }

  private fun javaModel(
    target: KSClassDeclaration,
    mixin: KSClassDeclaration?,
  ): JsonModel? {
    val targetName = binaryName(target) ?: return fail(target, "JSON target must be named")
    val members =
      javaMembers(target, mixin) +
        (mixin?.let(::mixinMembers) ?: emptyList()) +
        listOfNotNull(javaAnySetter(target, mixin), javaCreator(target, mixin)) +
        validators(target, mixin)
    val retention = retention(target, mixin)
    return JsonModel(
      targetBinaryName = targetName,
      members = members,
      mixinBinaryName = mixin?.let(::binaryName),
      originatingFiles = originatingFiles(target, mixin),
      retainedAnnotations = retention.annotations,
      annotationOwnerTypes = retention.annotationOwners,
      retainedTypes = retention.types - targetName,
      codecTypes = retention.codecs,
      containerTypes = retention.containers,
    )
  }

  private fun mixinTarget(source: KSClassDeclaration): KSClassDeclaration? {
    val annotation =
      source.annotations.firstOrNull { annotationName(it) == JSON_MIXIN }
        ?: return fail(source, "Missing @JsonMixin declaration")
    val value = annotation.arguments.firstOrNull { it.name?.asString() == "target" }?.value
    val type = value as? KSType ?: return fail(source, "@JsonMixin must declare a target type")
    if (type.isError) return null
    return actual(type.declaration) as? KSClassDeclaration
      ?: fail(source, "@JsonMixin target must be a declared type")
  }

  private fun originatingFiles(
    target: KSClassDeclaration,
    mixin: KSClassDeclaration?,
  ) = listOfNotNull(mixin?.containingFile, target.containingFile).distinct()

  private fun isKotlin(origin: Origin): Boolean =
    origin == Origin.KOTLIN || origin == Origin.KOTLIN_LIB

  private fun kotlinModel(
    target: KSClassDeclaration,
    mixin: KSClassDeclaration?,
  ): JsonModel? {
    val targetName = binaryName(target) ?: return fail(target, "@JsonType target must be named")
    if (Modifier.INNER in target.modifiers) {
      return fail(target, "Kotlin JSON does not support inner classes")
    }
    val singleton = target.classKind == ClassKind.OBJECT && !target.isCompanionObject
    val closed =
      if (mixin == null) hasAnnotation(target, JSON_SUB_TYPES)
      else effectiveTypeAnnotation(target, mixin, JSON_SUB_TYPES)
    val concrete =
      target.classKind == ClassKind.CLASS &&
        Modifier.ABSTRACT !in target.modifiers &&
        Modifier.SEALED !in target.modifiers
    val valueClass = isValueClass(target)
    val retainModel = concrete && !closed
    val creatorMembers =
      if (retainModel && !singleton && !valueClass) {
        kotlinCreator(target, mixin) ?: return null
      } else {
        null
      }
    val targetMembers =
      when {
        singleton ->
          listOf(
            JvmMember(
              MemberKind.FIELD,
              targetName,
              "INSTANCE",
              "L${targetName.replace('.', '/')};",
            )
          ) + (members(target, emptyMap()) ?: return null)
        valueClass -> valueClassMembers(target) ?: return null
        creatorMembers != null ->
          (members(target, creatorMembers.parameters) ?: return null) + creatorMembers.members
        else -> emptyList()
      }
    val anySetter = effectiveMethods(target, mixin, JSON_ANY_SETTER)
    if (creatorMembers != null && anySetter.isNotEmpty()) {
      return fail(
        anySetter.first(),
        "@JsonAnySetter is not supported on a Kotlin constructor model"
      )
    }
    val members =
      targetMembers + (mixin?.let(::mixinMembers) ?: emptyList()) + validators(target, mixin)
    val retention = retention(target, mixin)
    return JsonModel(
      targetBinaryName = targetName,
      members = members,
      mixinBinaryName = mixin?.let(::binaryName),
      originatingFiles = originatingFiles(target, mixin),
      retainedAnnotations = retention.annotations,
      annotationOwnerTypes = retention.annotationOwners,
      retainedTypes = retention.types - targetName,
      codecTypes = retention.codecs,
      containerTypes = retention.containers,
    )
  }

  private fun valueClassMembers(target: KSClassDeclaration): List<JvmMember>? {
    val members = ArrayList<JvmMember>()
    var declaration = target
    while (true) {
      val constructor =
        declaration.primaryConstructor
          ?: return fail(declaration, "Kotlin value class must have a primary constructor")
      if (constructor.parameters.size != 1) {
        return fail(constructor, "Kotlin value class must have one underlying parameter")
      }
      val underlying = constructor.parameters.single().type.resolve()
      val carrier = valueClassCarrier(constructor, underlying) ?: return null
      val owner = binaryName(declaration)!!
      val ownerDescriptor = "L${owner.replace('.', '/')};"
      val fieldName =
        constructor.parameters.single().name?.asString()
          ?: return fail(constructor, "Kotlin value-class parameter must be named")
      members += JvmMember(MemberKind.FIELD, owner, fieldName, carrier.descriptor)
      members +=
        JvmMember(MemberKind.METHOD, owner, "<init>", methodDescriptor(listOf(carrier), "V"))
      members +=
        JvmMember(
          MemberKind.METHOD,
          owner,
          "constructor-impl",
          methodDescriptor(listOf(carrier), carrier.descriptor),
        )
      members +=
        JvmMember(
          MemberKind.METHOD,
          owner,
          "box-impl",
          methodDescriptor(listOf(carrier), ownerDescriptor),
        )
      members +=
        JvmMember(
          MemberKind.METHOD,
          owner,
          "unbox-impl",
          methodDescriptor(emptyList(), carrier.descriptor)
        )
      val underlyingDeclaration = actual(underlying.declaration) as? KSClassDeclaration
      if (underlyingDeclaration == null || !isValueClass(underlyingDeclaration)) {
        break
      }
      declaration = underlyingDeclaration
    }
    return members
  }

  private fun kotlinCreator(
    target: KSClassDeclaration,
    mixin: KSClassDeclaration?,
  ): CreatorMembers? {
    val selected = ArrayList<KSFunctionDeclaration>()
    target
      .getConstructors()
      .filter { constructor ->
        if (mixin == null) hasAnnotation(constructor, JSON_CREATOR)
        else hasEffectiveAnnotation(constructor, mixin, JSON_CREATOR)
      }
      .forEach(selected::add)
    kotlinFactories(target)
      .filter { factory ->
        if (mixin == null) hasAnnotation(factory, JSON_CREATOR)
        else hasEffectiveAnnotation(factory, mixin, JSON_CREATOR)
      }
      .forEach(selected::add)
    if (selected.size > 1) {
      return fail(selected[1], "Exactly one effective @JsonCreator is allowed")
    }
    val explicit = selected.singleOrNull()
    if (explicit != null && explicit.simpleName.asString() != "<init>") {
      return kotlinFactory(target, explicit)
    }
    val constructor =
      explicit
        ?: target.primaryConstructor
        ?: return fail(target, "Kotlin JSON model has no selected constructor")
    if (Modifier.PRIVATE in constructor.modifiers || Modifier.PROTECTED in constructor.modifiers) {
      return fail(constructor, "Kotlin JSON constructor must be public or internal")
    }
    if (constructor.parameters.any { it.isVararg }) {
      return fail(constructor, "Kotlin JSON constructor must not be vararg")
    }
    val rawDescriptor =
      descriptor(constructor)
        ?: return fail(constructor, "Cannot map Kotlin constructor to a JVM descriptor")
    val parameterTypes = constructor.parameters.map { carrier(it.type.resolve()) }
    val descriptor = normalizeValueCarriers(rawDescriptor, parameterTypes)
    val parsed = parseMethodDescriptor(descriptor)
    val count = constructor.parameters.size
    val types =
      when {
        parsed.parameters.size == count -> parsed.parameters
        parsed.parameters.size == count + 1 &&
          parsed.parameters.last().descriptor == DEFAULT_MARKER -> parsed.parameters.dropLast(1)
        else -> return fail(constructor, "Kotlin constructor JVM shape is inconsistent")
      }
    var invocation = descriptor
    if (
      parsed.parameters.size == count &&
        constructor.parameters.indices.any { index ->
          isUnboxedValue(constructor.parameters[index].type.resolve(), types[index])
        }
    ) {
      invocation = appendParameters(descriptor, listOf(JvmType(DEFAULT_MARKER)))
    }
    val optional = BooleanArray(count) { constructor.parameters[it].hasDefault }
    val defaultDescriptor =
      if (optional.any { it }) {
        methodDescriptor(
          types + List((count + 31) ushr 5) { JvmType("I") } + JvmType(DEFAULT_MARKER),
          "V",
        )
      } else {
        null
      }
    val names =
      constructor.parameters.mapIndexed { index, parameter ->
        parameter.name?.asString() ?: return fail(parameter, "Unnamed constructor parameter $index")
      }
    val owner = binaryName(target)!!
    val executable = methodDescriptor(types, "V")
    val members = linkedSetOf(JvmMember(MemberKind.METHOD, owner, "<init>", executable))
    members += JvmMember(MemberKind.METHOD, owner, "<init>", invocation)
    defaultDescriptor?.let { members += JvmMember(MemberKind.METHOD, owner, "<init>", it) }
    return CreatorMembers(
      members = members.toList(),
      parameters = names.indices.associate { names[it] to types[it] },
    )
  }

  private fun kotlinFactory(
    target: KSClassDeclaration,
    factory: KSFunctionDeclaration,
  ): CreatorMembers? {
    val owner =
      factory.parentDeclaration as? KSClassDeclaration
        ?: return fail(factory, "Kotlin @JsonCreator factory must be a target member")
    val companionFactory = owner.isCompanionObject
    val modifiers = resolver.effectiveJavaModifiers(factory)
    if (
      Modifier.PRIVATE in factory.modifiers ||
        Modifier.PROTECTED in factory.modifiers ||
        factory.parameters.any { it.isVararg } ||
        factory.typeParameters.isNotEmpty() ||
        factory.parameters.any { it.hasDefault } ||
        companionFactory && !hasAnnotation(factory, "kotlin.jvm.JvmStatic") ||
        !companionFactory &&
          Modifier.JAVA_STATIC !in modifiers &&
          factory.functionKind != FunctionKind.STATIC
    ) {
      return fail(factory, "Invalid Kotlin @JsonCreator static factory")
    }
    val targetName = binaryName(target)!!
    val sourceTypes = factory.parameters.map { propertyCarrier(it.type.resolve()) }
    val rawDescriptor =
      descriptor(factory)
        ?: return fail(factory, "Cannot map Kotlin @JsonCreator factory to a JVM descriptor")
    val normalized = normalizeValueCarriers(rawDescriptor, sourceTypes)
    val parsed = parseMethodDescriptor(normalized)
    if (
      parsed.parameters.size != factory.parameters.size ||
        parsed.result != "L${targetName.replace('.', '/')};"
    ) {
      return fail(factory, "Kotlin @JsonCreator factory must return its exact owner")
    }
    val names =
      factory.parameters.mapIndexed { index, parameter ->
        parameter.name?.asString() ?: return fail(parameter, "Unnamed factory parameter $index")
      }
    val name = resolver.getJvmName(factory) ?: factory.simpleName.asString()
    val members = ArrayList<JvmMember>(if (companionFactory) 2 else 1)
    members += JvmMember(MemberKind.METHOD, targetName, name, normalized)
    if (companionFactory) {
      members += JvmMember(MemberKind.METHOD, binaryName(owner)!!, name, normalized)
    }
    return CreatorMembers(
      members = members,
      parameters = names.indices.associate { names[it] to parsed.parameters[it] },
    )
  }

  private fun kotlinFactories(target: KSClassDeclaration): Sequence<KSFunctionDeclaration> {
    val direct =
      target.declarations.filterIsInstance<KSFunctionDeclaration>().filter { function ->
        function.simpleName.asString() != "<init>" &&
          (function.functionKind == FunctionKind.STATIC ||
            Modifier.JAVA_STATIC in resolver.effectiveJavaModifiers(function))
      }
    val companions =
      target.declarations
        .filterIsInstance<KSClassDeclaration>()
        .filter { it.isCompanionObject }
        .flatMap { companion ->
          companion.declarations.filterIsInstance<KSFunctionDeclaration>().filter {
            hasAnnotation(it, "kotlin.jvm.JvmStatic")
          }
        }
    return direct + companions
  }

  private fun normalizeValueCarriers(
    descriptor: String,
    sourceTypes: List<JvmType>,
  ): String {
    require(descriptor.startsWith('(')) { "Invalid Kotlin constructor descriptor $descriptor" }
    val result = StringBuilder(descriptor.length + 32).append('(')
    var offset = 1
    var parameter = 0
    while (descriptor[offset] != ')') {
      val start = offset
      while (descriptor[offset] == '[') offset++
      offset =
        if (descriptor[offset] == 'L') descriptor.indexOf(';', offset + 1) + 1 else offset + 1
      require(offset > start) { "Invalid Kotlin constructor descriptor $descriptor" }
      if (descriptor[start] == 'V') {
        require(parameter < sourceTypes.size) { "Unexpected void carrier in $descriptor" }
        result.append(sourceTypes[parameter].descriptor)
      } else {
        result.append(descriptor, start, offset)
      }
      parameter++
    }
    return result.append(descriptor.substring(offset)).toString()
  }

  private fun javaMembers(
    target: KSClassDeclaration,
    mixin: KSClassDeclaration?,
  ): List<JvmMember> {
    val result = linkedMapOf<String, JvmMember>()
    for (property in target.getAllProperties()) {
      if (property.origin !in JAVA_ORIGINS || !property.hasBackingField) continue
      val owner = property.parentDeclaration as? KSClassDeclaration ?: continue
      val modifiers = resolver.effectiveJavaModifiers(property)
      if (Modifier.JAVA_STATIC in modifiers || Modifier.JAVA_TRANSIENT in modifiers) {
        continue
      }
      val type = propertyDescriptor(property)
      if (type.descriptor == "Ljava/lang/Class;") continue
      val member =
        JvmMember(
          MemberKind.FIELD,
          binaryName(owner)!!,
          property.simpleName.asString(),
          type.descriptor,
        )
      result["F#${member.ownerBinaryName}#${member.name}#${member.descriptor}"] = member
    }
    for (function in effectiveFunctions(target)) {
      val owner = function.parentDeclaration as? KSClassDeclaration ?: continue
      val modifiers = resolver.effectiveJavaModifiers(function)
      if (
        Modifier.PUBLIC !in modifiers ||
          Modifier.JAVA_STATIC in modifiers ||
          function.functionKind == FunctionKind.STATIC ||
          function.isAbstract ||
          function.parameters.any { it.isVararg } ||
          function.typeParameters.isNotEmpty() ||
          hasEffectiveAnnotation(function, mixin, JSON_ANY_SETTER)
      ) {
        continue
      }
      val name = resolver.getJvmName(function) ?: function.simpleName.asString()
      val descriptor = descriptor(function) ?: continue
      val method = parseMethodDescriptor(descriptor)
      val annotated = hasEffectiveJsonAnnotation(function, mixin)
      val selected =
        when {
          method.parameters.isEmpty() &&
            method.result != "V" &&
            method.result != "Ljava/lang/Class;" &&
            (annotated || isGetterName(name, method.result)) -> true
          method.parameters.size == 1 &&
            method.result == "V" &&
            (annotated || name.startsWith("set") && name.length > 3) -> true
          else -> false
        }
      if (!selected) continue
      val member =
        JvmMember(
          MemberKind.METHOD,
          binaryName(owner)!!,
          name,
          descriptor,
        )
      val parameterKey = method.parameters.joinToString("") { it.descriptor }
      result["M#$name#$parameterKey"] = member
    }
    return result.values.sortedWith(
      compareBy(JvmMember::name, JvmMember::descriptor, JvmMember::kind)
    )
  }

  private fun isGetterName(name: String, result: String): Boolean =
    name.startsWith("get") && name.length > 3 ||
      name.startsWith("is") && name.length > 2 && (result == "Z" || result == "Ljava/lang/Boolean;")

  private fun javaAnySetter(
    target: KSClassDeclaration,
    mixin: KSClassDeclaration?,
  ): JvmMember? {
    val methods = effectiveMethods(target, mixin, JSON_ANY_SETTER)
    if (methods.size > 1) {
      return fail(methods[1], "At most one effective @JsonAnySetter method is allowed")
    }
    val method = methods.singleOrNull() ?: return null
    val descriptor =
      descriptor(method) ?: return fail(method, "Cannot map @JsonAnySetter to a JVM descriptor")
    val shape = parseMethodDescriptor(descriptor)
    val modifiers = resolver.effectiveJavaModifiers(method)
    if (
      Modifier.PUBLIC !in modifiers ||
        Modifier.JAVA_STATIC in modifiers ||
        method.functionKind == FunctionKind.STATIC ||
        method.isAbstract ||
        method.parameters.any { it.isVararg } ||
        method.typeParameters.isNotEmpty() ||
        shape.parameters.size != 2 ||
        shape.parameters[0].descriptor != "Ljava/lang/String;" ||
        shape.result != "V"
    ) {
      return fail(method, "Invalid effective @JsonAnySetter method")
    }
    val owner = method.parentDeclaration as KSClassDeclaration
    return JvmMember(
      MemberKind.METHOD,
      binaryName(owner)!!,
      resolver.getJvmName(method) ?: method.simpleName.asString(),
      descriptor,
    )
  }

  private fun validators(
    target: KSClassDeclaration,
    mixin: KSClassDeclaration?,
  ): List<JvmMember> {
    val result = ArrayList<JvmMember>()
    for (method in effectiveMethods(target, mixin, JSON_VALIDATOR)) {
      val descriptor = descriptor(method)
      val modifiers = resolver.effectiveJavaModifiers(method)
      if (
        descriptor != "()V" ||
          Modifier.PUBLIC !in modifiers ||
          Modifier.JAVA_STATIC in modifiers ||
          method.functionKind == FunctionKind.STATIC ||
          method.isAbstract ||
          method.parameters.any { it.isVararg } ||
          method.typeParameters.isNotEmpty()
      ) {
        logger.error("Invalid effective @JsonValidator method", method)
        continue
      }
      val owner = method.parentDeclaration as? KSClassDeclaration ?: continue
      result +=
        JvmMember(
          MemberKind.METHOD,
          binaryName(owner)!!,
          resolver.getJvmName(method) ?: method.simpleName.asString(),
          descriptor,
        )
    }
    return result.sortedWith(compareBy(JvmMember::ownerBinaryName, JvmMember::name))
  }

  private fun javaCreator(
    target: KSClassDeclaration,
    mixin: KSClassDeclaration?,
  ): JvmMember? {
    val candidates = ArrayList<KSFunctionDeclaration>()
    target
      .getConstructors()
      .filter { hasEffectiveAnnotation(it, mixin, JSON_CREATOR) }
      .forEach(candidates::add)
    target.declarations
      .filterIsInstance<KSFunctionDeclaration>()
      .filter {
        it.simpleName.asString() != "<init>" && hasEffectiveAnnotation(it, mixin, JSON_CREATOR)
      }
      .forEach(candidates::add)
    if (candidates.size > 1) {
      return fail(candidates[1], "Exactly one effective @JsonCreator is allowed")
    }
    val creator = candidates.singleOrNull() ?: return null
    val descriptor =
      descriptor(creator) ?: return fail(creator, "Cannot map @JsonCreator to a JVM descriptor")
    val method = parseMethodDescriptor(descriptor)
    val factory = creator.simpleName.asString() != "<init>"
    val modifiers = resolver.effectiveJavaModifiers(creator)
    if (
      Modifier.PUBLIC !in modifiers ||
        creator.parameters.any { it.isVararg } ||
        creator.typeParameters.isNotEmpty() ||
        creator.parameters.isEmpty() ||
        factory &&
          (Modifier.JAVA_STATIC !in modifiers && creator.functionKind != FunctionKind.STATIC ||
            method.result != "L${binaryName(target)!!.replace('.', '/')};")
    ) {
      return fail(creator, "Invalid effective @JsonCreator executable")
    }
    return JvmMember(
      MemberKind.METHOD,
      binaryName(target)!!,
      if (factory) resolver.getJvmName(creator) ?: creator.simpleName.asString() else "<init>",
      descriptor,
    )
  }

  private fun effectiveFunctions(target: KSClassDeclaration): List<KSFunctionDeclaration> {
    val result = linkedMapOf<String, KSFunctionDeclaration>()
    for (method in target.getAllFunctions()) {
      if (method.simpleName.asString() == "<init>" || method.origin == Origin.SYNTHETIC) continue
      val descriptor = descriptor(method) ?: continue
      val parsed = parseMethodDescriptor(descriptor)
      val name = resolver.getJvmName(method) ?: method.simpleName.asString()
      val key = name + parsed.parameters.joinToString("") { it.descriptor }
      result.putIfAbsent(key, method)
    }
    return result.values.toList()
  }

  private fun effectiveMethods(
    target: KSClassDeclaration,
    mixin: KSClassDeclaration?,
    annotation: String,
  ): List<KSFunctionDeclaration> =
    effectiveFunctions(target).filter { method ->
      if (mixin == null) hasAnnotation(method, annotation)
      else hasEffectiveAnnotation(method, mixin, annotation)
    }

  private fun hasEffectiveAnnotation(
    target: KSFunctionDeclaration,
    mixin: KSClassDeclaration?,
    annotation: String,
  ): Boolean {
    if (mixin == null) return hasAnnotation(target, annotation)
    val source = matchingMixinFunction(target, mixin)
    if (source != null) {
      if (hasAnnotation(source, annotation)) return true
      if (removesAnnotation(source, annotation)) return false
    }
    return hasAnnotation(target, annotation)
  }

  private fun hasEffectiveJsonAnnotation(
    target: KSFunctionDeclaration,
    mixin: KSClassDeclaration?,
  ): Boolean =
    effectiveAnnotations(target, mixin).any {
      annotationName(it).startsWith(JSON_ANNOTATION_PACKAGE)
    }

  private fun matchingMixinFunction(
    target: KSFunctionDeclaration,
    mixin: KSClassDeclaration,
  ): KSFunctionDeclaration? {
    val key = callableKey(target) ?: return null
    return mixinCallables(mixin).firstOrNull { callableKey(it) == key }
  }

  private fun mixinCallables(mixin: KSClassDeclaration): Sequence<KSFunctionDeclaration> =
    mixin.declarations.filterIsInstance<KSFunctionDeclaration>() +
      mixin.getConstructors() +
      mixin.declarations
        .filterIsInstance<KSClassDeclaration>()
        .filter { it.isCompanionObject }
        .flatMap { companion ->
          companion.declarations.filterIsInstance<KSFunctionDeclaration>().filter {
            hasAnnotation(it, "kotlin.jvm.JvmStatic")
          }
        }

  private fun callableKey(function: KSFunctionDeclaration): String? {
    val descriptor = descriptor(function) ?: return null
    return (resolver.getJvmName(function) ?: function.simpleName.asString()) + descriptor
  }

  private fun effectiveTypeAnnotation(
    target: KSClassDeclaration,
    mixin: KSClassDeclaration,
    annotation: String,
  ): Boolean =
    when {
      hasAnnotation(mixin, annotation) -> true
      removesAnnotation(mixin, annotation) -> false
      else -> hasAnnotation(target, annotation)
    }

  private fun removesAnnotation(source: KSAnnotated, annotation: String): Boolean {
    val removal =
      source.annotations.firstOrNull { annotationName(it) == JSON_MIXIN_REMOVE } ?: return false
    return removal.arguments.any { argument -> containsType(argument.value, annotation) }
  }

  private fun containsType(value: Any?, typeName: String): Boolean =
    when (value) {
      is KSType ->
        (actual(value.declaration) as? KSClassDeclaration)?.qualifiedName?.asString() == typeName
      is Iterable<*> -> value.any { containsType(it, typeName) }
      else -> false
    }

  private fun hasJsonAnnotation(source: KSAnnotated): Boolean =
    source.annotations.any { annotationName(it).startsWith(JSON_ANNOTATION_PACKAGE) }

  private fun mixinMembers(mixin: KSClassDeclaration): List<JvmMember> {
    val result = linkedMapOf<String, JvmMember>()
    val owner = binaryName(mixin) ?: return emptyList()
    fun add(member: JvmMember) {
      result["${member.kind}#${member.name}#${member.descriptor}"] = member
    }
    for (property in mixin.declarations.filterIsInstance<KSPropertyDeclaration>()) {
      val selected =
        hasJsonAnnotation(property) ||
          property.getter?.let(::hasJsonAnnotation) == true ||
          property.setter?.let(::hasJsonAnnotation) == true
      if (!selected) continue
      val type = propertyDescriptor(property)
      if (property.hasBackingField) {
        add(JvmMember(MemberKind.FIELD, owner, property.simpleName.asString(), type.descriptor))
      }
      property.getter?.let { getter ->
        val name = resolver.getJvmName(getter) ?: return@let
        add(
          JvmMember(MemberKind.METHOD, owner, name, methodDescriptor(emptyList(), type.descriptor))
        )
      }
      property.setter?.let { setter ->
        val name = resolver.getJvmName(setter) ?: return@let
        add(JvmMember(MemberKind.METHOD, owner, name, methodDescriptor(listOf(type), "V")))
      }
    }
    for (function in mixinCallables(mixin)) {
      val selected = hasJsonAnnotation(function) || function.parameters.any(::hasJsonAnnotation)
      if (!selected) continue
      val descriptor = descriptor(function) ?: continue
      val name = resolver.getJvmName(function) ?: function.simpleName.asString()
      add(JvmMember(MemberKind.METHOD, owner, name, descriptor))
    }
    return result.values.sortedWith(
      compareBy(JvmMember::name, JvmMember::descriptor, JvmMember::kind)
    )
  }

  private fun members(
    target: KSClassDeclaration,
    creatorTypes: Map<String, JvmType>,
  ): List<JvmMember>? {
    val result = ArrayList<JvmMember>()
    for (property in target.getAllProperties()) {
      if (Modifier.CONST in property.modifiers || property.extensionReceiver != null) continue
      val owner = property.parentDeclaration as? KSClassDeclaration ?: continue
      val ownerName = binaryName(owner) ?: continue
      val carrier = creatorTypes[property.simpleName.asString()] ?: propertyDescriptor(property)
      if (!property.isDelegated() && property.hasBackingField) {
        result +=
          JvmMember(
            MemberKind.FIELD,
            ownerName,
            property.simpleName.asString(),
            carrier.descriptor,
          )
      }
      property.getter?.let { getter ->
        val name =
          resolver.getJvmName(getter)
            ?: return fail(getter, "Cannot determine the JVM getter for ${property.simpleName}")
        result +=
          JvmMember(
            MemberKind.METHOD,
            ownerName,
            name,
            methodDescriptor(emptyList(), carrier.descriptor),
          )
      }
      property.setter?.let { setter ->
        if (property.isMutable && !property.isDelegated()) {
          val name =
            resolver.getJvmName(setter)
              ?: return fail(setter, "Cannot determine the JVM setter for ${property.simpleName}")
          result +=
            JvmMember(
              MemberKind.METHOD,
              ownerName,
              name,
              methodDescriptor(listOf(carrier), "V"),
            )
        }
      }
    }
    return result.sortedWith(compareBy(JvmMember::name, JvmMember::descriptor, JvmMember::kind))
  }

  private fun propertyDescriptor(property: KSPropertyDeclaration): JvmType {
    val descriptor = jvmSignature(property)
    return if (descriptor == null || descriptor == "V" || descriptor == "<ERROR>") {
      propertyCarrier(property.type.resolve())
    } else {
      JvmType(descriptor)
    }
  }

  private fun carrier(type: KSType): JvmType {
    val declaration = actual(type.declaration)
    val name = declaration.qualifiedName?.asString()
    val nullable = type.nullability == Nullability.NULLABLE
    PRIMITIVES[name]?.let { primitive ->
      if (nullable && name in UNSIGNED) return JvmType("L${name!!.replace('.', '/')};")
      return if (nullable) JvmType(BOXES.getValue(primitive)) else JvmType(primitive)
    }
    ARRAYS[name]?.let {
      return JvmType(it)
    }
    if (declaration is com.google.devtools.ksp.symbol.KSTypeParameter) {
      return JvmType("Ljava/lang/Object;")
    }
    val binary =
      JAVA_TYPES[name]
        ?: (declaration as? KSClassDeclaration)?.let(::binaryName)
        ?: "java.lang.Object"
    return JvmType("L${binary.replace('.', '/')};")
  }

  private fun propertyCarrier(type: KSType): JvmType {
    if (type.nullability == Nullability.NULLABLE) return carrier(type)
    val declaration = actual(type.declaration) as? KSClassDeclaration ?: return carrier(type)
    if (!isValueClass(declaration)) return carrier(type)
    val constructor = declaration.primaryConstructor ?: return carrier(type)
    if (constructor.parameters.size != 1) return carrier(type)
    val underlying = constructor.parameters.single().type.resolve()
    return valueClassCarrier(constructor, underlying) ?: carrier(type)
  }

  private fun valueClassCarrier(
    constructor: KSFunctionDeclaration,
    underlying: KSType,
  ): JvmType? {
    val rawDescriptor =
      descriptor(constructor)
        ?: return fail(constructor, "Cannot map Kotlin value-class constructor to a JVM descriptor")
    val normalized = normalizeValueCarriers(rawDescriptor, listOf(carrier(underlying)))
    val method = parseMethodDescriptor(normalized)
    if (
      method.parameters.size != 1 ||
        (method.result != "V" && method.result != method.parameters.single().descriptor)
    ) {
      return fail(constructor, "Kotlin value-class constructor-impl has an invalid JVM shape")
    }
    return method.parameters.single()
  }

  private fun isUnboxedValue(type: KSType, carrier: JvmType): Boolean {
    if (type.nullability == Nullability.NULLABLE) return false
    val declaration = actual(type.declaration) as? KSClassDeclaration ?: return false
    return isValueClass(declaration) &&
      carrier.descriptor != "L${binaryName(declaration)!!.replace('.', '/')};"
  }

  private fun isValueClass(declaration: KSClassDeclaration): Boolean =
    // KSP classpath symbols can omit VALUE, but @JvmInline is binary-retained compiler identity.
    Modifier.VALUE in declaration.modifiers || hasAnnotation(declaration, JVM_INLINE)

  private fun retention(
    target: KSClassDeclaration,
    mixin: KSClassDeclaration?,
  ): Retention {
    val result = Retention()
    if (isKotlin(target.origin) || mixin?.origin?.let(::isKotlin) == true) {
      result.annotations += "kotlin.Metadata"
    }
    for (source in annotationSources(target)) {
      effectiveAnnotations(source, mixin).forEach { collectAnnotation(it, result) }
    }
    if (mixin != null) {
      // JsonMixin and JsonMixinRemove are runtime controls, not effective mapping annotations.
      // Preserve the controls themselves without treating their class-valued selectors as model
      // endpoints or retained application types.
      annotationSources(mixin).forEach { source ->
        source.annotations
          .map(::annotationName)
          .filter { it == JSON_MIXIN || it == JSON_MIXIN_REMOVE }
          .forEach(result.annotations::add)
      }
    }
    collectEndpoints(target, mixin, result)
    return result
  }

  private fun effectiveAnnotations(
    target: KSAnnotated,
    mixin: KSClassDeclaration?,
  ): Sequence<KSAnnotation> = sequence {
    val source = mixin?.let { matchingMixinSource(target, it) }
    val replacements =
      source
        ?.annotations
        ?.filter { annotationName(it) !in CONTROL_ANNOTATIONS }
        ?.associateBy(::annotationName)
        .orEmpty()
    for (annotation in target.annotations) {
      val name = annotationName(annotation)
      if (!name.startsWith(JSON_ANNOTATION_PACKAGE)) continue
      if (name !in replacements && (source == null || !removesAnnotation(source, name))) {
        yield(annotation)
      }
    }
    replacements.values.forEach { yield(it) }
  }

  private fun matchingMixinSource(
    target: KSAnnotated,
    mixin: KSClassDeclaration,
  ): KSAnnotated? =
    when (target) {
      is KSClassDeclaration -> if (target.isCompanionObject) null else mixin
      is KSFunctionDeclaration -> matchingMixinFunction(target, mixin)
      is KSPropertyDeclaration -> matchingMixinProperty(target, mixin)
      is KSPropertyGetter -> matchingMixinProperty(target.receiver, mixin)?.getter
      is KSPropertySetter -> matchingMixinProperty(target.receiver, mixin)?.setter
      is KSValueParameter -> matchingMixinParameter(target, mixin)
      else -> null
    }

  private fun matchingMixinProperty(
    target: KSPropertyDeclaration,
    mixin: KSClassDeclaration,
  ): KSPropertyDeclaration? =
    mixin.declarations.filterIsInstance<KSPropertyDeclaration>().firstOrNull {
      it.simpleName.asString() == target.simpleName.asString() &&
        propertyDescriptor(it) == propertyDescriptor(target)
    }

  private fun matchingMixinParameter(
    target: KSValueParameter,
    mixin: KSClassDeclaration,
  ): KSValueParameter? {
    val owner = target.parent as? KSFunctionDeclaration ?: return null
    val source = matchingMixinFunction(owner, mixin) ?: return null
    val index = owner.parameters.indexOf(target)
    return source.parameters.getOrNull(index)
  }

  private fun collectAnnotation(annotation: KSAnnotation, result: Retention) {
    val name = annotationName(annotation)
    if (!name.startsWith(JSON_ANNOTATION_PACKAGE) || name in CONTROL_ANNOTATIONS) return
    result.annotations += name
    when (name) {
      JSON_CODEC -> collectCodecAnnotation(annotation, result.codecs)
      JSON_BYTE_ARRAY -> {
        val format =
          annotation.arguments.first { it.name?.asString() == "value" }.value as KSClassDeclaration
        result.codecs +=
          if (format.simpleName.asString() == "ARRAY")
            "org.apache.fory.json.codec.ArrayCodec\$SignedByteArrayCodec"
          else BASE64_CODEC
      }
      JSON_SUB_TYPES -> collectSubtypeTypes(annotation, result.types)
      else ->
        annotation.arguments.forEach { argument -> collectTypeValue(argument.value, result.types) }
    }
  }

  private fun collectCodecAnnotation(annotation: KSAnnotation, result: MutableSet<String>) {
    annotation.arguments.forEachIndexed { index, argument ->
      val slot = argument.name?.asString()
      if (slot in CODEC_SLOTS || slot == null && index == 0) {
        collectCodecType(argument.value, result)
      }
    }
  }

  private fun collectSubtypeTypes(annotation: KSAnnotation, result: MutableSet<String>) {
    annotation.arguments.forEach { argument -> collectSubtypeTypeValue(argument.value, result) }
  }

  private fun collectSubtypeTypeValue(value: Any?, result: MutableSet<String>) {
    when (value) {
      is KSType -> {
        val declaration = actual(value.declaration) as? KSClassDeclaration ?: return
        if (declaration.qualifiedName?.asString() != "java.lang.Void") {
          binaryName(declaration)?.let(result::add)
        }
      }
      is KSAnnotation -> {
        if (annotationName(value) == JSON_SUB_TYPE) {
          value.arguments.forEach { argument ->
            when (argument.name?.asString()) {
              "value" -> collectSubtypeTypeValue(argument.value, result)
              "className" ->
                (argument.value as? String)?.takeIf(String::isNotEmpty)?.let(result::add)
            }
          }
        } else {
          value.arguments.forEach { collectSubtypeTypeValue(it.value, result) }
        }
      }
      is Iterable<*> -> value.forEach { collectSubtypeTypeValue(it, result) }
    }
  }

  private fun collectCodecType(value: Any?, result: MutableSet<String>) {
    when (value) {
      is KSType -> {
        val binary = (actual(value.declaration) as? KSClassDeclaration)?.let(::binaryName)
        if (binary != null && binary !in CODEC_SENTINELS) result += binary
      }
      is Iterable<*> -> value.forEach { collectCodecType(it, result) }
    }
  }

  private fun collectEndpoints(
    target: KSClassDeclaration,
    mixin: KSClassDeclaration?,
    result: Retention,
  ) {
    val ownerType = target.asStarProjectedType()
    val endpoints = ArrayList<KSType>()
    for (property in target.getAllProperties()) {
      if (Modifier.CONST in property.modifiers || property.extensionReceiver != null) continue
      endpoints +=
        runCatching { property.asMemberOf(ownerType) }.getOrElse { property.type.resolve() }
    }
    for (function in effectiveFunctions(target)) {
      val descriptor = descriptor(function) ?: continue
      val method = parseMethodDescriptor(descriptor)
      val name = resolver.getJvmName(function) ?: function.simpleName.asString()
      val selected =
        (if (mixin == null) hasJsonAnnotation(function)
        else hasEffectiveJsonAnnotation(function, mixin)) ||
          method.parameters.isEmpty() &&
            method.result != "V" &&
            isGetterName(name, method.result) ||
          method.parameters.size == 1 && method.result == "V" && name.startsWith("set")
      if (!selected) continue
      val member = runCatching { function.asMemberOf(ownerType) }.getOrNull()
      if (member != null && !member.isError) {
        member.returnType?.let(endpoints::add)
        endpoints.addAll(member.parameterTypes.filterNotNull())
      } else {
        function.returnType?.resolve()?.let(endpoints::add)
        function.parameters.mapTo(endpoints) { it.type.resolve() }
      }
    }
    val constructors = target.getConstructors().toList()
    val explicit =
      constructors.filter { constructor ->
        if (mixin == null) hasAnnotation(constructor, JSON_CREATOR)
        else hasEffectiveAnnotation(constructor, mixin, JSON_CREATOR)
      }
    val selectedConstructors =
      if (explicit.isNotEmpty() || !isKotlin(target.origin)) explicit
      else listOfNotNull(target.primaryConstructor)
    selectedConstructors.forEach { constructor ->
      val member = runCatching { constructor.asMemberOf(ownerType) }.getOrNull()
      if (member != null && !member.isError) {
        endpoints.addAll(member.parameterTypes.filterNotNull())
      } else constructor.parameters.mapTo(endpoints) { it.type.resolve() }
    }
    kotlinFactories(target)
      .filter { factory ->
        if (mixin == null) hasAnnotation(factory, JSON_CREATOR)
        else hasEffectiveAnnotation(factory, mixin, JSON_CREATOR)
      }
      .forEach { factory -> factory.parameters.mapTo(endpoints) { it.type.resolve() } }
    val visited = hashSetOf<String>()
    endpoints.forEach { collectEndpoint(it, target, mixin, result, visited) }
  }

  private fun collectEndpoint(
    type: KSType,
    target: KSClassDeclaration,
    mixin: KSClassDeclaration?,
    result: Retention,
    visited: MutableSet<String>,
  ) {
    if (type.isError || !visited.add(type.toString())) return
    val declaration = actual(type.declaration) as? KSClassDeclaration
    if (declaration != null) {
      collectEndpointCodec(declaration, target, mixin, result)
      if (isConcreteContainer(declaration, type)) {
        binaryName(declaration)?.let(result.containers::add)
      }
    }
    type.arguments.forEach { argument ->
      argument.type?.resolve()?.let { collectEndpoint(it, target, mixin, result, visited) }
    }
  }

  private fun collectEndpointCodec(
    declaration: KSClassDeclaration,
    target: KSClassDeclaration,
    mixin: KSClassDeclaration?,
    result: Retention,
  ) {
    val direct =
      if (declaration == target) effectiveAnnotation(declaration, mixin, JSON_CODEC)
      else declaration.annotations.firstOrNull { annotationName(it) == JSON_CODEC }
    if (direct != null) {
      collectEndpointCodecDeclaration(declaration, direct, target, result)
      return
    }
    // A type-level removal masks inherited JsonCodec declarations for this exact target, matching
    // the runtime overlay. Without this stop, endpoint traversal would resurrect the removed codec.
    if (declaration == target && mixin != null && removesAnnotation(mixin, JSON_CODEC)) return
    declaration.getAllSuperTypes().forEach { supertype ->
      val owner = actual(supertype.declaration) as? KSClassDeclaration ?: return@forEach
      owner.annotations
        .firstOrNull { annotationName(it) == JSON_CODEC }
        ?.let { annotation -> collectEndpointCodecDeclaration(owner, annotation, target, result) }
    }
  }

  private fun collectEndpointCodecDeclaration(
    owner: KSClassDeclaration,
    annotation: KSAnnotation,
    target: KSClassDeclaration,
    result: Retention,
  ) {
    result.annotations += JSON_CODEC
    collectCodecAnnotation(annotation, result.codecs)
    if (owner != target) binaryName(owner)?.let(result.annotationOwners::add)
  }

  private fun effectiveAnnotation(
    target: KSAnnotated,
    mixin: KSClassDeclaration?,
    name: String,
  ): KSAnnotation? = effectiveAnnotations(target, mixin).firstOrNull { annotationName(it) == name }

  private fun isConcreteContainer(declaration: KSClassDeclaration, type: KSType): Boolean {
    if (
      declaration.classKind == ClassKind.INTERFACE || Modifier.ABSTRACT in declaration.modifiers
    ) {
      return false
    }
    return CONTAINER_TYPES.any { name ->
      resolver
        .getClassDeclarationByName(resolver.getKSNameFromString(name))
        ?.asStarProjectedType()
        ?.isAssignableFrom(type.starProjection()) == true
    }
  }

  private fun annotationSources(target: KSClassDeclaration): Sequence<KSAnnotated> = sequence {
    yield(target)
    target.getConstructors().forEach { constructor ->
      yield(constructor)
      constructor.parameters.forEach { yield(it) }
    }
    target.getAllProperties().forEach { property ->
      yield(property)
      property.getter?.let { yield(it) }
      property.setter?.let { setter ->
        yield(setter)
        yield(setter.parameter)
      }
    }
    effectiveFunctions(target).forEach { function ->
      yield(function)
      function.parameters.forEach { yield(it) }
    }
    target.declarations
      .filterIsInstance<KSClassDeclaration>()
      .filter { it.isCompanionObject }
      .forEach { companion ->
        yield(companion)
        companion.declarations.filterIsInstance<KSFunctionDeclaration>().forEach { function ->
          yield(function)
          function.parameters.forEach { yield(it) }
        }
      }
  }

  private fun collectTypeValue(value: Any?, result: MutableSet<String>) {
    when (value) {
      is KSType ->
        (actual(value.declaration) as? KSClassDeclaration)?.let(::binaryName)?.let(result::add)
      is KSAnnotation -> value.arguments.forEach { collectTypeValue(it.value, result) }
      is Iterable<*> -> value.forEach { collectTypeValue(it, result) }
    }
  }

  private fun descriptor(declaration: KSDeclaration): String? {
    val signature = jvmSignature(declaration) ?: return null
    val start = signature.indexOf('(')
    return if (start < 0) null else signature.substring(start)
  }

  private fun jvmSignature(declaration: KSDeclaration): String? =
    try {
      resolver.mapToJvmSignature(declaration)
    } catch (_: RuntimeException) {
      null
    }

  private fun hasAnnotation(source: KSAnnotated, name: String): Boolean =
    source.annotations.any { annotationName(it) == name }

  private fun annotationName(annotation: KSAnnotation): String =
    annotation.annotationType.resolve().declaration.qualifiedName?.asString().orEmpty()

  private fun actual(declaration: KSDeclaration): KSDeclaration =
    if (declaration is KSTypeAlias) actual(declaration.type.resolve().declaration) else declaration

  private fun binaryName(declaration: KSClassDeclaration): String? {
    val parent = declaration.parentDeclaration as? KSClassDeclaration
    return if (parent == null) declaration.qualifiedName?.asString()
    else binaryName(parent)?.let { "$it\$${declaration.simpleName.asString()}" }
  }

  private fun <T> fail(node: KSNode, message: String): T? {
    logger.error(message, node)
    return null
  }

  private companion object {
    val SOURCE_ORIGINS = setOf(Origin.KOTLIN, Origin.JAVA)
    val JAVA_ORIGINS = setOf(Origin.JAVA, Origin.JAVA_LIB)
    const val JVM_INLINE = "kotlin.jvm.JvmInline"
    val CONTROL_ANNOTATIONS = setOf(JSON_MIXIN, JSON_MIXIN_REMOVE)
    val CONTAINER_TYPES = setOf("java.util.Collection", "java.util.Map")
    val CODEC_SLOTS = setOf("value", "elementCodec", "contentCodec", "keyCodec", "valueCodec")
    val CODEC_SENTINELS =
      setOf(
        "org.apache.fory.json.annotation.JsonCodec\$NoJsonValueCodec",
        "org.apache.fory.json.annotation.JsonCodec\$NoMapKeyCodec",
      )
    val PRIMITIVES =
      mapOf(
        "kotlin.Boolean" to "Z",
        "kotlin.Byte" to "B",
        "kotlin.Short" to "S",
        "kotlin.Int" to "I",
        "kotlin.Long" to "J",
        "kotlin.Float" to "F",
        "kotlin.Double" to "D",
        "kotlin.Char" to "C",
        "kotlin.UByte" to "B",
        "kotlin.UShort" to "S",
        "kotlin.UInt" to "I",
        "kotlin.ULong" to "J",
      )
    val UNSIGNED = setOf("kotlin.UByte", "kotlin.UShort", "kotlin.UInt", "kotlin.ULong")
    val BOXES =
      mapOf(
        "Z" to "Ljava/lang/Boolean;",
        "B" to "Ljava/lang/Byte;",
        "S" to "Ljava/lang/Short;",
        "I" to "Ljava/lang/Integer;",
        "J" to "Ljava/lang/Long;",
        "F" to "Ljava/lang/Float;",
        "D" to "Ljava/lang/Double;",
        "C" to "Ljava/lang/Character;",
      )
    val ARRAYS =
      mapOf(
        "kotlin.BooleanArray" to "[Z",
        "kotlin.ByteArray" to "[B",
        "kotlin.ShortArray" to "[S",
        "kotlin.IntArray" to "[I",
        "kotlin.LongArray" to "[J",
        "kotlin.FloatArray" to "[F",
        "kotlin.DoubleArray" to "[D",
        "kotlin.CharArray" to "[C",
        "kotlin.UByteArray" to "[B",
        "kotlin.UShortArray" to "[S",
        "kotlin.UIntArray" to "[I",
        "kotlin.ULongArray" to "[J",
      )
    val JAVA_TYPES =
      mapOf(
        "kotlin.Any" to "java.lang.Object",
        "kotlin.String" to "java.lang.String",
        "kotlin.CharSequence" to "java.lang.CharSequence",
        "kotlin.Throwable" to "java.lang.Throwable",
        "kotlin.Nothing" to "java.lang.Void",
        "kotlin.collections.Iterable" to "java.lang.Iterable",
        "kotlin.collections.Collection" to "java.util.Collection",
        "kotlin.collections.MutableCollection" to "java.util.Collection",
        "kotlin.collections.List" to "java.util.List",
        "kotlin.collections.MutableList" to "java.util.List",
        "kotlin.collections.Set" to "java.util.Set",
        "kotlin.collections.MutableSet" to "java.util.Set",
        "kotlin.collections.Map" to "java.util.Map",
        "kotlin.collections.MutableMap" to "java.util.Map",
      )
  }
}
