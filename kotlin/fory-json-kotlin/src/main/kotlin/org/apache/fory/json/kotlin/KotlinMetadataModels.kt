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

package org.apache.fory.json.kotlin

import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.LinkedHashMap
import kotlin.ExperimentalContextParameters
import kotlin.metadata.ClassKind
import kotlin.metadata.ExperimentalContextReceivers
import kotlin.metadata.KmClass
import kotlin.metadata.KmClassifier
import kotlin.metadata.KmConstructor
import kotlin.metadata.KmFunction
import kotlin.metadata.KmProperty
import kotlin.metadata.KmType
import kotlin.metadata.KmTypeProjection
import kotlin.metadata.KmValueParameter
import kotlin.metadata.KmVariance
import kotlin.metadata.Visibility
import kotlin.metadata.declaresDefaultValue
import kotlin.metadata.isConst
import kotlin.metadata.isDefinitelyNonNull
import kotlin.metadata.isDelegated
import kotlin.metadata.isInner
import kotlin.metadata.isLateinit
import kotlin.metadata.isNullable
import kotlin.metadata.isSecondary
import kotlin.metadata.isSuspend
import kotlin.metadata.isValue
import kotlin.metadata.isVar
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.jvm.fieldSignature
import kotlin.metadata.jvm.getterSignature
import kotlin.metadata.jvm.setterSignature
import kotlin.metadata.jvm.signature
import kotlin.metadata.kind
import kotlin.metadata.visibility
import org.apache.fory.json.ForyJsonException
import org.apache.fory.json.codec.JsonObjectModel
import org.apache.fory.json.meta.JsonCreatorDeclaration
import org.apache.fory.meta.TypeExtMeta
import org.apache.fory.reflect.TypeRef
import org.apache.fory.type.TypeUtils
import org.apache.fory.type.Types

/** Strict cold-path translation from Kotlin class metadata to the standard JSON object model. */
@OptIn(ExperimentalContextParameters::class)
internal object KotlinMetadataModels {
  fun objectModel(
    ownerType: TypeRef<*>,
    creatorDeclarations: List<JsonCreatorDeclaration> = emptyList(),
  ): JsonObjectModel {
    val rawType = ownerType.rawType
    val model = readClass(rawType)
    if (rawType.isAnonymousClass || rawType.isLocalClass || rawType.isSynthetic) {
      unsupported(rawType, "local, anonymous, and synthetic classes have no stable schema")
    }
    if (!Modifier.isPublic(rawType.modifiers)) {
      unsupported(rawType, "model class is not JVM-public")
    }
    if (model.isInner) unsupported(rawType, "inner classes need an outer instance")
    if (hasImplicitContext(model)) {
      unsupported(rawType, "context receivers have no JSON argument source")
    }
    if (model.isValue) unsupported(rawType, "value classes use the value codec")
    return when (model.kind) {
      ClassKind.OBJECT -> singletonModel(ownerType, rawType, model, creatorDeclarations)
      ClassKind.COMPANION_OBJECT -> unsupported(rawType, "companion objects have no value schema")
      ClassKind.INTERFACE ->
        unsupported(rawType, "interfaces require @JsonSubTypes or a custom codec")
      ClassKind.ANNOTATION_CLASS -> unsupported(rawType, "annotation classes have no value schema")
      ClassKind.ENUM_CLASS,
      ClassKind.ENUM_ENTRY -> unsupported(rawType, "enum classes use the core enum codec")
      ClassKind.CLASS -> classModel(ownerType, rawType, model, creatorDeclarations)
    }
  }

  private fun singletonModel(
    ownerType: TypeRef<*>,
    rawType: Class<*>,
    model: KmClass,
    creators: List<JsonCreatorDeclaration>,
  ): JsonObjectModel {
    if (creators.isNotEmpty()) unsupported(rawType, "a singleton cannot declare a JSON creator")
    val properties = properties(ownerType, model)
    val instanceField =
      try {
        rawType.getField("INSTANCE")
      } catch (cause: ReflectiveOperationException) {
        throw ForyJsonException(
          "Unsupported Kotlin singleton ${rawType.name}: missing INSTANCE",
          cause
        )
      }
    if (
      !Modifier.isPublic(instanceField.modifiers) ||
        !Modifier.isStatic(instanceField.modifiers) ||
        !Modifier.isFinal(instanceField.modifiers) ||
        instanceField.declaringClass != rawType ||
        instanceField.isSynthetic ||
        instanceField.type != rawType
    ) {
      unsupported(rawType, "missing exact singleton INSTANCE field")
    }
    val instance =
      try {
        instanceField.get(null)
      } catch (cause: ReflectiveOperationException) {
        throw ForyJsonException(
          "Unsupported Kotlin singleton ${rawType.name}: inaccessible INSTANCE",
          cause
        )
      }
    return JsonObjectModel.fixedInstance(
      instance,
      properties.map { it.name }.toTypedArray(),
      properties.map { it.getter }.toTypedArray(),
      properties.map { it.setter }.toTypedArray(),
      properties.map { it.type }.toTypedArray(),
    )
  }

  private fun classModel(
    ownerType: TypeRef<*>,
    rawType: Class<*>,
    model: KmClass,
    creators: List<JsonCreatorDeclaration>,
  ): JsonObjectModel {
    if (Modifier.isAbstract(rawType.modifiers) || rawType.isInterface) {
      unsupported(rawType, "abstract models require @JsonSubTypes or a custom codec")
    }
    val creator = selectCreator(ownerType, rawType, model, creators)
    val properties = properties(ownerType, model)
    val declaredProperties =
      properties.filter { it.declaringType == rawType }.associateBy { it.name }
    val substitutions = KotlinMetadataTypes.substitutions(ownerType, model)
    val parameters = creator.parameters
    val names = Array(parameters.size) { parameters[it].name }
    val parameterTypes =
      Array<TypeRef<*>>(parameters.size) {
        KotlinMetadataTypes.resolve(parameters[it].type, rawType.classLoader, substitutions, false)
      }
    val parameterNullable = BooleanArray(parameters.size) { nullable(parameterTypes[it]) }
    val defaultMaskBits =
      IntArray(parameters.size) { if (parameters[it].declaresDefaultValue) it else -1 }
    val accessors = arrayOfNulls<Method>(parameters.size)
    if (creator.primary) {
      for (index in parameters.indices) {
        val property = declaredProperties[names[index]]
        if (property != null && property.type == parameterTypes[index]) {
          if (creators.isEmpty() && property.getter == null && !property.fieldReadable) {
            unsupported(rawType, "primary property ${names[index]} is not publicly readable")
          }
          accessors[index] = property.getter
        } else if (creators.isEmpty()) {
          unsupported(rawType, "primary parameter ${names[index]} is not an exact property")
        }
      }
    }
    return JsonObjectModel(
      creator.executable,
      creator.invocation,
      creator.defaultConstructor,
      names,
      accessors,
      arrayOfNulls(parameters.size),
      defaultMaskBits,
      parameterNullable,
      parameterTypes,
      properties.map { it.name }.toTypedArray(),
      properties.map { it.getter }.toTypedArray(),
      properties.map { it.setter }.toTypedArray(),
      properties.map { it.type }.toTypedArray(),
      BooleanArray(properties.size) { properties[it].reconstructible },
      BooleanArray(properties.size) { properties[it].required },
    )
  }

  private fun selectCreator(
    ownerType: TypeRef<*>,
    rawType: Class<*>,
    model: KmClass,
    declarations: List<JsonCreatorDeclaration>,
  ): CreatorMetadata {
    if (declarations.isEmpty()) {
      val primary =
        model.constructors.singleOrNull { !it.isSecondary }
          ?: unsupported(rawType, "missing unique primary constructor")
      return constructorMetadata(rawType, primary, true, null)
    }
    val exact = ArrayList<CreatorMetadata>(1)
    for (declaration in declarations) {
      val executable = declaration.executable()
      when (executable) {
        is Constructor<*> -> {
          if (executable.declaringClass != rawType) continue
          val descriptor = KotlinMetadataTypes.constructorDescriptor(executable)
          val source = model.constructors.singleOrNull { it.signature?.descriptor == descriptor }
          if (source != null) {
            exact += constructorMetadata(rawType, source, !source.isSecondary, executable)
          }
        }
        is Method -> {
          factoryMetadataOrNull(ownerType, rawType, model, executable)?.let { exact += it }
        }
      }
    }
    return exact.singleOrNull()
      ?: unsupported(
        rawType,
        "effective @JsonCreator does not select one logical Kotlin declaration"
      )
  }

  private fun constructorMetadata(
    rawType: Class<*>,
    source: KmConstructor,
    primary: Boolean,
    selected: Constructor<*>?,
  ): CreatorMetadata {
    val signature =
      source.signature ?: unsupported(rawType, "selected constructor has no JVM signature")
    val selectedConstructor = findConstructor(rawType, signature.descriptor)
    val constructor = logicalConstructor(rawType, selectedConstructor, source.valueParameters.size)
    val sourceAccessible =
      source.visibility == Visibility.PUBLIC || source.visibility == Visibility.INTERNAL
    if (!sourceAccessible && !isAccessibilityConstructor(selectedConstructor, constructor)) {
      unsupported(rawType, "selected constructor is not source-public")
    }
    // A source-public constructor with direct value-class parameters is annotated on its public
    // synthetic accessibility constructor. The metadata signature names that exact bridge; copied
    // @JvmOverloads prefixes have no KmConstructor signature and never reach this comparison.
    if (selected != null && selected != selectedConstructor) {
      unsupported(rawType, "selected constructor is a compiler-derived overload")
    }
    if (
      constructor.isSynthetic || constructor.isVarArgs || constructor.typeParameters.isNotEmpty()
    ) {
      unsupported(rawType, "selected constructor is not an exact JVM declaration")
    }
    val invocation =
      if (selectedConstructor.parameterCount != constructor.parameterCount) selectedConstructor
      else if (Modifier.isPublic(constructor.modifiers)) constructor
      else findInvocationConstructor(rawType, constructor)
    val defaultConstructor =
      if (source.valueParameters.any { it.declaresDefaultValue }) {
        findDefaultConstructor(rawType, constructor)
      } else {
        null
      }
    return CreatorMetadata(
      constructor,
      invocation,
      defaultConstructor,
      source.valueParameters,
      primary,
    )
  }

  private fun factoryMetadataOrNull(
    ownerType: TypeRef<*>,
    rawType: Class<*>,
    model: KmClass,
    factory: Method,
  ): CreatorMetadata? {
    if (factory.declaringClass != rawType || factory.returnType != rawType) return null
    val companionName = model.companionObject ?: return null
    val companionType =
      try {
        Class.forName("${rawType.name}\$$companionName", false, rawType.classLoader)
      } catch (_: ClassNotFoundException) {
        return null
      }
    val companion = readClass(companionType)
    val descriptor = KotlinMetadataTypes.methodDescriptor(factory)
    val source =
      companion.functions.singleOrNull {
        it.signature?.name == factory.name && it.signature?.descriptor == descriptor
      } ?: return null
    validateFactory(rawType, factory, source)
    if (source.valueParameters.any { it.declaresDefaultValue }) {
      unsupported(rawType, "a selected static factory cannot declare compiler defaults")
    }
    val returnType =
      KotlinMetadataTypes.resolve(source.returnType, companionType.classLoader, emptyMap(), false)
    if (!exactFactoryOwner(ownerType, returnType)) {
      unsupported(rawType, "selected static factory does not return its exact non-null owner")
    }
    return CreatorMetadata(factory, factory, null, source.valueParameters, false)
  }

  private fun exactFactoryOwner(ownerType: TypeRef<*>, returnType: TypeRef<*>): Boolean {
    if (nullable(returnType)) return false
    if (ownerType.typeExtMeta == null) {
      return ownerType.rawType.typeParameters.isEmpty() && ownerType.rawType == returnType.rawType
    }
    return returnType == KotlinMetadataTypes.withOccurrence(ownerType, false, false)
  }

  private fun validateFactory(rawType: Class<*>, factory: Method, source: KmFunction) {
    val modifiers = factory.modifiers
    if (
      !Modifier.isPublic(modifiers) ||
        !Modifier.isStatic(modifiers) ||
        factory.isSynthetic ||
        factory.isBridge ||
        factory.isVarArgs ||
        factory.typeParameters.isNotEmpty() ||
        source.visibility != Visibility.PUBLIC && source.visibility != Visibility.INTERNAL ||
        source.receiverParameterType != null ||
        hasImplicitContext(source) ||
        source.isSuspend ||
        source.typeParameters.isNotEmpty()
    ) {
      unsupported(rawType, "selected factory is not an exact public @JvmStatic declaration")
    }
  }

  private fun properties(ownerType: TypeRef<*>, model: KmClass): List<PropertyMetadata> {
    val candidates = LinkedHashMap<String, MutableList<PropertyMetadata>>()
    collectProperties(
      PropertyOwner(
        ownerType.rawType,
        KotlinMetadataTypes.substitutions(ownerType, model),
      ),
      model,
      candidates,
      HashSet(),
      HashSet(),
    )
    return candidates.map { (name, declarations) ->
      declarations.singleOrNull { candidate ->
        declarations.all { it == candidate || overrides(it, candidate) }
      }
        ?: unsupported(
          ownerType.rawType,
          "ambiguous inherited property $name from " +
            declarations.joinToString { it.declaringType.name },
        )
    }
  }

  private fun collectProperties(
    owner: PropertyOwner,
    model: KmClass,
    properties: LinkedHashMap<String, MutableList<PropertyMetadata>>,
    active: MutableSet<PropertyOwner>,
    visited: MutableSet<PropertyOwner>,
  ) {
    if (!active.add(owner)) {
      throw ForyJsonException("Recursive Kotlin class hierarchy at ${owner.rawType.name}")
    }
    try {
      if (!visited.add(owner)) return
      for (supertype in model.supertypes) {
        val superType =
          KotlinMetadataTypes.supertype(
            supertype,
            owner.rawType.classLoader,
            owner.substitutions,
          )
        if (superType.rawType == Any::class.java) continue
        val superModel = readClassOrNull(superType.rawType) ?: continue
        if (superModel.kind == ClassKind.CLASS || superModel.kind == ClassKind.INTERFACE) {
          collectProperties(
            PropertyOwner(
              superType.rawType,
              KotlinMetadataTypes.superSubstitutions(superModel, superType),
            ),
            superModel,
            properties,
            active,
            visited,
          )
        }
      }
      for (property in model.properties) {
        if (property.isConst) continue
        val candidate = propertyMetadata(owner.rawType, property, owner.substitutions)
        properties.getOrPut(candidate.name) { ArrayList(1) }.add(candidate)
      }
    } finally {
      active.remove(owner)
    }
  }

  private fun propertyMetadata(
    declaringType: Class<*>,
    property: KmProperty,
    substitutions: Map<Int, TypeRef<*>>,
  ): PropertyMetadata {
    val type =
      KotlinMetadataTypes.resolve(
        property.returnType,
        declaringType.classLoader,
        substitutions,
        false
      )
    val instance = property.receiverParameterType == null && !hasImplicitContext(property)
    val getter = if (instance) propertyMethod(declaringType, property.getterSignature) else null
    val setter =
      if (instance && property.isVar && !property.isDelegated) {
        propertyMethod(declaringType, property.setterSignature)
      } else {
        null
      }
    val field =
      if (instance && !property.isDelegated) {
        publicField(declaringType, property)
      } else {
        null
      }
    val reconstructible =
      property.fieldSignature != null &&
        (getter != null && setter != null ||
          property.isVar && field != null && !Modifier.isFinal(field.modifiers))
    val required = property.isLateinit
    if (required && (!reconstructible || nullable(type))) {
      unsupported(declaringType, "lateinit property ${property.name} is not an exact non-null var")
    }
    return PropertyMetadata(
      property.name,
      declaringType,
      type,
      getter,
      setter,
      field != null,
      reconstructible,
      required,
    )
  }

  private fun propertyMethod(
    declaringType: Class<*>,
    signature: kotlin.metadata.jvm.JvmMethodSignature?,
  ): Method? {
    if (signature == null) return null
    val method =
      declaringType.declaredMethods.singleOrNull {
        it.name == signature.name &&
          KotlinMetadataTypes.methodDescriptor(it) == signature.descriptor
      } ?: unsupported(declaringType, "method $signature was not found exactly")
    val modifiers = method.modifiers
    return if (
      Modifier.isPublic(modifiers) &&
        !Modifier.isStatic(modifiers) &&
        !method.isBridge &&
        !method.isSynthetic
    )
      method
    else null
  }

  private fun publicField(
    declaringType: Class<*>,
    property: KmProperty,
  ): Field? {
    val signature = property.fieldSignature ?: return null
    if (signature.name != property.name) return null
    val field =
      declaringType.declaredFields.singleOrNull {
        it.name == signature.name && KotlinMetadataTypes.descriptor(it.type) == signature.descriptor
      } ?: unsupported(declaringType, "field $signature was not found exactly")
    val modifiers = field.modifiers
    return if (Modifier.isPublic(modifiers) && !Modifier.isStatic(modifiers) && !field.isSynthetic)
      field
    else null
  }

  private fun overrides(prior: PropertyMetadata, current: PropertyMetadata): Boolean {
    if (prior.declaringType == current.declaringType) {
      return prior.type == current.type && prior.getter == current.getter
    }
    if (!prior.declaringType.isAssignableFrom(current.declaringType)) return false
    val priorGetter = prior.getter ?: return false
    val currentGetter = current.getter ?: return false
    return priorGetter.name == currentGetter.name &&
      priorGetter.parameterTypes.contentEquals(currentGetter.parameterTypes)
  }

  private fun readClass(type: Class<*>): KmClass {
    return KotlinMetadataTypes.classMetadata(type).kmClass
  }

  // Kotlin metadata 2.3 encodes contextParameters on functions and properties, but KmClass still
  // exposes only its deprecated contextReceiverTypes slot. Keep that one class-level ABI read in
  // this cold helper until Kotlin metadata removes the contextual-class format.
  @OptIn(ExperimentalContextReceivers::class)
  @Suppress("DEPRECATION")
  internal fun hasImplicitContext(declaration: Any): Boolean =
    when (declaration) {
      is KmClass -> declaration.contextReceiverTypes.isNotEmpty()
      is KmFunction -> declaration.contextParameters.isNotEmpty()
      is KmProperty -> declaration.contextParameters.isNotEmpty()
      else -> error("Unsupported Kotlin metadata declaration ${declaration::class.java.name}")
    }

  private fun readClassOrNull(type: Class<*>): KmClass? =
    if (type.getAnnotation(Metadata::class.java) == null) null else readClass(type)

  private fun findConstructor(rawType: Class<*>, descriptor: String): Constructor<*> =
    rawType.declaredConstructors.singleOrNull {
      KotlinMetadataTypes.constructorDescriptor(it) == descriptor
    } ?: unsupported(rawType, "constructor descriptor $descriptor was not found exactly")

  private fun logicalConstructor(
    rawType: Class<*>,
    selected: Constructor<*>,
    logicalCount: Int,
  ): Constructor<*> {
    if (selected.parameterCount == logicalCount) return selected
    val selectedTypes = selected.parameterTypes
    if (
      !Modifier.isPublic(selected.modifiers) ||
        !selected.isSynthetic ||
        selectedTypes.size != logicalCount + 1 ||
        selectedTypes.last().name != "kotlin.jvm.internal.DefaultConstructorMarker"
    ) {
      unsupported(rawType, "selected JVM constructor has an invalid accessibility shape")
    }
    return rawType.declaredConstructors.singleOrNull { candidate ->
      val candidateTypes = candidate.parameterTypes
      candidateTypes.size == logicalCount &&
        candidateTypes.indices.all { candidateTypes[it] == selectedTypes[it] }
    } ?: unsupported(rawType, "logical constructor was not found exactly")
  }

  private fun isAccessibilityConstructor(
    selected: Constructor<*>,
    logical: Constructor<*>,
  ): Boolean =
    selected !== logical &&
      Modifier.isPublic(selected.modifiers) &&
      selected.isSynthetic &&
      !logical.isSynthetic &&
      selected.parameterTypes.size == logical.parameterTypes.size + 1 &&
      logical.parameterTypes.indices.all {
        selected.parameterTypes[it] == logical.parameterTypes[it]
      } &&
      selected.parameterTypes.last().name == "kotlin.jvm.internal.DefaultConstructorMarker"

  private fun findDefaultConstructor(
    rawType: Class<*>,
    constructor: Constructor<*>
  ): Constructor<*> {
    val parameters = constructor.parameterTypes
    val maskCount = (parameters.size + 31) ushr 5
    return rawType.declaredConstructors.singleOrNull { candidate ->
      val candidateTypes = candidate.parameterTypes
      candidate.isSynthetic &&
        candidateTypes.size == parameters.size + maskCount + 1 &&
        parameters.indices.all { candidateTypes[it] == parameters[it] } &&
        (0 until maskCount).all {
          candidateTypes[parameters.size + it] == Int::class.javaPrimitiveType
        } &&
        candidateTypes.last().name == "kotlin.jvm.internal.DefaultConstructorMarker"
    } ?: unsupported(rawType, "compiler-default constructor shape was not found exactly")
  }

  private fun findInvocationConstructor(
    rawType: Class<*>,
    constructor: Constructor<*>,
  ): Constructor<*> {
    val parameters = constructor.parameterTypes
    return rawType.declaredConstructors.singleOrNull { candidate ->
      val candidateTypes = candidate.parameterTypes
      Modifier.isPublic(candidate.modifiers) &&
        candidate.isSynthetic &&
        candidateTypes.size == parameters.size + 1 &&
        parameters.indices.all { candidateTypes[it] == parameters[it] } &&
        candidateTypes.last().name == "kotlin.jvm.internal.DefaultConstructorMarker"
    } ?: unsupported(rawType, "public compiler accessibility constructor was not found exactly")
  }

  private fun nullable(type: TypeRef<*>): Boolean =
    type.typeExtMeta?.nullable()
      ?: throw ForyJsonException("Kotlin JSON occurrence has no nullability: ${type.type}")

  private fun unsupported(type: Class<*>, reason: String): Nothing =
    throw ForyJsonException("Unsupported Kotlin JSON model ${type.name}: $reason")

  private data class CreatorMetadata(
    val executable: Executable,
    val invocation: Executable,
    val defaultConstructor: Constructor<*>?,
    val parameters: List<KmValueParameter>,
    val primary: Boolean,
  )

  private data class PropertyMetadata(
    val name: String,
    val declaringType: Class<*>,
    val type: TypeRef<*>,
    val getter: Method?,
    val setter: Method?,
    val fieldReadable: Boolean,
    val reconstructible: Boolean,
    val required: Boolean,
  )

  private data class PropertyOwner(
    val rawType: Class<*>,
    val substitutions: Map<Int, TypeRef<*>>,
  )
}

/** Single owner of strict Kotlin metadata decoding, substitution, and JSON type tokens. */
internal object KotlinMetadataTypes {
  fun classMetadata(type: Class<*>): KotlinClassMetadata.Class {
    val annotation =
      type.getAnnotation(Metadata::class.java)
        ?: throw ForyJsonException("Unsupported Kotlin metadata on ${type.name}: missing @Metadata")
    val metadata =
      try {
        KotlinClassMetadata.readStrict(annotation)
      } catch (cause: IllegalArgumentException) {
        throw ForyJsonException("Unsupported Kotlin metadata on ${type.name}", cause)
      }
    if (metadata !is KotlinClassMetadata.Class) {
      throw ForyJsonException(
        "Unsupported Kotlin metadata on ${type.name}: not a class declaration",
      )
    }
    val version = metadata.version
    if (version.major != 2 || version.minor != 3) {
      throw ForyJsonException(
        "Unsupported Kotlin metadata on ${type.name}: ABI $version; expected 2.3",
      )
    }
    return metadata
  }

  fun constructorDescriptor(constructor: Constructor<*>): String =
    descriptor(constructor.parameterTypes, Void.TYPE)

  fun methodDescriptor(method: Method): String =
    descriptor(method.parameterTypes, method.returnType)

  fun descriptor(parameters: Array<Class<*>>, result: Class<*>): String = buildString {
    append('(')
    parameters.forEach { append(descriptor(it)) }
    append(')')
    append(descriptor(result))
  }

  fun descriptor(type: Class<*>): String =
    when {
      type.isArray -> type.name.replace('.', '/')
      !type.isPrimitive -> "L${type.name.replace('.', '/')};"
      type == Void.TYPE -> "V"
      type == java.lang.Boolean.TYPE -> "Z"
      type == java.lang.Byte.TYPE -> "B"
      type == java.lang.Short.TYPE -> "S"
      type == java.lang.Integer.TYPE -> "I"
      type == java.lang.Long.TYPE -> "J"
      type == java.lang.Float.TYPE -> "F"
      type == java.lang.Double.TYPE -> "D"
      type == java.lang.Character.TYPE -> "C"
      else -> error("Unsupported primitive carrier $type")
    }

  fun substitutions(ownerType: TypeRef<*>, model: KmClass): Map<Int, TypeRef<*>> {
    if (model.typeParameters.isEmpty()) return emptyMap()
    val arguments = ownerType.typeArguments
    if (arguments.isEmpty()) return emptyMap()
    if (arguments.size != model.typeParameters.size) {
      throw ForyJsonException(
        "Kotlin generic model ${ownerType.type} requires exact type arguments"
      )
    }
    return model.typeParameters.indices.associate { model.typeParameters[it].id to arguments[it] }
  }

  fun supertype(
    type: KmType,
    loader: ClassLoader?,
    substitutions: Map<Int, TypeRef<*>>,
  ): SupertypeMetadata {
    if (type.flexibleTypeUpperBound != null) {
      throw ForyJsonException("Kotlin platform supertypes require an exact custom JSON codec")
    }
    val classifier = type.classifier
    if (classifier !is KmClassifier.Class) {
      throw ForyJsonException("Unsupported Kotlin JSON supertype classifier $classifier")
    }
    val rawType = classFor(classifier.name, loader)
    val arguments = ArrayList<TypeRef<*>?>(type.arguments.size)
    for (projection in type.arguments) {
      arguments +=
        if (dependsOnMissing(projection, substitutions)) null
        else resolveProjection(projection, loader, substitutions)
    }
    return SupertypeMetadata(rawType, arguments)
  }

  fun superSubstitutions(
    model: KmClass,
    supertype: SupertypeMetadata,
  ): Map<Int, TypeRef<*>> {
    if (model.typeParameters.size != supertype.arguments.size) {
      throw ForyJsonException(
        "Kotlin generic supertype ${supertype.rawType.name} requires exact type arguments",
      )
    }
    val result = LinkedHashMap<Int, TypeRef<*>>(model.typeParameters.size)
    for (index in model.typeParameters.indices) {
      val argument = supertype.arguments[index] ?: continue
      result[model.typeParameters[index].id] = argument
    }
    return result
  }

  fun resolve(
    type: KmType,
    loader: ClassLoader?,
    substitutions: Map<Int, TypeRef<*>>,
    typeArgument: Boolean,
  ): TypeRef<*> {
    if (type.flexibleTypeUpperBound != null) {
      throw ForyJsonException("Kotlin platform types require an exact custom JSON codec")
    }
    val classifier = type.classifier
    if (classifier is KmClassifier.TypeParameter) {
      val substituted =
        substitutions[classifier.id]
          ?: throw ForyJsonException("Unresolved Kotlin JSON type parameter ${classifier.id}")
      val nullable =
        if (type.isDefinitelyNonNull) false else type.isNullable || occurrenceNullable(substituted)
      return withNullability(substituted, nullable)
    }
    if (classifier !is KmClassifier.Class) {
      throw ForyJsonException("Unsupported Kotlin JSON type classifier $classifier")
    }
    val nullable = type.isNullable && !type.isDefinitelyNonNull
    val logicalClass = classFor(classifier.name, loader)
    val arguments = type.arguments.map { resolveProjection(it, loader, substitutions) }
    val component = if (classifier.name == "kotlin/Array") arguments.singleOrNull() else null
    val semanticId = semanticTypeId(classifier.name)
    val unsignedCarrier = unsignedCarrier(semanticId)
    val rawType =
      if (component != null) {
        ReflectArray.newInstance(TypeUtils.boxedType(component.rawType), 0).javaClass
      } else if (!typeArgument && (!nullable || isUnsignedArray(semanticId))) {
        // A direct nullable unsigned-array member still has a nullable primitive-array JVM
        // carrier. Only generic/container occurrences use the boxed Kotlin wrapper class.
        unsignedCarrier ?: logicalClass
      } else {
        TypeUtils.boxedType(logicalClass)
      }
    val metadata = TypeExtMeta.of(semanticId, nullable, false, false, false)
    return when {
      component != null -> TypeRef.of<Any>(rawType, metadata, null, component)
      arguments.isEmpty() -> plainTypeRef(rawType, metadata)
      else -> TypeRef.ofDeclaredTypeArguments(rawType, metadata, arguments, null)
    }
  }

  private fun resolveProjection(
    projection: KmTypeProjection,
    loader: ClassLoader?,
    substitutions: Map<Int, TypeRef<*>>,
  ): TypeRef<*> {
    val type =
      projection.type ?: throw ForyJsonException("Star-projected Kotlin JSON types are unsupported")
    if (projection.variance == KmVariance.IN) {
      throw ForyJsonException("Contravariant Kotlin JSON types are unsupported")
    }
    val resolved = resolve(type, loader, substitutions, true)
    return if (projection.variance == KmVariance.OUT) withCovariance(resolved) else resolved
  }

  private fun dependsOnMissing(
    projection: KmTypeProjection,
    substitutions: Map<Int, TypeRef<*>>,
  ): Boolean {
    if (projection.variance == KmVariance.IN) return false
    val type = projection.type ?: return false
    val classifier = type.classifier
    if (classifier is KmClassifier.TypeParameter && classifier.id !in substitutions) return true
    return type.arguments.any { dependsOnMissing(it, substitutions) }
  }

  private fun withNullability(type: TypeRef<*>, nullable: Boolean): TypeRef<*> {
    val current =
      type.typeExtMeta
        ?: throw ForyJsonException("Platform-typed Kotlin JSON occurrence $type is unsupported")
    return withOccurrence(type, nullable, current.covariant())
  }

  private fun withCovariance(type: TypeRef<*>): TypeRef<*> {
    val current =
      type.typeExtMeta
        ?: throw ForyJsonException("Platform-typed Kotlin JSON occurrence $type is unsupported")
    return withOccurrence(type, current.nullable(), true)
  }

  fun withOccurrence(type: TypeRef<*>, nullable: Boolean, covariant: Boolean): TypeRef<*> {
    val current =
      type.typeExtMeta
        ?: throw ForyJsonException("Platform-typed Kotlin JSON occurrence $type is unsupported")
    if (current.nullable() == nullable && current.covariant() == covariant) return type
    val metadata =
      TypeExtMeta.of(
        current.typeId(),
        nullable,
        current.trackingRef(),
        current.nullableWrapper(),
        covariant,
      )
    val component = if (type.isArray) type.componentType else null
    return TypeRef.ofSemanticTypeArguments<Any>(type.type, metadata, type.typeArguments, component)
  }

  private fun occurrenceNullable(type: TypeRef<*>): Boolean =
    type.typeExtMeta?.nullable()
      ?: throw ForyJsonException("Kotlin JSON occurrence has no nullability: ${type.type}")

  @Suppress("UNCHECKED_CAST")
  private fun plainTypeRef(type: Class<*>, metadata: TypeExtMeta): TypeRef<*> =
    TypeRef.of(type as Class<Any>, metadata)

  private fun classFor(name: String, loader: ClassLoader?): Class<*> =
    try {
      when (name) {
        "kotlin/Boolean" -> java.lang.Boolean.TYPE
        "kotlin/Byte" -> java.lang.Byte.TYPE
        "kotlin/Short" -> java.lang.Short.TYPE
        "kotlin/Int" -> java.lang.Integer.TYPE
        "kotlin/Long" -> java.lang.Long.TYPE
        "kotlin/Float" -> java.lang.Float.TYPE
        "kotlin/Double" -> java.lang.Double.TYPE
        "kotlin/Char" -> java.lang.Character.TYPE
        "kotlin/BooleanArray" -> BooleanArray::class.java
        "kotlin/ByteArray" -> ByteArray::class.java
        "kotlin/ShortArray" -> ShortArray::class.java
        "kotlin/IntArray" -> IntArray::class.java
        "kotlin/LongArray" -> LongArray::class.java
        "kotlin/FloatArray" -> FloatArray::class.java
        "kotlin/DoubleArray" -> DoubleArray::class.java
        "kotlin/CharArray" -> CharArray::class.java
        "kotlin/String" -> String::class.java
        "kotlin/Any" -> Any::class.java
        "kotlin/Unit" -> Unit::class.java
        "kotlin/Nothing" -> Void::class.java
        "kotlin/Number" -> Number::class.java
        "kotlin/CharSequence" -> CharSequence::class.java
        "kotlin/Comparable" -> Comparable::class.java
        "kotlin/Throwable" -> Throwable::class.java
        "kotlin/Enum" -> Enum::class.java
        "kotlin/collections/Iterable",
        "kotlin/collections/MutableIterable" -> Iterable::class.java
        "kotlin/collections/Collection",
        "kotlin/collections/MutableCollection" -> Collection::class.java
        "kotlin/collections/List",
        "kotlin/collections/MutableList" -> List::class.java
        "kotlin/collections/Set",
        "kotlin/collections/MutableSet" -> Set::class.java
        "kotlin/collections/Map",
        "kotlin/collections/MutableMap" -> Map::class.java
        "kotlin/collections/Iterator",
        "kotlin/collections/MutableIterator" -> Iterator::class.java
        "kotlin/collections/ListIterator",
        "kotlin/collections/MutableListIterator" -> ListIterator::class.java
        "kotlin/collections/Map.Entry",
        "kotlin/collections/MutableMap.MutableEntry" -> Map.Entry::class.java
        "kotlin/Array" -> Array<Any>::class.java
        else -> Class.forName(binaryName(name), false, loader)
      }
    } catch (cause: ClassNotFoundException) {
      throw ForyJsonException("Kotlin JSON metadata type $name is not available", cause)
    }

  private fun binaryName(metadataName: String): String {
    val packageEnd = metadataName.lastIndexOf('/')
    val packageName =
      if (packageEnd < 0) "" else metadataName.substring(0, packageEnd).replace('/', '.') + "."
    return packageName + metadataName.substring(packageEnd + 1).replace('.', '$')
  }

  private fun semanticTypeId(name: String): Int =
    when (name) {
      "kotlin/UByte" -> Types.UINT8
      "kotlin/UShort" -> Types.UINT16
      "kotlin/UInt" -> Types.UINT32
      "kotlin/ULong" -> Types.UINT64
      "kotlin/UByteArray" -> Types.UINT8_ARRAY
      "kotlin/UShortArray" -> Types.UINT16_ARRAY
      "kotlin/UIntArray" -> Types.UINT32_ARRAY
      "kotlin/ULongArray" -> Types.UINT64_ARRAY
      else -> 0
    }

  private fun unsignedCarrier(typeId: Int): Class<*>? =
    when (typeId) {
      Types.UINT8 -> java.lang.Byte.TYPE
      Types.UINT16 -> java.lang.Short.TYPE
      Types.UINT32 -> java.lang.Integer.TYPE
      Types.UINT64 -> java.lang.Long.TYPE
      Types.UINT8_ARRAY -> ByteArray::class.java
      Types.UINT16_ARRAY -> ShortArray::class.java
      Types.UINT32_ARRAY -> IntArray::class.java
      Types.UINT64_ARRAY -> LongArray::class.java
      else -> null
    }

  private fun isUnsignedArray(typeId: Int): Boolean =
    typeId == Types.UINT8_ARRAY ||
      typeId == Types.UINT16_ARRAY ||
      typeId == Types.UINT32_ARRAY ||
      typeId == Types.UINT64_ARRAY

  class SupertypeMetadata(
    val rawType: Class<*>,
    val arguments: List<TypeRef<*>?>,
  )
}
