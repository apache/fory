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

import java.lang.reflect.{AnnotatedElement, Constructor, GenericArrayType, Method, Modifier, ParameterizedType, Type}
import java.util.{Arrays, Objects}

import org.apache.fory.json.ForyJsonException
import org.apache.fory.json.annotation.JsonCodec
import org.apache.fory.json.codec.{JsonValueCodec, MapKeyCodec}
import org.apache.fory.json.scala.{JsonEnumeration, ScalaEnumerationCodec}
import org.apache.fory.reflect.TypeRef

private[scala] object ScalaEnumerationTypes {
  private val ValueClass = classOf[Enumeration#Value]

  def propertyTypes(
      typeClass: Class[_],
      constructor: Constructor[_],
      names: Array[String],
      getters: Array[Method],
      setters: Array[Method]
  ): Array[Type] = {
    val constructorParameters = constructor.getParameters
    val constructorTypes = constructor.getGenericParameterTypes
    val result = new Array[Type](names.length)
    var index = 0
    while (index < names.length) {
      val getter = getters(index)
      val setter = setters(index)
      val declaredType =
        if (index < constructorTypes.length) constructorTypes(index)
        else if (getter != null) getter.getGenericReturnType
        else setter.getGenericParameterTypes.apply(0)
      val constructorParameter =
        if (index < constructorParameters.length) constructorParameters(index) else null
      result(index) = resolveProperty(
        typeClass,
        names(index),
        declaredType,
        constructorParameter,
        getter,
        setter
      )
      index += 1
    }
    result
  }

  def createCodec(typeRef: TypeRef[_]): JsonValueCodec[_] = {
    val owner = enumerationOwner(typeRef.getType)
    if (owner == null) null else new BoundEnumerationCodec(enumeration(owner))
  }

  def mapKeyCodec(keyType: Type): MapKeyCodec = {
    val owner = enumerationOwner(keyType)
    if (owner == null) null else new BoundEnumerationCodec(enumeration(owner))
  }

  private def resolveProperty(
      typeClass: Class[_],
      name: String,
      declaredType: Type,
      constructorParameter: AnnotatedElement,
      getter: Method,
      setter: Method
  ): Type = {
    var selected: JsonEnumeration = null
    var selectedSource: AnnotatedElement = null
    var codecSource: AnnotatedElement = null

    def inspect(source: AnnotatedElement): Unit = {
      if (source == null) return
      if (source.getDeclaredAnnotation(classOf[JsonCodec]) != null) codecSource = source
      val annotation = source.getDeclaredAnnotation(classOf[JsonEnumeration])
      if (annotation != null) {
        if (selected != null && !sameSelection(selected, annotation)) {
          throw new ForyJsonException(
            s"Conflicting @JsonEnumeration declarations for ${typeClass.getName}.$name from " +
              s"$selectedSource and $source"
          )
        }
        if (selected == null) {
          selected = annotation
          selectedSource = source
        }
      }
    }

    inspect(constructorParameter)
    inspect(getter)
    val fields = typeClass.getDeclaredFields
    var fieldIndex = 0
    while (fieldIndex < fields.length) {
      if (fields(fieldIndex).getName == name) inspect(fields(fieldIndex))
      fieldIndex += 1
    }
    inspect(setter)
    if (setter != null && setter.getParameterCount == 1) inspect(setter.getParameters.apply(0))

    if (selected == null) return declaredType
    if (codecSource != null) {
      throw new ForyJsonException(
        s"@JsonEnumeration cannot coexist with @JsonCodec for ${typeClass.getName}.$name"
      )
    }
    applySelection(declaredType, selected, s"${typeClass.getName}.$name")
  }

  private def applySelection(valueType: Type, annotation: JsonEnumeration, property: String): Type = {
    val value = configured(annotation.value())
    val element = configured(annotation.element())
    val content = configured(annotation.content())
    val mapKey = configured(annotation.mapKey())
    val mapValue = configured(annotation.mapValue())
    val childCount =
      (if (element) 1 else 0) + (if (content) 1 else 0) +
        (if (mapKey) 1 else 0) + (if (mapValue) 1 else 0)
    if (value) {
      if (childCount != 0)
        throw invalid(property, "value cannot be combined with child slots")
      requireValue(valueType, property)
      return ownedValue(annotation.value(), property)
    }
    if (childCount == 0) throw invalid(property, "at least one owner slot is required")
    if (element) {
      if (childCount != 1) throw invalid(property, "element cannot be combined with other child slots")
      return replaceElement(valueType, annotation.element(), property)
    }
    if (content) {
      if (childCount != 1) throw invalid(property, "content cannot be combined with other child slots")
      return replaceContent(valueType, annotation.content(), property)
    }
    replaceMap(valueType, annotation.mapKey(), annotation.mapValue(), property)
  }

  private def replaceElement(valueType: Type, owner: Class[_], property: String): Type =
    valueType match {
      case cls: Class[_] if cls.isArray =>
        requireValue(cls.getComponentType, property)
        new OwnedArrayType(ownedValue(owner, property))
      case array: GenericArrayType =>
        requireValue(array.getGenericComponentType, property)
        new OwnedArrayType(ownedValue(owner, property))
      case parameterized: ParameterizedType =>
        val rawType = rawClass(parameterized)
        if (
          rawType == null || classOf[scala.collection.Map[_, _]].isAssignableFrom(rawType) ||
          !classOf[scala.collection.Iterable[_]].isAssignableFrom(rawType)
        ) throw invalid(property, "element requires a Scala collection or array")
        val arguments = parameterized.getActualTypeArguments
        if (arguments.length != 1) throw invalid(property, "element requires one direct type argument")
        requireValue(arguments(0), property)
        rebuild(parameterized, Array(ownedValue(owner, property)))
      case _ => throw invalid(property, "element requires a Scala collection or array")
    }

  private def replaceContent(valueType: Type, owner: Class[_], property: String): Type =
    valueType match {
      case parameterized: ParameterizedType =>
        val rawType = rawClass(parameterized)
        if (rawType == null || !classOf[Option[_]].isAssignableFrom(rawType))
          throw invalid(property, "content requires Option")
        val arguments = parameterized.getActualTypeArguments
        if (arguments.length != 1) throw invalid(property, "content requires one direct type argument")
        requireValue(arguments(0), property)
        rebuild(parameterized, Array(ownedValue(owner, property)))
      case _ => throw invalid(property, "content requires a parameterized Option")
    }

  private def replaceMap(
      valueType: Type,
      keyOwner: Class[_],
      valueOwner: Class[_],
      property: String
  ): Type = valueType match {
    case parameterized: ParameterizedType =>
      val rawType = rawClass(parameterized)
      if (rawType == null || !classOf[scala.collection.Map[_, _]].isAssignableFrom(rawType))
        throw invalid(property, "mapKey and mapValue require a Scala Map")
      val arguments = parameterized.getActualTypeArguments
      if (arguments.length != 2) throw invalid(property, "Scala Map requires key and value arguments")
      val rewritten = arguments.clone()
      if (configured(keyOwner)) {
        requireValue(arguments(0), property)
        rewritten(0) = ownedValue(keyOwner, property)
      }
      if (configured(valueOwner)) {
        requireValue(arguments(1), property)
        rewritten(1) = ownedValue(valueOwner, property)
      }
      rebuild(parameterized, rewritten)
    case _ => throw invalid(property, "mapKey and mapValue require a parameterized Scala Map")
  }

  private def rebuild(valueType: ParameterizedType, arguments: Array[Type]): Type = {
    new ResolvedParameterizedType(valueType.getOwnerType, valueType.getRawType, arguments)
  }

  private def ownedValue(owner: Class[_], property: String): Type = {
    validateOwner(owner, property)
    new ResolvedParameterizedType(owner, ValueClass, Array.empty)
  }

  private def enumerationOwner(valueType: Type): Class[_] = valueType match {
    case parameterized: ParameterizedType
        if rawClass(parameterized) == ValueClass &&
          parameterized.getActualTypeArguments.length == 0 =>
      val owner = rawClass(parameterized.getOwnerType)
      if (owner == null) null
      else {
        validateOwner(owner, valueType.toString)
        owner
      }
    case _ => null
  }

  private def validateOwner(owner: Class[_], source: String): Unit = {
    if (!classOf[Enumeration].isAssignableFrom(owner))
      throw invalid(source, s"${owner.getName} is not a Scala Enumeration singleton class")
    val field =
      try owner.getField("MODULE$")
      catch {
        case _: NoSuchFieldException =>
          throw invalid(source, s"${owner.getName} has no public MODULE$$ field")
      }
    val modifiers = field.getModifiers
    if (
      field.getType != owner || !Modifier.isPublic(modifiers) ||
      !Modifier.isStatic(modifiers) || !Modifier.isFinal(modifiers)
    ) throw invalid(source, s"${owner.getName}.MODULE$$ is not a public static final singleton")
  }

  private def enumeration(owner: Class[_]): Enumeration = {
    validateOwner(owner, owner.getName)
    try {
      val value = owner.getField("MODULE$").get(null)
      value match {
        case enumeration: Enumeration => enumeration
        case _ => throw invalid(owner.getName, "MODULE$ is not a Scala Enumeration")
      }
    } catch {
      case error: ReflectiveOperationException =>
        throw new ForyJsonException(s"Cannot load Scala Enumeration owner ${owner.getName}", error)
    }
  }

  private def requireValue(valueType: Type, property: String): Unit = {
    if (rawClass(valueType) != ValueClass)
      throw invalid(property, s"configured occurrence is ${valueType.getTypeName}, not Enumeration.Value")
  }

  private def rawClass(valueType: Type): Class[_] = valueType match {
    case cls: Class[_]                  => cls
    case parameterized: ParameterizedType => parameterized.getRawType match {
        case cls: Class[_] => cls
        case _             => null
      }
    case _ => null
  }

  private def configured(owner: Class[_]): Boolean = owner != classOf[Void]

  private def sameSelection(left: JsonEnumeration, right: JsonEnumeration): Boolean =
    left.value() == right.value() && left.element() == right.element() &&
      left.content() == right.content() && left.mapKey() == right.mapKey() &&
      left.mapValue() == right.mapValue()

  private def invalid(property: String, reason: String): ForyJsonException =
    new ForyJsonException(s"Invalid @JsonEnumeration on $property: $reason")

  private final class BoundEnumerationCodec(enumeration: Enumeration)
      extends ScalaEnumerationCodec(enumeration)

  private final class OwnedArrayType(componentType: Type) extends GenericArrayType {
    override def getGenericComponentType: Type = componentType

    override def equals(other: Any): Boolean = other match {
      case array: GenericArrayType => componentType == array.getGenericComponentType
      case _                       => false
    }

    override def hashCode(): Int = componentType.hashCode()

    override def toString: String = componentType.getTypeName + "[]"
  }

  private final class ResolvedParameterizedType(
      ownerType: Type,
      rawType: Type,
      suppliedArguments: Array[Type]
  ) extends ParameterizedType {
    private val arguments = suppliedArguments.clone()

    override def getOwnerType: Type = ownerType

    override def getRawType: Type = rawType

    override def getActualTypeArguments: Array[Type] = arguments.clone()

    override def equals(other: Any): Boolean = other match {
      case parameterized: ParameterizedType =>
        Objects.equals(ownerType, parameterized.getOwnerType) &&
          Objects.equals(rawType, parameterized.getRawType) &&
          Arrays.equals(arguments.asInstanceOf[Array[Object]],
            parameterized.getActualTypeArguments.asInstanceOf[Array[Object]])
      case _ => false
    }

    override def hashCode(): Int =
      Arrays.hashCode(arguments.asInstanceOf[Array[Object]]) ^ Objects.hashCode(ownerType) ^
        Objects.hashCode(rawType)

    override def toString: String = {
      val builder = new StringBuilder(rawType.getTypeName)
      if (arguments.nonEmpty) {
        builder.append('<')
        var index = 0
        while (index < arguments.length) {
          if (index != 0) builder.append(", ")
          builder.append(arguments(index).getTypeName)
          index += 1
        }
        builder.append('>')
      }
      builder.toString()
    }
  }
}
