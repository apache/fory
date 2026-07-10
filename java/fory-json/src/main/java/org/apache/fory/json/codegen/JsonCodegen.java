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

package org.apache.fory.json.codegen;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.fory.annotation.Internal;
import org.apache.fory.codegen.CodeGenerator;
import org.apache.fory.codegen.CompileUnit;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.codec.BaseObjectCodec;
import org.apache.fory.json.codec.CollectionCodec;
import org.apache.fory.json.codec.GeneratedObjectCodec;
import org.apache.fory.json.codec.JsonCodec;
import org.apache.fory.json.codec.ObjectCodec;
import org.apache.fory.json.meta.JsonFieldInfo;
import org.apache.fory.json.meta.JsonFieldKind;
import org.apache.fory.json.resolver.JsonTypeResolver;
import org.apache.fory.platform.AndroidSupport;
import org.apache.fory.platform.internal._JDKAccess;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.util.record.RecordUtils;

public final class JsonCodegen {
  static final int GENERIC_READER = 0;
  static final int LATIN1_READER = 1;
  static final int UTF16_READER = 2;
  static final int UTF8_READER = 3;

  private static final Map<String, Map<String, Integer>> ID_GENERATOR = new ConcurrentHashMap<>();

  final boolean writeNullFields;
  private final int codegenHash;
  private final CodeGenerator codeGenerator;
  private final ClassLoader jsonLoader;
  private final ConcurrentHashMap<Class<?>, GeneratedObjectCodecClass> generatedClasses;

  public JsonCodegen(boolean writeNullFields, int codegenHash) {
    this.writeNullFields = writeNullFields;
    this.codegenHash = codegenHash;
    jsonLoader = JsonCodegen.class.getClassLoader();
    codeGenerator = new CodeGenerator(jsonLoader);
    generatedClasses = new ConcurrentHashMap<>();
  }

  public GeneratedObjectCodecClass generatedClass(Class<?> type) {
    return generatedClasses.get(type);
  }

  public GeneratedObjectCodecClass compileClass(ObjectCodec objectCodec) {
    Class<?> type = objectCodec.type();
    if (!canCompile(objectCodec)) {
      return null;
    }
    GeneratedObjectCodecClass generatedClass = generatedClasses.get(type);
    if (generatedClass != null) {
      return generatedClass;
    }
    return generatedClasses.computeIfAbsent(type, ignored -> buildClass(objectCodec));
  }

  public GeneratedObjectCodec newCodec(
      ObjectCodec objectCodec,
      JsonTypeResolver typeResolver,
      GeneratedObjectCodecClass generatedClass) {
    if (generatedClass == null) {
      return null;
    }
    Class<?> type = objectCodec.type();
    JsonFieldInfo[] writeProperties = objectCodec.writeFields();
    JsonCodec[] writeCodecs = writeCodecs(writeProperties);
    JsonFieldInfo[] readProperties = objectCodec.readFields();
    JsonCodec[] readCodecs = readCodecs(readProperties);
    BaseObjectCodec[] readObjectCodecs = readObjectCodecs(objectCodec, typeResolver);
    GeneratedObjectCodec codec =
        generatedClass.newCodec(
            objectCodec,
            writeProperties,
            writeCodecs,
            readProperties,
            readCodecs,
            readObjectCodecs);
    registerWriterCallbacks(typeResolver, codec, writeProperties, writeCodecs);
    registerReaderCallbacks(
        typeResolver, codec, type, readProperties, readCodecs, readObjectCodecs);
    return codec;
  }

  private GeneratedObjectCodecClass buildClass(ObjectCodec objectCodec) {
    Class<?> type = objectCodec.type();
    boolean record = objectCodec.isRecord();
    JsonFieldInfo[] writeProperties = objectCodec.writeFields();
    JsonFieldInfo[] readProperties = objectCodec.readFields();
    String generatedPackage = CodeGenerator.getPackage(type);
    String generatedClassName = className(type, "Object");
    Class<?> codecClass =
        compileCodecClass(
            generatedPackage, generatedClassName, type, writeProperties, readProperties, record);
    return new GeneratedObjectCodecClass(codecClass);
  }

  public boolean canCompile(BaseObjectCodec objectCodec) {
    Class<?> type = objectCodec.type();
    if (!canCompileType(type)) {
      return false;
    }
    boolean record = objectCodec.isRecord();
    JsonFieldInfo[] writeProperties = objectCodec.writeFields();
    for (int i = 0; i < writeProperties.length; i++) {
      if (!canCompileWrite(writeProperties[i])) {
        return false;
      }
    }
    JsonFieldInfo[] readProperties = objectCodec.readFields();
    for (int i = 0; i < readProperties.length; i++) {
      if (!canCompileRead(readProperties[i], record)) {
        return false;
      }
    }
    return true;
  }

  private Class<?> compileCodecClass(
      String generatedPackage,
      String className,
      Class<?> type,
      JsonFieldInfo[] writeProperties,
      JsonFieldInfo[] readProperties,
      boolean record) {
    String code =
        new JsonGeneratedCodecBuilder(
                this, generatedPackage, className, type, writeProperties, readProperties, record)
            .genCode();
    try {
      return compileClass(generatedPackage, className, code);
    } catch (Throwable e) {
      throw new ForyJsonException("Cannot compile generated JSON codec " + className, e);
    }
  }

  private Class<?> compileClass(String generatedPackage, String className, String code)
      throws ClassNotFoundException {
    CompileUnit unit = new CompileUnit(generatedPackage, className, code);
    ClassLoader classLoader = codeGenerator.compile(unit);
    return classLoader.loadClass(qualifiedClassName(generatedPackage, className));
  }

  private static JsonCodec[] writeCodecs(JsonFieldInfo[] properties) {
    JsonCodec[] codecs = new JsonCodec[properties.length];
    for (int i = 0; i < properties.length; i++) {
      if (usesWriteCodec(properties[i])) {
        codecs[i] = properties[i].writeTypeInfo().codec();
      }
    }
    return codecs;
  }

  private static JsonCodec[] readCodecs(JsonFieldInfo[] properties) {
    JsonCodec[] codecs = new JsonCodec[properties.length];
    for (int i = 0; i < properties.length; i++) {
      if (usesReadCodec(properties[i])) {
        codecs[i] = properties[i].readTypeInfo().codec();
      }
    }
    return codecs;
  }

  private BaseObjectCodec[] readObjectCodecs(
      BaseObjectCodec objectCodec, JsonTypeResolver typeResolver) {
    JsonFieldInfo[] properties = objectCodec.readFields();
    BaseObjectCodec[] nestedCodecs = new BaseObjectCodec[properties.length];
    Class<?> type = objectCodec.type();
    for (int i = 0; i < properties.length; i++) {
      Class<?> nestedType = readNestedType(properties[i]);
      if (nestedType != null && nestedType != type) {
        nestedCodecs[i] = typeResolver.getObjectCodec(nestedType);
      }
    }
    return nestedCodecs;
  }

  static Class<?> readNestedType(JsonFieldInfo property) {
    if (property.readKind() == JsonFieldKind.OBJECT
        && property.readRawType() != Object.class
        && property.readTypeInfo().codec() instanceof BaseObjectCodec) {
      return property.readRawType();
    }
    return null;
  }

  private boolean canCompileWrite(JsonFieldInfo property) {
    Field field = property.writeField();
    if (field == null && property.writeGetter() == null) {
      return false;
    }
    if (property.writeGetter() != null && !canCall(property.writeGetter())) {
      return false;
    }
    Class<?> rawType = property.writeRawType();
    if (rawType != null && !rawType.isPrimitive() && !isVisible(rawType)) {
      return false;
    }
    if (property.writeKind() != JsonFieldKind.COLLECTION) {
      return true;
    }
    Class<?> elementType = property.writeElementRawType();
    return !isPojo(elementType) || isVisible(elementType);
  }

  private boolean canCompileRead(JsonFieldInfo property, boolean record) {
    if (!record && property.readAccessor() == null) {
      return false;
    }
    if (!record && property.readSetter() != null && !canCall(property.readSetter())) {
      return false;
    }
    if (!record
        && property.readSetter() == null
        && property.readAccessor().coreAccessor() == null) {
      return false;
    }
    Class<?> rawType = property.readRawType();
    if (rawType != null && !rawType.isPrimitive() && !isVisible(rawType)) {
      return false;
    }
    if (property.readKind() != JsonFieldKind.COLLECTION) {
      return true;
    }
    Class<?> elementType = property.readElementRawType();
    return !isPojo(elementType) || isVisible(elementType);
  }

  private boolean canCompileType(Class<?> type) {
    return CodeGenerator.sourcePublicAccessible(type) && isVisible(type);
  }

  private boolean canCall(Method method) {
    return Modifier.isPublic(method.getModifiers())
        && CodeGenerator.sourcePublicAccessible(method.getDeclaringClass());
  }

  private boolean isVisible(Class<?> type) {
    if (type.isPrimitive()) {
      return true;
    }
    while (type.isArray()) {
      type = type.getComponentType();
    }
    if (type.isPrimitive()) {
      return true;
    }
    String name = type.getName();
    try {
      return Class.forName(name, false, jsonLoader) == type;
    } catch (ClassNotFoundException e) {
      return false;
    }
  }

  Class<?> codecFieldType(JsonCodec codec) {
    if (codec instanceof BaseObjectCodec) {
      return BaseObjectCodec.class;
    }
    Class<?> type = codec.getClass();
    if (isPublicSourceType(type) && isVisible(type)) {
      return type;
    }
    return JsonCodec.class;
  }

  private static void registerWriterCallbacks(
      JsonTypeResolver resolver,
      GeneratedObjectCodec codecOwner,
      JsonFieldInfo[] properties,
      JsonCodec[] codecs) {
    for (int i = 0; i < properties.length; i++) {
      JsonFieldInfo property = properties[i];
      JsonCodec codec = codecs[i];
      if (usesWriteCodec(property) && codec instanceof BaseObjectCodec) {
        registerFieldCallback(resolver, codecOwner, "w" + i, codec);
      }
    }
  }

  private static void registerReaderCallbacks(
      JsonTypeResolver resolver,
      GeneratedObjectCodec codecOwner,
      Class<?> type,
      JsonFieldInfo[] properties,
      JsonCodec[] codecs,
      BaseObjectCodec[] objectCodecs) {
    for (int i = 0; i < properties.length; i++) {
      JsonFieldInfo property = properties[i];
      JsonCodec codec = codecs[i];
      if (usesReadCodec(property) && codec instanceof BaseObjectCodec) {
        registerFieldCallback(resolver, codecOwner, "r" + i, codec);
      }
      if (storesReadObjectCodec(type, property)) {
        registerFieldCallback(resolver, codecOwner, "o" + i, objectCodecs[i]);
      }
    }
  }

  private static void registerFieldCallback(
      JsonTypeResolver resolver,
      GeneratedObjectCodec codecOwner,
      String fieldName,
      JsonCodec currentCodec) {
    Field field = ReflectionUtils.getField(codecOwner.getClass(), fieldName);
    resolver.registerJITNotifyCallback(
        currentCodec, codec -> ReflectionUtils.setObjectFieldValue(codecOwner, field, codec));
  }

  private static boolean isPublicSourceType(Class<?> type) {
    if (!CodeGenerator.sourcePublicAccessible(type)) {
      return false;
    }
    for (Class<?> current = type; current != null; current = current.getEnclosingClass()) {
      if (!Modifier.isPublic(current.getModifiers())) {
        return false;
      }
    }
    return true;
  }

  static boolean usesWriteCodec(JsonFieldInfo property) {
    switch (property.writeKind()) {
      case ARRAY:
      case MAP:
      case OBJECT:
        return true;
      case COLLECTION:
        return !writesStringCollectionDirectly(property);
      default:
        return false;
    }
  }

  static boolean writesStringCollectionDirectly(JsonFieldInfo property) {
    return property.writeElementRawType() == String.class
        && property.writeTypeInfo().codec().getClass()
            == CollectionCodec.StringCollectionCodec.class;
  }

  static boolean usesReadCodec(JsonFieldInfo property) {
    switch (property.readKind()) {
      case ENUM:
      case ARRAY:
      case COLLECTION:
      case MAP:
        return true;
      case OBJECT:
        return !usesReadObjectCodec(property);
      default:
        return false;
    }
  }

  static boolean usesReadTypeField(JsonFieldInfo property) {
    switch (property.readKind()) {
      case ARRAY:
      case COLLECTION:
      case MAP:
        return true;
      case OBJECT:
        return true;
      default:
        return false;
    }
  }

  static boolean usesReadObjectCodec(JsonFieldInfo property) {
    return property.readKind() == JsonFieldKind.OBJECT
        && property.readRawType() != Object.class
        && property.readTypeInfo().codec() instanceof BaseObjectCodec;
  }

  static boolean storesReadObjectCodec(Class<?> type, JsonFieldInfo property) {
    Class<?> nestedType = readNestedType(property);
    return nestedType != null && nestedType != type;
  }

  private String className(Class<?> type, String role) {
    String name = simpleClassName(type) + role + "ForyJsonCodec";
    Map<String, Integer> subGenerator =
        ID_GENERATOR.computeIfAbsent(name, key -> new ConcurrentHashMap<>());
    String key = codegenHash + "_" + CodeGenerator.getClassUniqueId(type);
    Integer id = subGenerator.get(key);
    if (id == null) {
      synchronized (subGenerator) {
        id = subGenerator.computeIfAbsent(key, ignored -> subGenerator.size());
      }
    }
    return id == 0 ? name : name + id;
  }

  private static String simpleClassName(Class<?> type) {
    String name = type.getName();
    Package declaringPackage = type.getPackage();
    if (declaringPackage != null) {
      String prefix = declaringPackage.getName() + ".";
      if (name.startsWith(prefix)) {
        name = name.substring(prefix.length());
      }
    } else {
      int separator = name.lastIndexOf('.');
      if (separator >= 0) {
        name = name.substring(separator + 1);
      }
    }
    return name.replace('.', '_').replace('$', '_');
  }

  private static String qualifiedClassName(String generatedPackage, String className) {
    return generatedPackage.isEmpty() ? className : generatedPackage + "." + className;
  }

  private static boolean isPojo(Class<?> type) {
    return type != null
        && type != Object.class
        && type != String.class
        && type != Boolean.class
        && type != Byte.class
        && type != Short.class
        && type != Integer.class
        && type != Long.class
        && type != Float.class
        && type != Double.class
        && type != Character.class
        && !type.isPrimitive()
        && !type.isEnum()
        && !type.isArray()
        && !Collection.class.isAssignableFrom(type)
        && !Map.class.isAssignableFrom(type);
  }

  private static boolean isRecordField(JsonFieldInfo property) {
    Field field = property.writeField();
    return field != null && RecordUtils.isRecord(field.getDeclaringClass());
  }

  @Internal
  public static final class GeneratedObjectCodecClass {
    private final MethodHandle constructor;
    private final Constructor<?> androidConstructor;

    private GeneratedObjectCodecClass(Class<?> codecClass) {
      try {
        if (AndroidSupport.IS_ANDROID) {
          androidConstructor = codecConstructor(codecClass);
          constructor = null;
        } else {
          constructor = codecHandle(codecClass);
          androidConstructor = null;
        }
      } catch (Throwable e) {
        throw new ForyJsonException(
            "Cannot resolve generated JSON codec constructor for " + codecClass.getName(), e);
      }
    }

    private GeneratedObjectCodec newCodec(
        ObjectCodec base,
        JsonFieldInfo[] writeProperties,
        JsonCodec[] writeCodecs,
        JsonFieldInfo[] readProperties,
        JsonCodec[] readCodecs,
        BaseObjectCodec[] objectCodecs) {
      try {
        if (AndroidSupport.IS_ANDROID) {
          return (GeneratedObjectCodec)
              androidConstructor.newInstance(
                  base, writeProperties, writeCodecs, readProperties, readCodecs, objectCodecs);
        }
        return (GeneratedObjectCodec)
            constructor.invoke(
                base, writeProperties, writeCodecs, readProperties, readCodecs, objectCodecs);
      } catch (Throwable e) {
        throw new ForyJsonException("Cannot instantiate generated JSON codec", e);
      }
    }

    private static Constructor<?> codecConstructor(Class<?> codecClass)
        throws NoSuchMethodException {
      Constructor<?> constructor =
          codecClass.getDeclaredConstructor(
              ObjectCodec.class,
              JsonFieldInfo[].class,
              JsonCodec[].class,
              JsonFieldInfo[].class,
              JsonCodec[].class,
              BaseObjectCodec[].class);
      constructor.setAccessible(true);
      return constructor;
    }

    private static MethodHandle codecHandle(Class<?> codecClass)
        throws NoSuchMethodException, IllegalAccessException {
      return _JDKAccess._trustedLookup(codecClass)
          .findConstructor(
              codecClass,
              MethodType.methodType(
                  void.class,
                  ObjectCodec.class,
                  JsonFieldInfo[].class,
                  JsonCodec[].class,
                  JsonFieldInfo[].class,
                  JsonCodec[].class,
                  BaseObjectCodec[].class));
    }
  }
}
