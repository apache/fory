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

package org.apache.fory.json.codec;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.fory.annotation.Internal;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.PropertyNamingStrategy;
import org.apache.fory.json.annotation.JsonCreator;
import org.apache.fory.json.annotation.JsonIgnore;
import org.apache.fory.json.annotation.JsonProperty;
import org.apache.fory.json.meta.JsonCreatorFieldInfo;
import org.apache.fory.json.meta.JsonCreatorInfo;
import org.apache.fory.json.meta.JsonFieldAccessor;
import org.apache.fory.json.meta.JsonFieldInfo;
import org.apache.fory.json.meta.JsonFieldTable;
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.json.reader.Utf16JsonReader;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.apache.fory.json.resolver.JsonTypeResolver;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;
import org.apache.fory.reflect.ObjectInstantiator;
import org.apache.fory.reflect.ObjectInstantiators;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.util.record.RecordComponent;
import org.apache.fory.util.record.RecordInfo;
import org.apache.fory.util.record.RecordUtils;

/**
 * Reflection-backed semantic codec and metadata owner for one Java object type.
 *
 * <p>Construction discovers eligible fields and JavaBean properties, merges each
 * field/getter/setter group into one logical property, applies its name, inclusion, and directional
 * ignore rules, resolves generic member types against the owner {@link TypeRef}, and builds
 * separate ordered read and write field arrays. Class-valued fields and properties are never JSON
 * members. Records retain constructor metadata and field defaults; ordinary objects retain an
 * allocation strategy plus field or accessor sinks.
 *
 * <p>This codec is the interpreted implementation and the semantic fallback. Only an exact
 * raw-class instance of this class is eligible for generated capability replacement. Parameterized
 * object codecs retain binding-specific member types and remain the owner of all five slots.
 * Generated code may replace paths independently, but it is built from this codec's immutable field
 * metadata and preserves the same null, unknown-field, record, and member-discovery semantics.
 */
public class ObjectCodec<T> implements JsonCodec<T>, StringObjectWriter<T>, Utf8ObjectWriter<T> {
  private static final int MUTABLE = 0;
  private static final int RECORD = 1;
  private static final int CREATOR = 2;

  protected final Class<?> type;
  protected final JsonFieldInfo[] writeFields;
  protected final JsonFieldInfo[] readFields;
  protected final JsonFieldTable readTable;
  protected final ObjectInstantiator<?> instantiator;
  private final int creationKind;
  private final JsonCreatorInfo creatorInfo;
  private final RecordInfo recordInfo;
  private final Object[] recordFieldDefaults;

  private ObjectCodec(
      Class<?> type,
      JsonFieldInfo[] writeFields,
      JsonFieldInfo[] readFields,
      String[] readJavaNames,
      JsonCreatorInfo creatorInfo,
      ObjectInstantiator<?> instantiator) {
    this.type = type;
    this.writeFields = writeFields;
    this.readFields = readFields;
    readTable = new JsonFieldTable(readFields);
    this.instantiator = instantiator;
    this.creatorInfo = creatorInfo;
    creationKind = RecordUtils.isRecord(type) ? RECORD : creatorInfo == null ? MUTABLE : CREATOR;
    if (creationKind == RECORD) {
      recordInfo = new RecordInfo(type, Arrays.asList(readJavaNames));
      recordFieldDefaults = recordFieldDefaults(type, readJavaNames, recordInfo);
    } else {
      recordInfo = null;
      recordFieldDefaults = null;
    }
  }

  public static <T> ObjectCodec<T> build(
      TypeRef<T> ownerType,
      boolean propertyDiscoveryEnabled,
      PropertyNamingStrategy propertyNamingStrategy,
      boolean writeNullFields) {
    Class<?> type = ownerType.getRawType();
    if (type.isInterface()
        || Modifier.isAbstract(type.getModifiers())
        || type.isPrimitive()
        || type.isArray()
        || type.isEnum()) {
      throw new ForyJsonException("Unsupported JSON object type " + type);
    }
    boolean record = RecordUtils.isRecord(type);
    rejectIneligibleAnnotations(type, propertyDiscoveryEnabled, record);
    LinkedHashMap<String, FieldBuilder> builders = new LinkedHashMap<>();
    addFields(type, record, propertyDiscoveryEnabled, builders);
    if (propertyDiscoveryEnabled && !record) {
      addAccessors(type, builders);
    } else if (record) {
      addRecordAccessors(type, builders);
    }
    JsonCreatorInfo creatorInfo =
        record
            ? rejectRecordCreator(type)
            : buildCreatorInfo(type, ownerType, builders, propertyNamingStrategy);
    List<JsonFieldInfo> writes = new ArrayList<>();
    List<JsonFieldInfo> reads = new ArrayList<>();
    List<String> readJavaNames = record ? new ArrayList<>() : null;
    Map<String, FieldBuilder> canonicalNames = new LinkedHashMap<>();
    Map<Long, String> canonicalHashes = new LinkedHashMap<>();
    for (FieldBuilder builder : builders.values()) {
      if (!builder.hasWriteSource() && !builder.hasReadSink()) {
        if (builder.hasConfiguration()) {
          throw new ForyJsonException(
              "JSON property annotation has no readable or writable direction for " + builder.name);
        }
        continue;
      }
      if (creatorInfo != null && !builder.hasWriteSource()) {
        if (builder.explicitInclude != JsonProperty.Include.DEFAULT) {
          throw new ForyJsonException(
              "JSON inclusion policy requires a write source for property " + builder.name);
        }
        if (!builder.creatorBound && builder.hasConfiguration()) {
          throw new ForyJsonException(
              "JSON property configuration is outside the creator read schema for " + builder.name);
        }
        continue;
      }
      JsonFieldInfo field =
          builder.build(record, ownerType, propertyNamingStrategy, writeNullFields);
      FieldBuilder priorProperty = canonicalNames.put(field.name(), builder);
      if (priorProperty != null) {
        throw new ForyJsonException(
            "Duplicate canonical JSON property name "
                + field.name()
                + " for "
                + priorProperty.nameDescription(propertyNamingStrategy)
                + " and "
                + builder.nameDescription(propertyNamingStrategy)
                + " on "
                + type.getName());
      }
      if (builder.hasWriteSource()) {
        writes.add(field);
      }
      if (creatorInfo == null && builder.hasReadSink()) {
        String priorHashName = canonicalHashes.put(field.nameHash(), field.name());
        if (priorHashName != null && !priorHashName.equals(field.name())) {
          throw new ForyJsonException(
              "JSON property name hash collision between "
                  + priorHashName
                  + " and "
                  + field.name());
        }
        reads.add(field);
        if (record) {
          readJavaNames.add(builder.name);
        }
      }
    }
    JsonFieldInfo[] writeArray = writes.toArray(new JsonFieldInfo[0]);
    JsonFieldInfo[] readArray = reads.toArray(new JsonFieldInfo[0]);
    for (int i = 0; i < readArray.length; i++) {
      readArray[i].setReadIndex(i);
    }
    ObjectInstantiator<?> instantiator = ObjectInstantiators.createObjectInstantiator(type);
    String[] recordNames = record ? readJavaNames.toArray(new String[0]) : null;
    if (ownerType.getType() instanceof Class) {
      return new ObjectCodec<>(type, writeArray, readArray, recordNames, creatorInfo, instantiator);
    }
    return new ParameterizedObjectCodec<>(
        type, writeArray, readArray, recordNames, creatorInfo, instantiator);
  }

  private static void addFields(
      Class<?> type,
      boolean record,
      boolean propertyDiscoveryEnabled,
      LinkedHashMap<String, FieldBuilder> builders) {
    List<Class<?>> hierarchy = new ArrayList<>();
    for (Class<?> current = type;
        current != null && current != Object.class;
        current = current.getSuperclass()) {
      hierarchy.add(current);
    }
    for (int i = hierarchy.size() - 1; i >= 0; i--) {
      Class<?> current = hierarchy.get(i);
      for (Field field : current.getDeclaredFields()) {
        int modifiers = field.getModifiers();
        if (!isEligibleField(field)) {
          continue;
        }
        JsonIgnore ignore = field.getAnnotation(JsonIgnore.class);
        boolean write = ignore == null || !ignore.ignoreWrite();
        boolean readAllowed = ignore == null || !ignore.ignoreRead();
        boolean read = (record || !Modifier.isFinal(modifiers)) && readAllowed;
        if (!propertyDiscoveryEnabled && !write && !read) {
          continue;
        }
        FieldBuilder builder =
            builders.computeIfAbsent(field.getName(), name -> new FieldBuilder(name));
        builder.setField(field, write, read, write, readAllowed);
      }
    }
  }

  private static void addAccessors(Class<?> type, LinkedHashMap<String, FieldBuilder> builders) {
    for (Method method : type.getMethods()) {
      if (!isEligibleAccessor(method)) {
        continue;
      }
      String propertyName = getterPropertyName(method);
      if (propertyName != null) {
        FieldBuilder builder = builders.get(propertyName);
        if (builder == null) {
          builder = new FieldBuilder(propertyName);
          builders.put(propertyName, builder);
        }
        builder.setWriteGetter(method);
        continue;
      }
      propertyName = setterPropertyName(method);
      if (propertyName != null) {
        FieldBuilder builder = builders.get(propertyName);
        if (builder == null) {
          builder = new FieldBuilder(propertyName);
          builders.put(propertyName, builder);
        }
        builder.setReadSetter(method);
      }
    }
  }

  private static void addRecordAccessors(
      Class<?> type, LinkedHashMap<String, FieldBuilder> builders) {
    RecordComponent[] components = RecordUtils.getRecordComponents(type);
    for (RecordComponent component : components) {
      FieldBuilder builder = builders.get(component.getName());
      if (builder == null) {
        throw new ForyJsonException("Missing JSON record field " + component.getName());
      }
      // Record accessors carry component annotations in Java 16+, but field access remains the
      // optimized read/write owner. The accessor participates only in logical-property annotation
      // merging and is discarded before hot metadata is published.
      builder.mergeAnnotation(component.getAccessor());
    }
  }

  private static JsonCreatorInfo rejectRecordCreator(Class<?> type) {
    for (Constructor<?> constructor : type.getDeclaredConstructors()) {
      if (constructor.isAnnotationPresent(JsonCreator.class)) {
        throw new ForyJsonException("@JsonCreator is not supported on record " + type.getName());
      }
    }
    for (Method method : type.getDeclaredMethods()) {
      if (method.isAnnotationPresent(JsonCreator.class)) {
        throw new ForyJsonException("@JsonCreator is not supported on record " + type.getName());
      }
    }
    return null;
  }

  private static JsonCreatorInfo buildCreatorInfo(
      Class<?> type,
      TypeRef<?> ownerType,
      LinkedHashMap<String, FieldBuilder> builders,
      PropertyNamingStrategy namingStrategy) {
    Executable creator = null;
    JsonCreator annotation = null;
    for (Constructor<?> constructor : type.getDeclaredConstructors()) {
      JsonCreator candidate = constructor.getAnnotation(JsonCreator.class);
      if (candidate != null) {
        validateCreator(type, constructor);
        if (creator != null) {
          throw new ForyJsonException("Multiple @JsonCreator declarations on " + type.getName());
        }
        creator = constructor;
        annotation = candidate;
      }
    }
    for (Method method : type.getDeclaredMethods()) {
      JsonCreator candidate = method.getAnnotation(JsonCreator.class);
      if (candidate != null) {
        validateCreator(type, method);
        if (creator != null) {
          throw new ForyJsonException("Multiple @JsonCreator declarations on " + type.getName());
        }
        creator = method;
        annotation = candidate;
      }
    }
    if (creator == null) {
      return null;
    }

    Map<String, FieldBuilder> jsonProperties = new LinkedHashMap<>();
    for (FieldBuilder builder : builders.values()) {
      if (!builder.hasLogicalMember()) {
        continue;
      }
      String jsonName = builder.jsonName(namingStrategy);
      FieldBuilder prior = jsonProperties.put(jsonName, builder);
      if (prior != null) {
        throw new ForyJsonException(
            "Duplicate canonical JSON property name "
                + jsonName
                + " for "
                + prior.nameDescription(namingStrategy)
                + " and "
                + builder.nameDescription(namingStrategy)
                + " on "
                + type.getName());
      }
    }

    Type[] parameterTypes = creator.getGenericParameterTypes();
    Class<?>[] rawTypes = creator.getParameterTypes();
    Parameter[] parameters = creator.getParameters();
    JsonCreatorFieldInfo[] fields = new JsonCreatorFieldInfo[parameterTypes.length];
    String[] propertyNames = annotation.value();
    if (propertyNames.length != 0) {
      if (propertyNames.length != parameterTypes.length) {
        throw new ForyJsonException(
            "@JsonCreator property count does not match parameter count on " + creator);
      }
      Set<String> names = new HashSet<>();
      for (int i = 0; i < propertyNames.length; i++) {
        String javaName = propertyNames[i];
        if (javaName.isEmpty() || !names.add(javaName)) {
          throw new ForyJsonException("Invalid @JsonCreator property name " + javaName);
        }
        if (parameters[i].isAnnotationPresent(JsonProperty.class)) {
          throw new ForyJsonException(
              "Property-list @JsonCreator parameters cannot declare @JsonProperty: " + creator);
        }
        FieldBuilder builder = builders.get(javaName);
        if (builder == null || !builder.hasLogicalMember()) {
          throw new ForyJsonException("Unknown @JsonCreator Java property " + javaName);
        }
        bindCreatorType(ownerType, creator, i, parameterTypes[i], builder);
        if (!builder.creatorReadAllowed()) {
          throw new ForyJsonException("@JsonCreator property is ignored for reading: " + javaName);
        }
        Type resolved = ownerType.resolveType(parameterTypes[i]).getType();
        fields[i] =
            new JsonCreatorFieldInfo(builder.jsonName(namingStrategy), i, resolved, rawTypes[i]);
      }
    } else {
      Set<String> names = new HashSet<>();
      for (int i = 0; i < parameters.length; i++) {
        JsonProperty property = parameters[i].getAnnotation(JsonProperty.class);
        if (property == null || property.value().isEmpty()) {
          throw new ForyJsonException(
              "Parameter-local @JsonCreator requires a non-empty @JsonProperty on every parameter: "
                  + creator);
        }
        String jsonName = property.value();
        if (!names.add(jsonName)) {
          throw new ForyJsonException("Duplicate @JsonCreator JSON property " + jsonName);
        }
        FieldBuilder builder = jsonProperties.get(jsonName);
        if (builder != null) {
          bindCreatorType(ownerType, creator, i, parameterTypes[i], builder);
          if (!builder.creatorReadAllowed()) {
            throw new ForyJsonException(
                "@JsonCreator property is ignored for reading: " + builder.name);
          }
          builder.mergeAnnotation(parameters[i]);
          if (property.include() != JsonProperty.Include.DEFAULT && !builder.hasWriteSource()) {
            throw new ForyJsonException(
                "Creator parameter inclusion requires a write source for " + jsonName);
          }
        } else if (property.include() != JsonProperty.Include.DEFAULT) {
          throw new ForyJsonException(
              "Creator-only property cannot declare an inclusion policy: " + jsonName);
        }
        Type resolved = ownerType.resolveType(parameterTypes[i]).getType();
        fields[i] = new JsonCreatorFieldInfo(jsonName, i, resolved, rawTypes[i]);
      }
    }
    rejectCreatorHashCollisions(fields);
    return new JsonCreatorInfo(type, creator, fields, creatorDefaults(rawTypes));
  }

  private static void validateCreator(Class<?> type, Executable creator) {
    int modifiers = creator.getModifiers();
    if (!Modifier.isPublic(modifiers)
        || creator.isSynthetic()
        || creator.isVarArgs()
        || creator.getParameterCount() == 0
        || creator.getTypeParameters().length != 0) {
      throw new ForyJsonException("Invalid @JsonCreator executable " + creator);
    }
    if (creator instanceof Method) {
      Method factory = (Method) creator;
      if (!Modifier.isStatic(modifiers) || factory.isBridge() || factory.getReturnType() != type) {
        throw new ForyJsonException("Invalid @JsonCreator factory " + factory);
      }
    }
  }

  private static void bindCreatorType(
      TypeRef<?> ownerType,
      Executable creator,
      int parameterIndex,
      Type parameterType,
      FieldBuilder builder) {
    Type resolvedParameter = ownerType.resolveType(parameterType).getType();
    Type propertyType = builder.logicalType(ownerType);
    if (!resolvedParameter.equals(propertyType)) {
      throw new ForyJsonException(
          "@JsonCreator parameter type "
              + resolvedParameter
              + " does not match property "
              + builder.name
              + " type "
              + propertyType
              + " on "
              + creator
              + " parameter "
              + parameterIndex);
    }
    builder.creatorBound = true;
  }

  private static void rejectCreatorHashCollisions(JsonCreatorFieldInfo[] fields) {
    Map<Long, String> names = new LinkedHashMap<>();
    for (JsonCreatorFieldInfo field : fields) {
      String prior = names.put(field.nameHash(), field.name());
      if (prior != null) {
        throw new ForyJsonException(
            "JSON creator property hash collision between " + prior + " and " + field.name());
      }
    }
  }

  private static Object[] creatorDefaults(Class<?>[] types) {
    Object[] defaults = new Object[types.length];
    for (int i = 0; i < types.length; i++) {
      Class<?> type = types[i];
      if (type == boolean.class) {
        defaults[i] = Boolean.FALSE;
      } else if (type == byte.class) {
        defaults[i] = Byte.valueOf((byte) 0);
      } else if (type == short.class) {
        defaults[i] = Short.valueOf((short) 0);
      } else if (type == int.class) {
        defaults[i] = Integer.valueOf(0);
      } else if (type == long.class) {
        defaults[i] = Long.valueOf(0L);
      } else if (type == float.class) {
        defaults[i] = Float.valueOf(0F);
      } else if (type == double.class) {
        defaults[i] = Double.valueOf(0D);
      } else if (type == char.class) {
        defaults[i] = Character.valueOf((char) 0);
      }
    }
    return defaults;
  }

  public final Class<?> type() {
    return type;
  }

  public final JsonFieldInfo[] writeFields() {
    return writeFields;
  }

  public final JsonFieldInfo[] readFields() {
    return readFields;
  }

  public final JsonFieldTable readTable() {
    return readTable;
  }

  public final boolean isRecord() {
    return creationKind == RECORD;
  }

  public final JsonCreatorInfo creatorInfo() {
    return creatorInfo;
  }

  @Internal
  public final Object requireCreatorResult(Object value) {
    if (value == null || value.getClass() != type) {
      throw new ForyJsonException("JSON creator must return an exact non-null " + type.getName());
    }
    return value;
  }

  @Internal
  public final ForyJsonException creatorFailure(Exception cause) {
    return new ForyJsonException("JSON creator failed for " + type.getName(), cause);
  }

  public final void resolveTypes(JsonTypeResolver typeResolver) {
    for (JsonFieldInfo field : writeFields) {
      field.resolveTypes(typeResolver);
    }
    for (JsonFieldInfo field : readFields) {
      field.resolveTypes(typeResolver);
    }
    if (creatorInfo != null) {
      creatorInfo.resolveTypes(typeResolver);
    }
  }

  @SuppressWarnings("unchecked")
  public final T newInstance() {
    return (T) instantiator.newInstance();
  }

  @Internal
  public final Object[] newRecordFieldValues() {
    return Arrays.copyOf(recordFieldDefaults, recordFieldDefaults.length);
  }

  @Internal
  @SuppressWarnings("unchecked")
  public final T newRecord(Object[] values) {
    Object[] arguments = RecordUtils.remapping(recordInfo, values);
    Object object = instantiator.newInstanceWithArguments(arguments);
    Arrays.fill(recordInfo.getRecordComponents(), null);
    return (T) object;
  }

  @Override
  public void writeString(StringJsonWriter writer, T value) {
    StringWriterCodec<T> codec = writer.typeResolver().stringWriter(this);
    if (codec != this) {
      codec.writeString(writer, value);
    } else if (value == null) {
      writer.writeNull();
    } else {
      writeStringObject(writer, value);
    }
  }

  @Override
  public void writeUtf8(Utf8JsonWriter writer, T value) {
    Utf8WriterCodec<T> codec = writer.typeResolver().utf8Writer(this);
    if (codec != this) {
      codec.writeUtf8(writer, value);
    } else if (value == null) {
      writer.writeNull();
    } else {
      writeUtf8Object(writer, value);
    }
  }

  // Raw and parameterized bindings share the same interpreted object algorithms inside this
  // top-level owner. Package access avoids Java 8 synthetic accessors from the nested binding;
  // these methods are not codec entries and must not be used for capability dispatch.
  final T readLatin1Object(Latin1JsonReader reader) {
    reader.enterDepth();
    if (creationKind != MUTABLE) {
      if (creationKind == CREATOR) {
        Object[] arguments = readLatin1CreatorArguments(reader);
        reader.exitDepth();
        return create(arguments);
      }
      T object = readLatin1Record(reader);
      reader.exitDepth();
      return object;
    }
    T object = newInstance();
    reader.expect('{');
    if (reader.consume('}')) {
      reader.exitDepth();
      return object;
    }
    do {
      JsonFieldInfo field = reader.readField(readTable);
      reader.expect(':');
      if (field == null) {
        reader.skipValue();
      } else {
        field.readLatin1(reader, object);
      }
    } while (reader.consume(','));
    reader.expect('}');
    reader.exitDepth();
    return object;
  }

  final T readUtf16Object(Utf16JsonReader reader) {
    reader.enterDepth();
    if (creationKind != MUTABLE) {
      if (creationKind == CREATOR) {
        Object[] arguments = readUtf16CreatorArguments(reader);
        reader.exitDepth();
        return create(arguments);
      }
      T object = readUtf16Record(reader);
      reader.exitDepth();
      return object;
    }
    T object = newInstance();
    reader.expect('{');
    if (reader.consume('}')) {
      reader.exitDepth();
      return object;
    }
    do {
      JsonFieldInfo field = reader.readField(readTable);
      reader.expect(':');
      if (field == null) {
        reader.skipValue();
      } else {
        field.readUtf16(reader, object);
      }
    } while (reader.consume(','));
    reader.expect('}');
    reader.exitDepth();
    return object;
  }

  final T readUtf8Object(Utf8JsonReader reader) {
    reader.enterDepth();
    if (creationKind != MUTABLE) {
      if (creationKind == CREATOR) {
        Object[] arguments = readUtf8CreatorArguments(reader);
        reader.exitDepth();
        return create(arguments);
      }
      T object = readUtf8Record(reader);
      reader.exitDepth();
      return object;
    }
    T object = newInstance();
    reader.expect('{');
    if (reader.consume('}')) {
      reader.exitDepth();
      return object;
    }
    do {
      JsonFieldInfo field = reader.readField(readTable);
      reader.expect(':');
      if (field == null) {
        reader.skipValue();
      } else {
        field.readUtf8(reader, object);
      }
    } while (reader.consume(','));
    reader.expect('}');
    reader.exitDepth();
    return object;
  }

  @Override
  public T readLatin1(Latin1JsonReader reader) {
    Latin1ReaderCodec<T> codec = reader.typeResolver().latin1Reader(this);
    if (codec != this) {
      return codec.readLatin1(reader);
    }
    if (reader.tryReadNullToken()) {
      return null;
    }
    return readLatin1Object(reader);
  }

  @Override
  public T readUtf16(Utf16JsonReader reader) {
    Utf16ReaderCodec<T> codec = reader.typeResolver().utf16Reader(this);
    if (codec != this) {
      return codec.readUtf16(reader);
    }
    if (reader.tryReadNullToken()) {
      return null;
    }
    return readUtf16Object(reader);
  }

  @Override
  public T readUtf8(Utf8JsonReader reader) {
    Utf8ReaderCodec<T> codec = reader.typeResolver().utf8Reader(this);
    if (codec != this) {
      return codec.readUtf8(reader);
    }
    if (reader.tryReadNullToken()) {
      return null;
    }
    return readUtf8Object(reader);
  }

  private T readLatin1Record(Latin1JsonReader reader) {
    Object[] values = newRecordFieldValues();
    reader.expect('{');
    if (!reader.consume('}')) {
      do {
        JsonFieldInfo field = reader.readField(readTable);
        reader.expect(':');
        if (field == null) {
          reader.skipValue();
        } else {
          values[field.readIndex()] = field.readLatin1Value(reader);
        }
      } while (reader.consume(','));
      reader.expect('}');
    }
    return newRecord(values);
  }

  private T readUtf16Record(Utf16JsonReader reader) {
    Object[] values = newRecordFieldValues();
    reader.expect('{');
    if (!reader.consume('}')) {
      do {
        JsonFieldInfo field = reader.readField(readTable);
        reader.expect(':');
        if (field == null) {
          reader.skipValue();
        } else {
          values[field.readIndex()] = field.readUtf16Value(reader);
        }
      } while (reader.consume(','));
      reader.expect('}');
    }
    return newRecord(values);
  }

  private T readUtf8Record(Utf8JsonReader reader) {
    Object[] values = newRecordFieldValues();
    reader.expect('{');
    if (!reader.consume('}')) {
      do {
        JsonFieldInfo field = reader.readField(readTable);
        reader.expect(':');
        if (field == null) {
          reader.skipValue();
        } else {
          values[field.readIndex()] = field.readUtf8Value(reader);
        }
      } while (reader.consume(','));
      reader.expect('}');
    }
    return newRecord(values);
  }

  private Object[] readLatin1CreatorArguments(Latin1JsonReader reader) {
    Object[] arguments = creatorInfo.newArguments();
    JsonCreatorFieldInfo[] fields = creatorInfo.fields();
    reader.expect('{');
    if (!reader.consume('}')) {
      do {
        int index = creatorInfo.index(reader.readFieldNameHash());
        reader.expect(':');
        if (index < 0) {
          reader.skipValue();
        } else {
          JsonCreatorFieldInfo field = fields[index];
          arguments[field.argumentIndex()] = field.readLatin1(reader);
        }
      } while (reader.consume(','));
      reader.expect('}');
    }
    return arguments;
  }

  private Object[] readUtf16CreatorArguments(Utf16JsonReader reader) {
    Object[] arguments = creatorInfo.newArguments();
    JsonCreatorFieldInfo[] fields = creatorInfo.fields();
    reader.expect('{');
    if (!reader.consume('}')) {
      do {
        int index = creatorInfo.index(reader.readFieldNameHash());
        reader.expect(':');
        if (index < 0) {
          reader.skipValue();
        } else {
          JsonCreatorFieldInfo field = fields[index];
          arguments[field.argumentIndex()] = field.readUtf16(reader);
        }
      } while (reader.consume(','));
      reader.expect('}');
    }
    return arguments;
  }

  private Object[] readUtf8CreatorArguments(Utf8JsonReader reader) {
    Object[] arguments = creatorInfo.newArguments();
    JsonCreatorFieldInfo[] fields = creatorInfo.fields();
    reader.expect('{');
    if (!reader.consume('}')) {
      do {
        int index = creatorInfo.index(reader.readFieldNameHash());
        reader.expect(':');
        if (index < 0) {
          reader.skipValue();
        } else {
          JsonCreatorFieldInfo field = fields[index];
          arguments[field.argumentIndex()] = field.readUtf8(reader);
        }
      } while (reader.consume(','));
      reader.expect('}');
    }
    return arguments;
  }

  @SuppressWarnings("unchecked")
  private T create(Object[] arguments) {
    return (T) creatorInfo.create(arguments);
  }

  final void writeStringObject(StringJsonWriter writer, T value) {
    writer.writeObjectStart();
    writeStringMembers(writer, value, 0);
    writer.writeObjectEnd();
  }

  @Override
  public final void writeStringMembers(StringJsonWriter writer, T value, int written) {
    JsonFieldInfo[] fields = writeFields;
    int length = fields.length;
    int i = 0;
    while (i + 4 <= length) {
      if (fields[i++].writeString(writer, value, written)) {
        written++;
      }
      if (fields[i++].writeString(writer, value, written)) {
        written++;
      }
      if (fields[i++].writeString(writer, value, written)) {
        written++;
      }
      if (fields[i++].writeString(writer, value, written)) {
        written++;
      }
    }
    while (i < length) {
      if (fields[i++].writeString(writer, value, written)) {
        written++;
      }
    }
  }

  final void writeUtf8Object(Utf8JsonWriter writer, T value) {
    writer.writeObjectStart();
    writeUtf8Members(writer, value, 0);
    writer.writeObjectEnd();
  }

  @Override
  public final void writeUtf8Members(Utf8JsonWriter writer, T value, int written) {
    JsonFieldInfo[] fields = writeFields;
    int length = fields.length;
    int i = 0;
    while (i + 4 <= length) {
      if (fields[i++].writeUtf8(writer, value, written)) {
        written++;
      }
      if (fields[i++].writeUtf8(writer, value, written)) {
        written++;
      }
      if (fields[i++].writeUtf8(writer, value, written)) {
        written++;
      }
      if (fields[i++].writeUtf8(writer, value, written)) {
        written++;
      }
    }
    while (i < length) {
      if (fields[i++].writeUtf8(writer, value, written)) {
        written++;
      }
    }
  }

  private static void rejectIneligibleAnnotations(
      Class<?> type, boolean propertyDiscoveryEnabled, boolean record) {
    for (Class<?> current = type;
        current != null && current != Object.class;
        current = current.getSuperclass()) {
      for (Field field : current.getDeclaredFields()) {
        if (field.isAnnotationPresent(JsonProperty.class) && !isEligibleField(field)) {
          throw new ForyJsonException("@JsonProperty is not supported on JSON field: " + field);
        }
      }
      for (Method method : current.getDeclaredMethods()) {
        if (!method.isAnnotationPresent(JsonProperty.class)) {
          continue;
        }
        validatePropertyMethod(type, method, propertyDiscoveryEnabled, record);
      }
    }
    // Class.getMethods() is the accessor-discovery surface and includes inherited interface
    // methods, while the class-hierarchy scan above deliberately includes non-public declarations.
    // Validate annotated interface declarations here so no method considered by discovery can
    // become a silent no-op merely because its declaring type is outside the class hierarchy.
    for (Method method : type.getMethods()) {
      if (method.getDeclaringClass().isInterface()
          && method.isAnnotationPresent(JsonProperty.class)) {
        validatePropertyMethod(type, method, propertyDiscoveryEnabled, record);
      }
    }
  }

  private static void validatePropertyMethod(
      Class<?> type, Method method, boolean propertyDiscoveryEnabled, boolean record) {
    if (!propertyDiscoveryEnabled
        || !isEligibleAccessor(method)
        || record && !isRecordAccessor(type, method)) {
      throw new ForyJsonException("@JsonProperty is not supported on JSON method: " + method);
    }
    if (!record && getterPropertyName(method) == null && setterPropertyName(method) == null) {
      throw new ForyJsonException("@JsonProperty requires a JSON getter or setter: " + method);
    }
  }

  private static boolean isRecordAccessor(Class<?> type, Method method) {
    RecordComponent[] components = RecordUtils.getRecordComponents(type);
    for (RecordComponent component : components) {
      if (component.getAccessor().equals(method)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isEligibleField(Field field) {
    int modifiers = field.getModifiers();
    return !Modifier.isStatic(modifiers)
        && !Modifier.isTransient(modifiers)
        && field.getType() != Class.class
        && !field.isSynthetic();
  }

  private static boolean isEligibleAccessor(Method method) {
    int modifiers = method.getModifiers();
    return Modifier.isPublic(modifiers)
        && !Modifier.isStatic(modifiers)
        && !method.isSynthetic()
        && !method.isBridge();
  }

  private static String getterPropertyName(Method method) {
    if (method.getParameterCount() != 0
        || method.getReturnType() == void.class
        || method.getReturnType() == Class.class) {
      return null;
    }
    String name = method.getName();
    if (name.equals("getClass")) {
      return null;
    }
    if (name.length() > 3 && name.startsWith("get")) {
      return decapitalize(name.substring(3));
    }
    if (name.length() > 2
        && name.startsWith("is")
        && (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class)) {
      return decapitalize(name.substring(2));
    }
    return null;
  }

  private static String setterPropertyName(Method method) {
    if (method.getParameterCount() != 1
        || method.getReturnType() != void.class
        || method.getParameterTypes()[0] == Class.class) {
      return null;
    }
    String name = method.getName();
    if (name.length() > 3 && name.startsWith("set")) {
      return decapitalize(name.substring(3));
    }
    return null;
  }

  private static String decapitalize(String name) {
    if (name.length() > 1
        && Character.isUpperCase(name.charAt(0))
        && Character.isUpperCase(name.charAt(1))) {
      return name;
    }
    return Character.toLowerCase(name.charAt(0)) + name.substring(1);
  }

  private static Object[] recordFieldDefaults(
      Class<?> type, String[] readJavaNames, RecordInfo recordInfo) {
    Object[] defaults = new Object[readJavaNames.length];
    Object[] componentDefaults = recordInfo.getRecordComponentsDefaultValues();
    Map<String, Integer> componentIndexes = RecordUtils.buildFieldToComponentMapping(type);
    for (int i = 0; i < readJavaNames.length; i++) {
      Integer componentIndex = componentIndexes.get(readJavaNames[i]);
      defaults[i] = componentIndex == null ? null : componentDefaults[componentIndex.intValue()];
    }
    return defaults;
  }

  private static final class FieldBuilder {
    private final String name;
    private Field field;
    private boolean fieldWriteAllowed;
    private boolean fieldReadAllowed;
    private Field writeField;
    private Field readField;
    private Method writeGetter;
    private Method readSetter;
    private JsonFieldAccessor writeAccessor;
    private JsonFieldAccessor readAccessor;
    private String explicitName;
    private AnnotatedElement explicitNameSource;
    private JsonProperty.Include explicitInclude = JsonProperty.Include.DEFAULT;
    private AnnotatedElement explicitIncludeSource;
    private boolean creatorBound;

    private FieldBuilder(String name) {
      this.name = name;
    }

    private void setField(
        Field field,
        boolean writeSource,
        boolean readSink,
        boolean writeAllowed,
        boolean readAllowed) {
      if (this.field != null) {
        throw new ForyJsonException("Duplicate JSON field " + name);
      }
      this.field = field;
      fieldWriteAllowed = writeAllowed;
      fieldReadAllowed = readAllowed;
      if (writeSource) {
        writeField = field;
      }
      if (readSink) {
        readField = field;
      }
      mergeAnnotation(field);
    }

    private void setWriteGetter(Method getter) {
      mergeAnnotation(getter);
      if (field != null && !fieldWriteAllowed) {
        return;
      }
      if (writeGetter != null) {
        throw new ForyJsonException("Duplicate JSON getter for property " + name);
      }
      writeGetter = getter;
      writeField = null;
    }

    private void setReadSetter(Method setter) {
      mergeAnnotation(setter);
      if (field != null && !fieldReadAllowed) {
        return;
      }
      if (readSetter != null) {
        throw new ForyJsonException("Duplicate JSON setter for property " + name);
      }
      readSetter = setter;
      readField = null;
    }

    private boolean hasWriteSource() {
      return writeGetter != null || writeField != null;
    }

    private boolean hasReadSink() {
      return readSetter != null || readField != null;
    }

    private boolean hasConfiguration() {
      return explicitName != null || explicitInclude != JsonProperty.Include.DEFAULT;
    }

    private boolean hasLogicalMember() {
      return field != null || writeGetter != null || readSetter != null;
    }

    private boolean creatorReadAllowed() {
      return field == null || fieldReadAllowed;
    }

    private String jsonName(PropertyNamingStrategy strategy) {
      return explicitName == null ? translateName(name, strategy) : explicitName;
    }

    private String nameDescription(PropertyNamingStrategy strategy) {
      return explicitName == null
          ? "Java property " + name + " transformed by " + strategy
          : "Java property " + name + " explicitly named by " + explicitNameSource;
    }

    private Type logicalType(TypeRef<?> ownerType) {
      Type type;
      if (writeGetter != null) {
        type = writeGetter.getGenericReturnType();
      } else if (writeField != null) {
        type = writeField.getGenericType();
      } else if (readSetter != null) {
        type = readSetter.getGenericParameterTypes()[0];
      } else if (field != null) {
        // Final fields and ignored ordinary read sinks may still be creator-bound properties.
        type = field.getGenericType();
      } else {
        throw new ForyJsonException("JSON property has no type source " + name);
      }
      return ownerType.resolveType(type).getType();
    }

    private JsonFieldInfo build(
        boolean record,
        TypeRef<?> ownerType,
        PropertyNamingStrategy propertyNamingStrategy,
        boolean defaultWriteNull) {
      validateTypes(ownerType);
      if (explicitInclude != JsonProperty.Include.DEFAULT && !hasWriteSource()) {
        throw new ForyJsonException(
            "JSON inclusion policy requires a write source for property " + name);
      }
      String jsonName = jsonName(propertyNamingStrategy);
      if (jsonName.isEmpty()) {
        throw new ForyJsonException("JSON property name must not be empty for " + name);
      }
      Class<?> rawWriteType = hasWriteSource() ? writeRawType() : null;
      boolean writeNull =
          rawWriteType != null
              && (rawWriteType.isPrimitive()
                  || explicitInclude == JsonProperty.Include.ALWAYS
                  || explicitInclude == JsonProperty.Include.DEFAULT && defaultWriteNull);
      writeAccessor =
          writeGetter != null
              ? JsonFieldAccessor.forGetter(writeGetter)
              : (writeField == null ? null : JsonFieldAccessor.forField(writeField));
      readAccessor =
          readSetter != null
              ? JsonFieldAccessor.forSetter(readSetter)
              : (readField == null || record ? null : JsonFieldAccessor.forField(readField));
      return new JsonFieldInfo(
          jsonName,
          writeNull,
          writeField,
          writeGetter,
          readField,
          readSetter,
          writeAccessor,
          readAccessor,
          ownerType);
    }

    private void mergeAnnotation(AnnotatedElement source) {
      JsonProperty property = source.getAnnotation(JsonProperty.class);
      if (property == null) {
        return;
      }
      String declaredName = property.value();
      if (!declaredName.isEmpty()) {
        if (explicitName != null && !explicitName.equals(declaredName)) {
          throw new ForyJsonException(
              "Conflicting JSON names for property "
                  + name
                  + ": "
                  + explicitName
                  + " from "
                  + explicitNameSource
                  + " and "
                  + declaredName
                  + " from "
                  + source);
        }
        explicitName = declaredName;
        if (explicitNameSource == null) {
          explicitNameSource = source;
        }
      }
      JsonProperty.Include declaredInclude = property.include();
      if (declaredInclude != JsonProperty.Include.DEFAULT) {
        if (explicitInclude != JsonProperty.Include.DEFAULT && explicitInclude != declaredInclude) {
          throw new ForyJsonException(
              "Conflicting JSON inclusion policies for property "
                  + name
                  + ": "
                  + explicitInclude
                  + " from "
                  + explicitIncludeSource
                  + " and "
                  + declaredInclude
                  + " from "
                  + source);
        }
        explicitInclude = declaredInclude;
        if (explicitIncludeSource == null) {
          explicitIncludeSource = source;
        }
      }
    }

    private void validateTypes(TypeRef<?> ownerType) {
      Type writeType =
          writeGetter == null ? fieldType(writeField) : writeGetter.getGenericReturnType();
      Type readType =
          readSetter == null ? fieldType(readField) : readSetter.getGenericParameterTypes()[0];
      if (writeType != null) {
        writeType = ownerType.resolveType(writeType).getType();
      }
      if (readType != null) {
        readType = ownerType.resolveType(readType).getType();
      }
      if (writeType != null && readType != null && !writeType.equals(readType)) {
        throw new ForyJsonException(
            "Conflicting JSON property types for " + name + ": " + writeType + " and " + readType);
      }
    }

    private static Type fieldType(Field field) {
      return field == null ? null : field.getGenericType();
    }

    private Class<?> writeRawType() {
      return writeGetter != null ? writeGetter.getReturnType() : writeField.getType();
    }
  }

  private static String translateName(String name, PropertyNamingStrategy strategy) {
    if (strategy == PropertyNamingStrategy.IDENTITY) {
      return name;
    }
    StringBuilder builder = new StringBuilder(name.length() + 4);
    int previous = -1;
    boolean previousUpper = false;
    for (int offset = 0; offset < name.length(); ) {
      int codePoint = name.codePointAt(offset);
      int width = Character.charCount(codePoint);
      int nextOffset = offset + width;
      int next = nextOffset < name.length() ? name.codePointAt(nextOffset) : -1;
      boolean upper = Character.isUpperCase(codePoint) || Character.isTitleCase(codePoint);
      boolean previousLower = previous >= 0 && Character.isLowerCase(previous);
      boolean previousDigit = previous >= 0 && Character.isDigit(previous);
      boolean nextLower = next >= 0 && Character.isLowerCase(next);
      if (upper && (previousLower || previousDigit || previousUpper && nextLower)) {
        builder.append('_');
      }
      builder.appendCodePoint(Character.toLowerCase(codePoint));
      if (!Character.isLetterOrDigit(codePoint)) {
        previous = -1;
        previousUpper = false;
      } else {
        previous = codePoint;
        previousUpper = upper;
      }
      offset = nextOffset;
    }
    return builder.toString();
  }

  /** Owns one parameterized POJO binding whose child types differ from the raw-class binding. */
  private static final class ParameterizedObjectCodec<T> extends ObjectCodec<T> {
    private ParameterizedObjectCodec(
        Class<?> type,
        JsonFieldInfo[] writeFields,
        JsonFieldInfo[] readFields,
        String[] readJavaNames,
        JsonCreatorInfo creatorInfo,
        ObjectInstantiator<?> instantiator) {
      super(type, writeFields, readFields, readJavaNames, creatorInfo, instantiator);
    }

    @Override
    public void writeString(StringJsonWriter writer, T value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writeStringObject(writer, value);
      }
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, T value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writeUtf8Object(writer, value);
      }
    }

    @Override
    public T readLatin1(Latin1JsonReader reader) {
      return reader.tryReadNullToken() ? null : readLatin1Object(reader);
    }

    @Override
    public T readUtf16(Utf16JsonReader reader) {
      return reader.tryReadNullToken() ? null : readUtf16Object(reader);
    }

    @Override
    public T readUtf8(Utf8JsonReader reader) {
      return reader.tryReadNullToken() ? null : readUtf8Object(reader);
    }
  }
}
