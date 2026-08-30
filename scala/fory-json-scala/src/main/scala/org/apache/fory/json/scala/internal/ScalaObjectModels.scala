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

package org.apache.fory.json.scala.internal

import java.lang.reflect.{Constructor, Field, Method, Modifier}

import org.apache.fory.json.ForyJsonException
import org.apache.fory.json.codec.{JsonObjectModel, JsonValueCodec, ObjectCodec}
import org.apache.fory.json.resolver.JsonTypeResolver
import org.apache.fory.reflect.TypeRef

private[scala] object ScalaObjectModels {
  def isCaseClass(typeClass: Class[_]): Boolean = {
    val name = typeClass.getName
    if (!classOf[Product].isAssignableFrom(typeClass) || name.startsWith("scala.Tuple")) {
      return false
    }
    val companion = companionOwner(typeClass)
    if (companion != null && findPrimaryConstructor(typeClass, companion) != null) return true
    // A case class that cannot reach its companion, such as one declared inside a class or a
    // method, is still a case class. Claim it so the codec reports the exact reason instead of
    // leaving it to a generic object model that silently drops every property. A generated `copy`
    // returning the declaring class together with a declared `productPrefix`, which `Product`
    // otherwise supplies by default, is the compiler marker of a case class. Standard-library
    // types keep their own mapping.
    !name.startsWith("scala.") && copyMethod(typeClass) != null && declaresProductPrefix(typeClass)
  }

  def caseClassCodec(typeRef: TypeRef[_], resolver: JsonTypeResolver): ObjectCodec[_] = {
    val typeClass = typeRef.getRawType
    if (outerField(typeClass) != null) {
      throw ScalaTypeSupport.unsupported(
        typeRef,
        "case class declared inside a class cannot be reconstructed without its outer instance"
      )
    }
    val companion = companionOwner(typeClass)
    if (companion == null) {
      throw ScalaTypeSupport.unsupported(
        typeRef,
        "case class companion is not reachable, such as a case class declared in a method"
      )
    }
    val constructor = findPrimaryConstructor(typeClass, companion)
    if (constructor == null) {
      throw ScalaTypeSupport.unsupported(typeRef, "case class has no supported public primary constructor")
    }
    val parameterTypes = constructor.getParameterTypes
    val names = constructor.getParameters.map(_.getName)
    val accessors = new Array[Method](names.length)
    var index = 0
    while (index < names.length) {
      val accessor = propertyGetter(typeClass, names(index), parameterTypes(index))
      if (accessor == null)
        throw new ForyJsonException(
          s"Missing Scala case-class accessor ${typeClass.getName}.${names(index)}"
        )
      if (
        accessor.getParameterCount != 0 || !compatibleAccessor(accessor, parameterTypes(index)) ||
        Modifier.isStatic(accessor.getModifiers) || accessor.isBridge || accessor.isSynthetic
      ) {
        throw new ForyJsonException(s"Invalid Scala case-class accessor $accessor")
      }
      accessors(index) = accessor
      index += 1
    }
    val bodyFields = productFields(typeClass).filter(field => !names.contains(field.getName))
    val bodyGetters = bodyFields.map(field => propertyGetter(typeClass, field.getName, field.getType))
    val bodyPropertyIndexes = bodyGetters.indices.filter(index => bodyGetters(index) != null)
    val propertyNames = names ++ bodyPropertyIndexes.map(index => bodyFields(index).getName)
    val propertyGetters = accessors ++ bodyPropertyIndexes.map(index => bodyGetters(index))
    val propertySetters =
      Array.fill[Method](names.length)(null) ++
        bodyPropertyIndexes.map(index => propertySetter(typeClass, bodyFields(index)))
    val propertyTypes =
      ScalaEnumerationTypes.propertyTypes(
        typeClass,
        constructor,
        propertyNames,
        propertyGetters,
        propertySetters
      ).map(typeRef.resolveType)
    // Constructor properties and their accessors are one logical occurrence. In particular,
    // @JsonEnumeration binds an erased Enumeration.Value parameter to the exact MODULE$ owner.
    val logicalParameterTypes = propertyTypes.take(names.length)
    val defaults = constructorDefaults(typeClass, companion, parameterTypes)
    // The receiver belongs to the model only when a default is actually bound to it, so a nested
    // case class without defaults keeps a receiver-free model.
    val defaultsReceiver =
      if (companion.staticForwarders || defaults.forall(_ == null)) null
      else companionInstance(typeRef, companion.owner)
    resolver.createObjectCodec(
      typeRef,
      new JsonObjectModel(
        constructor,
        null,
        names,
        accessors,
        defaults,
        defaultsReceiver,
        Array.fill(names.length)(-1),
        Array.fill(names.length)(true),
        logicalParameterTypes,
        propertyNames,
        propertyGetters,
        propertySetters,
        propertyTypes
      )
    )
  }

  def singletonCodec(
      typeRef: TypeRef[_],
      resolver: JsonTypeResolver
  ): JsonValueCodec[_] = {
    val typeClass = typeRef.getRawType
    val field = singletonField(typeClass)
    if (field == null) null
    else fixedCodec(typeRef, resolver, field.get(null))
  }

  def fixedCodec(
      typeRef: TypeRef[_],
      resolver: JsonTypeResolver,
      instance: AnyRef
  ): ObjectCodec[_] = {
    val typeClass = typeRef.getRawType
    if (instance == null || instance.getClass != typeClass)
      throw ScalaTypeSupport.unsupported(typeRef, "singleton instance has a different runtime class")
    val nonPropertyFields = singletonNonPropertyFields(typeClass)
    val fields = singletonStateFields(typeClass, nonPropertyFields)
    val getters = fields.map(field => propertyGetter(typeClass, field.getName, field.getType))
    val propertyTypes = fields.indices.map { index =>
      val getter = getters(index)
      typeRef.resolveType(if (getter == null) fields(index).getGenericType else getter.getGenericReturnType)
    }.toArray
    resolver.createObjectCodec(
      typeRef,
      JsonObjectModel.fixedInstance(
        instance,
        fields.map(_.getName),
        getters,
        Array.fill[Method](fields.length)(null),
        propertyTypes,
        nonPropertyFields
      )
    )
  }

  private def singletonStateFields(
      typeClass: Class[_],
      nonPropertyFields: Array[Field]
  ): Array[Field] = {
    val moduleClass = typeClass.getName.endsWith("$")
    typeClass.getDeclaredFields.filter { field =>
      val name = field.getName
      val modifiers = field.getModifiers
      if (moduleClass) {
        name != "MODULE$" && !field.isSynthetic &&
        (!Modifier.isStatic(modifiers) || !Modifier.isFinal(modifiers) ||
          propertyGetter(typeClass, name, field.getType) != null)
      }
      else {
        !Modifier.isStatic(modifiers) && !field.isSynthetic && !nonPropertyFields.contains(field)
      }
    }
  }

  private def singletonNonPropertyFields(typeClass: Class[_]): Array[Field] = {
    if (typeClass.getName.endsWith("$")) return Array.empty
    typeClass.getDeclaredFields.filter { field =>
      val name = field.getName
      val modifiers = field.getModifiers
      !Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers) && !field.isSynthetic &&
      (name.startsWith("_$ordinal$") || name.startsWith("$name$"))
    }
  }

  private def declaresProductPrefix(typeClass: Class[_]): Boolean = {
    try {
      val method = typeClass.getDeclaredMethod("productPrefix")
      method.getReturnType == classOf[String] && !method.isSynthetic
    } catch { case _: NoSuchMethodException => false }
  }

  private def copyMethod(typeClass: Class[_]): Method = {
    typeClass.getMethods
      .find(method =>
        method.getName == "copy" && !Modifier.isStatic(method.getModifiers) &&
          !method.isBridge && !method.isSynthetic && method.getReturnType == typeClass
      )
      .orNull
  }

  private def outerField(typeClass: Class[_]): Field = {
    typeClass.getDeclaredFields
      .find(field => field.getName == "$outer" && !Modifier.isStatic(field.getModifiers))
      .orNull
  }

  private def productFields(typeClass: Class[_]): Array[Field] = {
    typeClass.getDeclaredFields.filter { field =>
      val modifiers = field.getModifiers
      !Modifier.isStatic(modifiers) && !field.isSynthetic && !field.getName.startsWith("$")
    }
  }

  /**
   * Owner of the compiler-generated `apply` and `$lessinit$greater$default$N` members of a case
   * class. Scala mirrors those companion members as static forwarders on the case class itself
   * only for a top-level companion, so a case class declared inside an `object` keeps them as
   * instance members of the companion singleton.
   */
  private final class CompanionOwner(val owner: Class[_], val staticForwarders: Boolean)

  // Recognition must not initialize the companion. Resolving the owner keeps the singleton
  // unloaded so deciding whether a type is a supported case class never runs a user object body;
  // caseClassCodec reads MODULE$ only once it commits to building the model.
  private def companionOwner(typeClass: Class[_]): CompanionOwner = {
    val methods = typeClass.getMethods
    var index = 0
    while (index < methods.length) {
      val method = methods(index)
      if (
        method.getName == "apply" && Modifier.isStatic(method.getModifiers) &&
        !method.isBridge && !method.isSynthetic && method.getReturnType == typeClass
      ) return new CompanionOwner(typeClass, true)
      index += 1
    }
    val companionClass =
      try Class.forName(typeClass.getName + "$", false, typeClass.getClassLoader)
      catch { case _: ClassNotFoundException | _: LinkageError => return null }
    if (!Modifier.isPublic(companionClass.getModifiers) || singletonField(companionClass) == null) {
      null
    } else new CompanionOwner(companionClass, false)
  }

  private def companionInstance(typeRef: TypeRef[_], companionClass: Class[_]): AnyRef = {
    val instance =
      try singletonField(companionClass).get(null)
      catch {
        case error: ReflectiveOperationException =>
          throw new ForyJsonException(
            s"Cannot read Scala companion singleton ${companionClass.getName}",
            error
          )
      }
    if (instance == null) {
      throw ScalaTypeSupport.unsupported(typeRef, "case class companion singleton is not initialized")
    }
    instance
  }

  private def findPrimaryConstructor(
      typeClass: Class[_],
      companion: CompanionOwner
  ): Constructor[_] = {
    val constructors = typeClass.getConstructors
    val methods = companion.owner.getMethods
    val staticApply = companion.staticForwarders
    var selected: Constructor[_] = null
    var index = 0
    while (index < constructors.length) {
      val constructor = constructors(index)
      val parameterTypes = constructor.getParameterTypes
      val matchingApply = methods.exists { method =>
        method.getName == "apply" && Modifier.isStatic(method.getModifiers) == staticApply &&
        !method.isBridge && !method.isSynthetic && method.getReturnType == typeClass &&
        sameTypes(method.getParameterTypes, parameterTypes)
      }
      if (!constructor.isSynthetic && !constructor.isVarArgs && matchingApply) {
        if (selected != null) {
          throw new ForyJsonException(s"Ambiguous Scala case-class primary constructor on ${typeClass.getName}")
        }
        selected = constructor
      }
      index += 1
    }
    selected
  }

  private def sameTypes(left: Array[Class[_]], right: Array[Class[_]]): Boolean = {
    if (left.length != right.length) return false
    var index = 0
    while (index < left.length) {
      if (left(index) != right(index)) return false
      index += 1
    }
    true
  }

  private def propertyGetter(typeClass: Class[_], name: String, propertyType: Class[_]): Method = {
    try {
      val method = typeClass.getMethod(name)
      val modifiers = method.getModifiers
      if (
        method.getParameterCount == 0 && compatibleAccessor(method, propertyType) &&
        Modifier.isPublic(modifiers) && !Modifier.isStatic(modifiers) && !method.isBridge &&
        !method.isSynthetic
      ) method
      else null
    } catch { case _: NoSuchMethodException => null }
  }

  private def compatibleAccessor(method: Method, propertyType: Class[_]): Boolean =
    method.getReturnType == propertyType ||
      propertyType == classOf[scala.runtime.BoxedUnit] && method.getReturnType == java.lang.Void.TYPE

  private def propertySetter(typeClass: Class[_], field: Field): Method = {
    try {
      val method = typeClass.getMethod(field.getName + "_$eq", field.getType)
      val modifiers = method.getModifiers
      if (
        method.getReturnType == java.lang.Void.TYPE && Modifier.isPublic(modifiers) &&
        !Modifier.isStatic(modifiers) && !method.isBridge && !method.isSynthetic
      ) method
      else null
    } catch { case _: NoSuchMethodException => null }
  }

  private def constructorDefaults(
      typeClass: Class[_],
      companion: CompanionOwner,
      parameterTypes: Array[Class[_]]
  ): Array[Method] = {
    // Constructor defaults live on the same owner as `apply`: static forwarders on the case-class
    // owner for a top-level companion, otherwise instance members of the companion singleton.
    // A default in a later parameter list receives the preceding parameter lists as arguments.
    val defaults = new Array[Method](parameterTypes.length)
    val staticDefault = companion.staticForwarders
    var index = 0
    while (index < defaults.length) {
      val name = "$lessinit$greater$default$" + (index + 1)
      val candidates = companion.owner.getMethods.filter { method =>
        val modifiers = method.getModifiers
        method.getName == name && Modifier.isPublic(modifiers) &&
        Modifier.isStatic(modifiers) == staticDefault &&
        method.getParameterCount <= index &&
        compatibleDefaultParameters(method.getParameterTypes, parameterTypes) &&
        compatibleDefaultResult(method.getReturnType, parameterTypes(index))
      }
      if (candidates.length > 1)
        throw new ForyJsonException(s"Ambiguous Scala constructor default $name on ${typeClass.getName}")
      if (candidates.length == 1) defaults(index) = candidates(0)
      index += 1
    }
    defaults
  }

  private def compatibleDefaultParameters(
      dependencies: Array[Class[_]],
      constructorParameters: Array[Class[_]]
  ): Boolean = {
    var index = 0
    while (index < dependencies.length) {
      if (dependencies(index) != constructorParameters(index)) return false
      index += 1
    }
    true
  }

  private def compatibleDefaultResult(result: Class[_], parameter: Class[_]): Boolean =
    boxed(parameter).isAssignableFrom(boxed(result))

  private def boxed(value: Class[_]): Class[_] = {
    if (!value.isPrimitive) value
    else if (value == java.lang.Boolean.TYPE) classOf[java.lang.Boolean]
    else if (value == java.lang.Byte.TYPE) classOf[java.lang.Byte]
    else if (value == java.lang.Short.TYPE) classOf[java.lang.Short]
    else if (value == java.lang.Integer.TYPE) classOf[java.lang.Integer]
    else if (value == java.lang.Long.TYPE) classOf[java.lang.Long]
    else if (value == java.lang.Float.TYPE) classOf[java.lang.Float]
    else if (value == java.lang.Double.TYPE) classOf[java.lang.Double]
    else if (value == java.lang.Character.TYPE) classOf[java.lang.Character]
    else value
  }

  private def singletonField(typeClass: Class[_]): Field = {
    if (!typeClass.getName.endsWith("$")) return null
    try {
      val field = typeClass.getField("MODULE$")
      val modifiers = field.getModifiers
      if (
        field.getType == typeClass && Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers) &&
        Modifier.isFinal(modifiers)
      ) field
      else null
    } catch { case _: NoSuchFieldException => null }
  }
}
