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

package org.apache.fory.serializer.kotlin

import java.lang.reflect.Array
import java.lang.reflect.Modifier
import java.lang.reflect.Type
import java.util.IdentityHashMap
import kotlin.reflect.KParameter
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.javaType
import org.apache.fory.collection.ClassValueCache
import org.apache.fory.logging.Logger
import org.apache.fory.logging.LoggerFactory
import org.apache.fory.platform.AndroidSupport
import org.apache.fory.reflect.ObjectInstantiators
import org.apache.fory.util.DefaultValueUtils

/**
 * Kotlin implementation of DefaultValueSupport for extracting default values from Kotlin data
 * classes.
 *
 * This class uses Kotlin native reflection to analyze data classes and extract default values from
 * their primary constructor parameters.
 */
internal class KotlinDefaultValueSupport : DefaultValueUtils.DefaultValueSupport() {
  private val LOG: Logger = LoggerFactory.getLogger(KotlinDefaultValueSupport::class.java)
  private val cachedKotlinDataClassDefaultValues =
    ClassValueCache.newClassKeyCache<Map<String, Any>>(32)

  override fun hasDefaultValues(cls: Class<*>): Boolean {
    if (!isKotlinDataClass(cls)) {
      return false
    } else {
      return getAllDefaultValues(cls).isNotEmpty()
    }
  }

  override fun getAllDefaultValues(cls: Class<*>): Map<String, Any> {
    cachedKotlinDataClassDefaultValues.getIfPresent(cls)?.let {
      return it
    }
    return try {
      if (!isKotlinDataClass(cls)) {
        cachedKotlinDataClassDefaultValues.put(cls, emptyMap())
        return emptyMap()
      }

      val kClass = cls.kotlin
      val primaryConstructor = kClass.primaryConstructor ?: return emptyMap()
      val parameters = primaryConstructor.parameters
      val argsMap = mutableMapOf<KParameter, Any>()
      // Provide default values for all required (non-optional) parameters
      for (parameter in parameters) {
        if (!parameter.isOptional) {
          val defaultValue = getDefaultValueForType(parameter.type.javaType, IdentityHashMap())
          if (defaultValue != null) {
            argsMap[parameter] = defaultValue
          } else {
            cachedKotlinDataClassDefaultValues.put(cls, emptyMap())
            // If we can't provide a default for a required parameter, we can't get any defaults
            return emptyMap()
          }
        }
      }
      // Create a single instance
      val instance = primaryConstructor.callBy(argsMap)
      val defaultValues = mutableMapOf<String, Any>()
      // For each optional parameter, extract its value from the instance
      for (parameter in parameters) {
        if (parameter.isOptional && parameter.name != null) {
          val property = kClass.memberProperties.find { it.name == parameter.name }
          property?.let { prop ->
            @Suppress("UNCHECKED_CAST")
            val value = (prop as kotlin.reflect.KProperty1<Any, *>).get(instance)
            if (value != null) {
              defaultValues[parameter.name!!] = value
            }
          }
        }
      }
      cachedKotlinDataClassDefaultValues.put(cls, defaultValues)
      defaultValues
    } catch (e: Exception) {
      LOG.info("Error getting default values for class ${cls.name}: ${e.message}")
      cachedKotlinDataClassDefaultValues.put(cls, emptyMap())
      emptyMap()
    }
  }

  override fun getDefaultValue(cls: Class<*>, fieldName: String): Any? {
    if (!isKotlinDataClass(cls)) {
      return null
    }
    return getAllDefaultValues(cls)[fieldName]
  }

  private fun isKotlinDataClass(cls: Class<*>): Boolean {
    return cls.kotlin.isData
  }

  private fun getDefaultValueForType(type: Type, seen: IdentityHashMap<Class<*>, Boolean>): Any? {
    val clazz =
      when (type) {
        is Class<*> -> type
        is java.lang.reflect.ParameterizedType -> type.rawType as? Class<*>
        else -> null
      } ?: return null

    return when (clazz) {
      Int::class.java,
      Int::class.javaPrimitiveType -> 0
      Long::class.java,
      Long::class.javaPrimitiveType -> 0L
      Double::class.java,
      Double::class.javaPrimitiveType -> 0.0
      Float::class.java,
      Float::class.javaPrimitiveType -> 0.0f
      Boolean::class.java,
      Boolean::class.javaPrimitiveType -> false
      Byte::class.java,
      Byte::class.javaPrimitiveType -> 0.toByte()
      Short::class.java,
      Short::class.javaPrimitiveType -> 0.toShort()
      Char::class.java,
      Char::class.javaPrimitiveType -> '\u0000'
      String::class.java -> ""
      else -> getDefaultValueForClass(clazz, seen)
    }
  }

  private fun getDefaultValueForClass(
    clazz: Class<*>,
    seen: IdentityHashMap<Class<*>, Boolean>
  ): Any? {
    if (clazz.isArray) {
      return Array.newInstance(clazz.componentType, 0)
    }
    if (clazz.isEnum) {
      return clazz.enumConstants?.firstOrNull()
    }
    if (List::class.java.isAssignableFrom(clazz)) {
      return emptyList<Any>()
    }
    if (Set::class.java.isAssignableFrom(clazz)) {
      return emptySet<Any>()
    }
    if (Map::class.java.isAssignableFrom(clazz)) {
      return emptyMap<Any, Any>()
    }
    if (clazz.isInterface || Modifier.isAbstract(clazz.modifiers) || seen.containsKey(clazz)) {
      return null
    }
    seen[clazz] = true
    try {
      if (!AndroidSupport.IS_ANDROID) {
        return newDefaultInstance(clazz)
      }
      return newPublicDefaultInstance(clazz, seen)
    } finally {
      seen.remove(clazz)
    }
  }

  private fun newDefaultInstance(clazz: Class<*>): Any? {
    return ObjectInstantiators.getObjectInstantiator(clazz).newInstance()
  }

  private fun newPublicDefaultInstance(
    clazz: Class<*>,
    seen: IdentityHashMap<Class<*>, Boolean>
  ): Any? {
    val constructor =
      clazz.constructors.minWithOrNull(
        compareBy<java.lang.reflect.Constructor<*>> { it.parameterCount }
      ) ?: return null
    val args =
      constructor.genericParameterTypes.map { parameterType ->
        getDefaultValueForType(parameterType, seen) ?: return null
      }
    return constructor.newInstance(*args.toTypedArray())
  }
}
