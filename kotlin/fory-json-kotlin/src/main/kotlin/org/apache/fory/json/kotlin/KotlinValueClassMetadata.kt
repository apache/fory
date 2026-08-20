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

import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import kotlin.metadata.ClassKind
import kotlin.metadata.KmClass
import kotlin.metadata.Visibility
import kotlin.metadata.isSecondary
import kotlin.metadata.isValue
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.jvm.signature
import kotlin.metadata.kind
import kotlin.metadata.visibility
import org.apache.fory.json.ForyJsonException
import org.apache.fory.reflect.TypeRef
import org.apache.fory.serializer.GraphMemoryEstimates
import org.apache.fory.type.TypeUtils

/** Immutable cold metadata for one exact, possibly nested, value-class occurrence. */
internal class KotlinValueClassShape(
  val ownerType: TypeRef<*>,
  val outerNullable: Boolean,
  val layers: List<KotlinValueClassLayer>,
  val terminalType: TypeRef<*>,
) {
  val ownerClass: Class<*> =
    layers.firstOrNull()?.ownerClass
      ?: throw ForyJsonException("A Kotlin value-class shape requires at least one layer")
  val underlyingNullable: Boolean = nullable(layers.first().underlyingType)

  init {
    if (layers.first().occurrenceType != ownerType) {
      throw ForyJsonException("Invalid Kotlin value-class layer sequence for $ownerType")
    }
    if (outerNullable && underlyingNullable) {
      throw ForyJsonException(
        "Unsupported Kotlin JSON value class ${ownerClass.name}: nullable outer and " +
          "nullable underlying values need an explicit tagged codec",
      )
    }
    validateCarriers()
  }

  fun requireMapKey() {
    for (layer in layers) {
      if (nullable(layer.occurrenceType) || nullable(layer.underlyingType)) {
        throw ForyJsonException(
          "Kotlin JSON value-class map key ${ownerClass.name} must be non-null at every layer",
        )
      }
    }
    if (nullable(terminalType)) {
      throw ForyJsonException(
        "Kotlin JSON value-class map key ${ownerClass.name} has a nullable terminal type",
      )
    }
  }

  private fun validateCarriers() {
    for (index in layers.indices) {
      val layer = layers[index]
      if (layer.occurrenceType.rawType != layer.ownerClass) {
        invalidLayer(layer, "occurrence type does not name the exact owner")
      }
      if (index < layers.lastIndex) {
        val inner = layers[index + 1]
        if (layer.underlyingType != inner.occurrenceType) {
          invalidLayer(layer, "underlying type does not match the next semantic layer")
        }
        if (layer.carrierClass != inner.ownerClass && layer.carrierClass != inner.carrierClass) {
          invalidLayer(layer, "physical carrier does not match the nested value class")
        }
      } else {
        if (layer.underlyingType != terminalType) {
          invalidLayer(layer, "underlying type does not match the terminal type")
        }
        val terminalClass = terminalType.rawType
        if (layer.carrierClass.isPrimitive) {
          if (layer.carrierClass != terminalClass) {
            invalidLayer(layer, "primitive carrier does not match the terminal type")
          }
        } else if (!layer.carrierClass.isAssignableFrom(TypeUtils.boxedType(terminalClass))) {
          invalidLayer(layer, "reference carrier does not accept the terminal type")
        }
      }
    }
  }

  private fun invalidLayer(layer: KotlinValueClassLayer, reason: String): Nothing =
    throw ForyJsonException(
      "Invalid Kotlin value-class metadata for ${layer.ownerClass.name}: $reason",
    )
}

/** Exact Kotlin/JVM operations and the logical underlying type for one semantic layer. */
internal class KotlinValueClassLayer(
  val occurrenceType: TypeRef<*>,
  val ownerClass: Class<*>,
  val underlyingType: TypeRef<*>,
  val carrierClass: Class<*>,
) {
  val shallowBytes: Int = GraphMemoryEstimates.shallowObjectBytes(ownerClass)
}

/** Reflection-derived exact operations kept separate from the semantic shape. */
internal class KotlinReflectiveValueClass(
  val shape: KotlinValueClassShape,
  val constructors: List<Method>,
  val boxes: List<Method>,
  val unboxes: List<Method>,
) {
  fun carrierMethods(): KotlinCarrierMethods {
    // A lowered parent already stores the outer carrier. Generated code must run every
    // constructor-impl but materialize only inner wrappers required by the next layer's JVM ABI;
    // the reverse list likewise omits the outer unbox-impl.
    val constructMethods = ArrayList<Method>(shape.layers.size * 2)
    val constructBytes = ArrayList<Int>(shape.layers.size * 2)
    var index = shape.layers.lastIndex
    constructMethods += constructors[index]
    constructBytes += 0
    while (index > 0) {
      val inner = shape.layers[index]
      val outer = shape.layers[index - 1]
      if (outer.carrierClass == inner.ownerClass) {
        constructMethods += boxes[index]
        constructBytes += inner.shallowBytes
      }
      index--
      constructMethods += constructors[index]
      constructBytes += 0
    }
    val extractMethods = ArrayList<Method>(shape.layers.size - 1)
    for (innerIndex in 1 until shape.layers.size) {
      if (shape.layers[innerIndex - 1].carrierClass == shape.layers[innerIndex].ownerClass) {
        extractMethods += unboxes[innerIndex]
      }
    }
    return KotlinCarrierMethods(
      constructMethods.toTypedArray(),
      constructBytes.toIntArray(),
      extractMethods.toTypedArray(),
    )
  }
}

internal class KotlinCarrierMethods(
  val construct: Array<Method>,
  val boxBytes: IntArray,
  val extract: Array<Method>,
)

private class ReflectedLayer(
  val shape: KotlinValueClassLayer,
  val constructor: Method,
  val box: Method,
  val unbox: Method,
)

/** Strict Kotlin 2.3 metadata and JVM-descriptor validation for value-class codec construction. */
internal object KotlinValueClassMetadata {
  fun inspect(ownerType: TypeRef<*>): KotlinReflectiveValueClass {
    val layers = ArrayList<KotlinValueClassLayer>(2)
    val constructors = ArrayList<Method>(2)
    val boxes = ArrayList<Method>(2)
    val unboxes = ArrayList<Method>(2)
    val active = IdentityHashMap<Class<*>, Boolean>()
    var occurrence = ownerType
    var reflected = inspectLayer(occurrence, active)
    while (true) {
      val layer = reflected
      layers += layer.shape
      constructors += layer.constructor
      boxes += layer.box
      unboxes += layer.unbox
      val underlying = layer.shape.underlyingType
      // Generic value-class layers retain their erased Object carrier. Descending into a
      // substituted value-class argument would invent a flattened ABI that the outer class does
      // not have; the substituted logical type remains the terminal codec owner.
      if (
        layer.shape.carrierClass == Any::class.java ||
          !isValueClass(underlying.rawType) ||
          KotlinTemporalCodecs.supports(underlying.rawType) ||
          KotlinUnsupportedTypes.rejects(underlying.rawType)
      ) {
        return KotlinReflectiveValueClass(
          KotlinValueClassShape(
            ownerType,
            ownerType.typeExtMeta?.nullable() ?: true,
            layers,
            underlying,
          ),
          constructors,
          boxes,
          unboxes,
        )
      }
      val nested = inspectLayer(underlying, active)
      if (
        layer.shape.carrierClass != nested.shape.ownerClass &&
          layer.shape.carrierClass != nested.shape.carrierClass
      ) {
        return KotlinReflectiveValueClass(
          KotlinValueClassShape(
            ownerType,
            ownerType.typeExtMeta?.nullable() ?: true,
            layers,
            underlying,
          ),
          constructors,
          boxes,
          unboxes,
        )
      }
      occurrence = underlying
      reflected = nested
    }
  }

  fun isValueClass(type: Class<*>): Boolean {
    val metadata = type.getAnnotation(Metadata::class.java) ?: return false
    val classMetadata =
      try {
        KotlinClassMetadata.readStrict(metadata)
      } catch (_: IllegalArgumentException) {
        return false
      }
    return classMetadata is KotlinClassMetadata.Class && classMetadata.kmClass.isValue
  }

  private fun inspectLayer(
    occurrenceType: TypeRef<*>,
    active: IdentityHashMap<Class<*>, Boolean>,
  ): ReflectedLayer {
    val rawType = occurrenceType.rawType
    if (active.put(rawType, true) != null) {
      unsupported(rawType, "recursive value-class underlying type")
    }
    val classMetadata = KotlinMetadataTypes.classMetadata(rawType)
    val model = classMetadata.kmClass
    if (!model.isValue || model.kind != ClassKind.CLASS) {
      unsupported(rawType, "declaration is not a value class")
    }
    val primary =
      model.constructors.singleOrNull { !it.isSecondary }
        ?: unsupported(rawType, "missing unique primary constructor")
    if (primary.visibility != Visibility.PUBLIC && primary.visibility != Visibility.INTERNAL) {
      unsupported(rawType, "primary constructor is not JVM-accessible")
    }
    if (primary.valueParameters.size != 1) {
      unsupported(rawType, "primary constructor must have one underlying parameter")
    }
    val underlyingName =
      model.inlineClassUnderlyingPropertyName
        ?: unsupported(rawType, "missing underlying property name")
    val underlyingKmType =
      model.inlineClassUnderlyingType ?: unsupported(rawType, "missing underlying property type")
    val parameter = primary.valueParameters.single()
    if (parameter.name != underlyingName || parameter.type != underlyingKmType) {
      unsupported(rawType, "primary parameter and underlying property metadata disagree")
    }
    val substitutions = substitutions(occurrenceType, model)
    val underlyingType =
      KotlinMetadataTypes.resolve(underlyingKmType, rawType.classLoader, substitutions, false)
    val constructorSignature =
      primary.signature ?: unsupported(rawType, "primary constructor has no JVM signature")
    val underlyingField =
      rawType.declaredFields.singleOrNull {
        !Modifier.isStatic(it.modifiers) && it.name == underlyingName
      } ?: unsupported(rawType, "underlying field $underlyingName was not found exactly")
    if (
      !Modifier.isPrivate(underlyingField.modifiers) || !Modifier.isFinal(underlyingField.modifiers)
    ) {
      unsupported(rawType, "underlying field is not an exact private final carrier")
    }
    val carrier = underlyingField.type
    val constructorImpl = exactMethod(rawType, "constructor-impl", arrayOf(carrier), carrier, true)
    if (
      constructorSignature.name != constructorImpl.name ||
        constructorSignature.descriptor != KotlinMetadataTypes.methodDescriptor(constructorImpl)
    ) {
      unsupported(rawType, "primary constructor metadata does not name the exact constructor-impl")
    }
    val boxedConstructor =
      rawType.declaredConstructors.singleOrNull {
        it.parameterTypes.contentEquals(arrayOf(carrier))
      } ?: unsupported(rawType, "boxed constructor does not have the exact carrier descriptor")
    if (!Modifier.isPrivate(boxedConstructor.modifiers)) {
      unsupported(rawType, "boxed constructor is not private")
    }
    val boxImpl = exactMethod(rawType, "box-impl", arrayOf(carrier), rawType, true)
    val unboxImpl = exactMethod(rawType, "unbox-impl", emptyArray(), carrier, false)
    return ReflectedLayer(
      KotlinValueClassLayer(
        occurrenceType,
        rawType,
        underlyingType,
        carrier,
      ),
      constructorImpl,
      boxImpl,
      unboxImpl,
    )
  }

  private fun substitutions(ownerType: TypeRef<*>, model: KmClass): Map<Int, TypeRef<*>> {
    val substitutions = KotlinMetadataTypes.substitutions(ownerType, model)
    if (model.typeParameters.isNotEmpty() && substitutions.size != model.typeParameters.size) {
      throw ForyJsonException(
        "Kotlin JSON value class ${ownerType.type} requires exact type arguments",
      )
    }
    for (argument in substitutions.values) {
      if (argument.typeExtMeta == null) {
        throw ForyJsonException(
          "Kotlin JSON value class ${ownerType.type} has a platform-typed argument $argument",
        )
      }
    }
    return substitutions
  }

  private fun exactMethod(
    owner: Class<*>,
    name: String,
    parameters: Array<Class<*>>,
    result: Class<*>,
    static: Boolean,
  ): Method {
    val method =
      owner.declaredMethods.singleOrNull {
        it.name == name && it.parameterTypes.contentEquals(parameters) && it.returnType == result
      }
        ?: unsupported(
          owner,
          "method $name${KotlinMetadataTypes.descriptor(parameters, result)} was not found exactly",
        )
    if (!Modifier.isPublic(method.modifiers) || Modifier.isStatic(method.modifiers) != static) {
      unsupported(
        owner,
        "method $name${KotlinMetadataTypes.descriptor(parameters, result)} is not directly callable",
      )
    }
    if (!static && !Modifier.isFinal(method.modifiers)) {
      unsupported(
        owner,
        "method $name${KotlinMetadataTypes.descriptor(parameters, result)} is not final",
      )
    }
    return method
  }

  private fun unsupported(type: Class<*>, reason: String): Nothing =
    throw ForyJsonException("Unsupported Kotlin JSON value class ${type.name}: $reason")
}

private fun nullable(type: TypeRef<*>): Boolean = type.typeExtMeta?.nullable() == true
