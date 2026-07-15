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

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.meta.JsonAnySetterInvoker;
import org.apache.fory.json.meta.JsonCodecFactory;
import org.apache.fory.json.meta.JsonCreatorInvoker;
import org.apache.fory.json.meta.JsonDecodedMetadata;
import org.apache.fory.json.meta.JsonFieldAccessor;
import org.apache.fory.json.meta.JsonMetadataFormat;
import org.apache.fory.json.meta.JsonTypeMetadata;
import org.apache.fory.json.meta.JsonTypeNode;
import org.apache.fory.json.meta.JsonTypeUse;
import org.apache.fory.json.resolver.JsonTypeMetadataRegistry;
import org.apache.fory.json.resolver.JsonTypeResolver;
import org.apache.fory.reflect.FieldAccessor;
import org.apache.fory.reflect.ObjectInstantiator;
import org.apache.fory.reflect.ObjectInstantiators;
import org.apache.fory.reflect.TypeRef;

/** Resolves selected generated object facts on the codec-construction cold path. */
final class GeneratedObjectAccess {
  private static final JsonTypeUse[] NO_USES = new JsonTypeUse[0];
  private static final Type[] NO_TYPES = new Type[0];

  private final Class<?> owner;
  private final JsonTypeResolver resolver;
  private final JsonTypeMetadataRegistry registry;
  private final JsonDecodedMetadata metadata;
  private final Map<Integer, ResolvedType> bindings = new HashMap<>();
  private final ResolvedType[] resolvedNodes;
  private final boolean[] resolvingNodes;

  GeneratedObjectAccess(TypeRef<?> ownerType, JsonTypeUse ownerTypeUse, JsonTypeResolver resolver) {
    this.owner = ownerType.getRawType();
    this.resolver = resolver;
    registry = resolver.metadataRegistry();
    if (registry == null) {
      throw new ForyJsonException("Generated JSON object metadata is unavailable on this runtime");
    }
    metadata = registry.decodedSection(owner, JsonTypeMetadata.OBJECT);
    resolvedNodes = new ResolvedType[metadata.typeNodeCount()];
    resolvingNodes = new boolean[metadata.typeNodeCount()];
    bindOwner(ownerType.getType(), ownerTypeUse);
    bindHierarchy(owner, new HashSet<Class<?>>());
  }

  JsonDecodedMetadata metadata() {
    return metadata;
  }

  ResolvedType resolveType(int node, String path) {
    ResolvedType resolved = resolvedNodes[node];
    if (resolved != null) {
      return resolved;
    }
    if (resolvingNodes[node]) {
      JsonTypeNode recursive = metadata.typeNode(node);
      if (recursive.kind() != JsonMetadataFormat.TYPE_VARIABLE) {
        throw new ForyJsonException("Recursive generated JSON type node at " + path);
      }
      return unresolvedVariable(recursive.variableKey(), null, null, "", path);
    }
    resolvingNodes[node] = true;
    try {
      resolved = resolveNode(metadata.typeNode(node), path);
      resolvedNodes[node] = resolved;
      return resolved;
    } finally {
      resolvingNodes[node] = false;
    }
  }

  JsonFieldAccessor fieldAccessor(int operation, int accessMask) {
    int direction = 0;
    if ((accessMask & FieldAccessor.READ_ACCESS) != 0) {
      direction |= JsonMetadataFormat.WRITE;
    }
    if ((accessMask & FieldAccessor.WRITE_ACCESS) != 0) {
      direction |= JsonMetadataFormat.READ;
    }
    JsonDecodedMetadata.Operation recipe =
        operation(operation, JsonMetadataFormat.FIELD_ACCESS, direction);
    if (recipe.mode() == JsonMetadataFormat.OP_DIRECT) {
      return registry.directOperation(
          owner,
          JsonTypeMetadata.OBJECT,
          metadata,
          operation,
          JsonMetadataFormat.FIELD_ACCESS,
          JsonFieldAccessor.class);
    }
    Field field = declaredField(recipe);
    return JsonFieldAccessor.forField(field, accessMask);
  }

  JsonFieldAccessor getter(int operation) {
    JsonDecodedMetadata.Operation recipe =
        operation(operation, JsonMetadataFormat.GETTER, JsonMetadataFormat.WRITE);
    if (recipe.mode() == JsonMetadataFormat.OP_DIRECT) {
      return registry.directOperation(
          owner,
          JsonTypeMetadata.OBJECT,
          metadata,
          operation,
          JsonMetadataFormat.GETTER,
          JsonFieldAccessor.class);
    }
    return JsonFieldAccessor.forGetter(declaredMethod(recipe));
  }

  JsonFieldAccessor setter(int operation) {
    JsonDecodedMetadata.Operation recipe =
        operation(operation, JsonMetadataFormat.SETTER, JsonMetadataFormat.READ);
    if (recipe.mode() == JsonMetadataFormat.OP_DIRECT) {
      return registry.directOperation(
          owner,
          JsonTypeMetadata.OBJECT,
          metadata,
          operation,
          JsonMetadataFormat.SETTER,
          JsonFieldAccessor.class);
    }
    return JsonFieldAccessor.forSetter(declaredMethod(recipe));
  }

  JsonAnySetterInvoker anySetter(int operation) {
    JsonDecodedMetadata.Operation recipe =
        operation(operation, JsonMetadataFormat.ANY_SETTER, JsonMetadataFormat.READ);
    if (recipe.mode() == JsonMetadataFormat.OP_DIRECT) {
      return registry.directOperation(
          owner,
          JsonTypeMetadata.OBJECT,
          metadata,
          operation,
          JsonMetadataFormat.ANY_SETTER,
          JsonAnySetterInvoker.class);
    }
    return JsonAnySetterInvoker.forMethod(declaredMethod(recipe));
  }

  JsonCreatorInvoker creator(int operation) {
    JsonDecodedMetadata.Operation recipe =
        operation(operation, JsonMetadataFormat.CREATOR_CALL, JsonMetadataFormat.READ);
    if (recipe.mode() == JsonMetadataFormat.OP_DIRECT) {
      return registry.directOperation(
          owner,
          JsonTypeMetadata.OBJECT,
          metadata,
          operation,
          JsonMetadataFormat.CREATOR_CALL,
          JsonCreatorInvoker.class);
    }
    return JsonCreatorInvoker.forExecutable(owner, declaredExecutable(recipe));
  }

  ObjectInstantiator<?> instantiator(JsonDecodedMetadata.Instantiator fact) {
    int shape =
        fact.kind() == JsonMetadataFormat.INSTANTIATOR_RECORD
            ? JsonMetadataFormat.RECORD_CONSTRUCTOR
            : JsonMetadataFormat.NO_ARG_CONSTRUCTOR;
    JsonDecodedMetadata.Operation recipe =
        operation(fact.operation(), shape, JsonMetadataFormat.READ);
    if (recipe.mode() == JsonMetadataFormat.OP_DIRECT) {
      return registry.directOperation(
          owner,
          JsonTypeMetadata.OBJECT,
          metadata,
          fact.operation(),
          shape,
          ObjectInstantiator.class);
    }
    Executable executable = declaredExecutable(recipe);
    if (!(executable instanceof Constructor)) {
      throw invalidOperation(recipe, "constructor operation resolved to a method");
    }
    return fact.kind() == JsonMetadataFormat.INSTANTIATOR_RECORD
        ? ObjectInstantiators.createAndroidRecordInstantiator(owner, (Constructor<?>) executable)
        : ObjectInstantiators.createAndroidNoArgInstantiator(owner, (Constructor<?>) executable);
  }

  private ResolvedType resolveNode(JsonTypeNode node, String path) {
    if (node.kind() == JsonMetadataFormat.TYPE_VARIABLE) {
      ResolvedType binding = bindings.get(node.variableKey());
      CodecRef codec = codec(node);
      if (binding != null) {
        JsonTypeUse use =
            JsonTypeUse.bindGenerated(
                binding.type, codec.type, codec.factory, node.codecSource(), path);
        return new ResolvedType(binding.javaType, binding.rawType, use);
      }
      return unresolvedVariable(
          node.variableKey(), codec.type, codec.factory, node.codecSource(), path);
    }
    CodecRef codec = codec(node);
    if (node.kind() == JsonMetadataFormat.TYPE_PRIMITIVE) {
      Class<?> type = primitive(node.token());
      return generated(type, codec, NO_USES, null, NO_USES, NO_USES, -1, path);
    }
    if (node.kind() == JsonMetadataFormat.TYPE_ARRAY) {
      ResolvedType component = resolveType(node.token(), path + ".component");
      Type arrayType = JsonTypeUse.generatedArrayType(component.javaType);
      return generated(arrayType, codec, NO_USES, component.type, NO_USES, NO_USES, -1, path);
    }
    if (node.kind() == JsonMetadataFormat.TYPE_WILDCARD) {
      ResolvedType[] upper = resolveChildren(node, false, path + ".upper");
      ResolvedType[] lower = resolveChildren(node, true, path + ".lower");
      Type wildcard = JsonTypeUse.generatedWildcardType(types(upper), types(lower));
      return generated(wildcard, codec, NO_USES, null, uses(upper), uses(lower), -1, path);
    }
    Class<?> rawType = valueType(node.token());
    ResolvedType ownerType =
        node.ownerNode() < 0 ? null : resolveType(node.ownerNode(), path + ".owner");
    ResolvedType[] arguments = resolveChildren(node, false, path + ".argument");
    Type type =
        arguments.length == 0
            ? rawType
            : JsonTypeUse.generatedParameterizedType(
                ownerType == null ? null : ownerType.javaType, rawType, types(arguments));
    return generated(type, codec, uses(arguments), null, NO_USES, NO_USES, -1, path);
  }

  private ResolvedType unresolvedVariable(
      int key,
      Class<? extends JsonValueCodec<?>> codecClass,
      JsonCodecFactory codecFactory,
      String codecSource,
      String path) {
    JsonDecodedMetadata.TypeParameter parameter = metadata.typeParameter(key);
    ResolvedType[] bounds = new ResolvedType[parameter.boundCount()];
    for (int i = 0; i < bounds.length; i++) {
      int bound = parameter.bound(i);
      bounds[i] =
          resolvingNodes[bound]
              ? ordinary(Object.class, null)
              : resolveType(bound, path + ".bound");
    }
    Type type = bounds.length == 0 ? Object.class : bounds[0].javaType;
    JsonTypeUse use =
        JsonTypeUse.generatedNode(
            type, codecClass, codecFactory, codecSource, NO_USES, null, uses(bounds), NO_USES, key);
    return new ResolvedType(type, rawType(type), use);
  }

  private ResolvedType generated(
      Type type,
      CodecRef codec,
      JsonTypeUse[] arguments,
      JsonTypeUse component,
      JsonTypeUse[] upper,
      JsonTypeUse[] lower,
      int variableKey,
      String path) {
    JsonTypeUse use =
        JsonTypeUse.generatedNode(
            type,
            codec.type,
            codec.factory,
            codec.source,
            arguments,
            component,
            upper,
            lower,
            variableKey);
    return new ResolvedType(type, rawType(type), use);
  }

  private CodecRef codec(JsonTypeNode node) {
    if (!node.hasCodec()) {
      return CodecRef.NONE;
    }
    Class<? extends JsonValueCodec<?>> codecClass =
        resolver.generatedCodecClass(owner, JsonTypeMetadata.OBJECT, metadata, node.codecToken());
    JsonCodecFactory factory =
        registry.directOperation(
            owner,
            JsonTypeMetadata.OBJECT,
            metadata,
            node.codecOperation(),
            JsonMetadataFormat.CODEC_FACTORY,
            JsonCodecFactory.class);
    return new CodecRef(codecClass, factory, node.codecSource());
  }

  private ResolvedType[] resolveChildren(JsonTypeNode node, boolean lower, String path) {
    int count = lower ? node.lowerBoundCount() : node.childCount();
    ResolvedType[] children = new ResolvedType[count];
    for (int i = 0; i < count; i++) {
      children[i] = resolveType(lower ? node.lowerBound(i) : node.child(i), path + '[' + i + ']');
    }
    return children;
  }

  private void bindOwner(Type ownerType, JsonTypeUse ownerUse) {
    ResolvedType root = ordinary(ownerType, ownerUse);
    bindArguments(owner, root);
  }

  private void bindHierarchy(Class<?> type, Set<Class<?>> visited) {
    if (!visited.add(type)) {
      return;
    }
    JsonDecodedMetadata.Hierarchy hierarchy = hierarchy(type);
    if (hierarchy == null) {
      return;
    }
    if (hierarchy.superclassNode() >= 0) {
      ResolvedType superclass =
          resolveStructure(
              hierarchy.superclassNode(), "hierarchy superclass", new HashSet<Integer>());
      Class<?> raw = superclass.rawType;
      if (raw != Object.class
          && !raw.getName().equals("java.lang.Record")
          // Android represents platform classes with BootClassLoader rather than a null loader.
          // Loader identity with Object is the portable boundary for platform-owned hierarchies.
          && raw.getClassLoader() == Object.class.getClassLoader()) {
        throw new ForyJsonException(
            "Generated JSON object mapping for "
                + owner.getName()
                + " crosses platform superclass "
                + raw.getName()
                + "; register a complete codec for the object type");
      }
      bindArguments(raw, superclass);
      bindHierarchy(raw, visited);
    }
    for (int i = 0; i < hierarchy.interfaceCount(); i++) {
      ResolvedType interfaceType =
          resolveStructure(
              hierarchy.interfaceNode(i), "hierarchy interface " + i, new HashSet<Integer>());
      bindArguments(interfaceType.rawType, interfaceType);
      bindHierarchy(interfaceType.rawType, visited);
    }
  }

  private JsonDecodedMetadata.Hierarchy hierarchy(Class<?> type) {
    for (int i = 0; i < metadata.hierarchyCount(); i++) {
      JsonDecodedMetadata.Hierarchy hierarchy = metadata.hierarchy(i);
      if (registry.memberType(owner, JsonTypeMetadata.OBJECT, metadata, hierarchy.declaringToken())
          == type) {
        return hierarchy;
      }
    }
    return null;
  }

  private void bindArguments(Class<?> rawType, ResolvedType resolved) {
    int argumentCount = resolved.type.argumentCount();
    for (int key = 0; key < metadata.typeParameterCount(); key++) {
      JsonDecodedMetadata.TypeParameter parameter = metadata.typeParameter(key);
      if (parameter.ownerKind() != JsonMetadataFormat.OWNER_TYPE
          || parameter.parameterIndex() >= argumentCount
          || registry.memberType(owner, JsonTypeMetadata.OBJECT, metadata, parameter.ownerToken())
              != rawType) {
        continue;
      }
      JsonTypeUse use = resolved.type.argument(parameter.parameterIndex());
      bindings.put(key, new ResolvedType(use.type(), use.rawType(), use));
    }
  }

  private ResolvedType resolveStructure(int index, String path, Set<Integer> visiting) {
    JsonTypeNode node = metadata.typeNode(index);
    if (!visiting.add(index)) {
      if (node.kind() == JsonMetadataFormat.TYPE_VARIABLE) {
        ResolvedType binding = bindings.get(node.variableKey());
        return binding == null ? ordinary(Object.class, null) : binding;
      }
      throw new ForyJsonException("Recursive generated hierarchy type at " + path);
    }
    try {
      if (node.kind() == JsonMetadataFormat.TYPE_VARIABLE) {
        ResolvedType binding = bindings.get(node.variableKey());
        if (binding != null) {
          return binding;
        }
        JsonDecodedMetadata.TypeParameter parameter = metadata.typeParameter(node.variableKey());
        return parameter.boundCount() == 0
            ? ordinary(Object.class, null)
            : resolveStructure(parameter.bound(0), path + ".bound", visiting);
      }
      if (node.kind() == JsonMetadataFormat.TYPE_PRIMITIVE) {
        return ordinary(primitive(node.token()), null);
      }
      if (node.kind() == JsonMetadataFormat.TYPE_ARRAY) {
        ResolvedType component = resolveStructure(node.token(), path + ".component", visiting);
        return ordinary(JsonTypeUse.generatedArrayType(component.javaType), null);
      }
      if (node.kind() == JsonMetadataFormat.TYPE_WILDCARD) {
        Type[] upper = new Type[node.childCount()];
        Type[] lower = new Type[node.lowerBoundCount()];
        for (int i = 0; i < upper.length; i++) {
          upper[i] = resolveStructure(node.child(i), path + ".upper", visiting).javaType;
        }
        for (int i = 0; i < lower.length; i++) {
          lower[i] = resolveStructure(node.lowerBound(i), path + ".lower", visiting).javaType;
        }
        return ordinary(JsonTypeUse.generatedWildcardType(upper, lower), null);
      }
      Class<?> rawType = memberType(node.token());
      Type ownerType =
          node.ownerNode() < 0
              ? null
              : resolveStructure(node.ownerNode(), path + ".owner", visiting).javaType;
      Type[] arguments = new Type[node.childCount()];
      for (int i = 0; i < arguments.length; i++) {
        arguments[i] = resolveStructure(node.child(i), path + ".argument", visiting).javaType;
      }
      Type type =
          arguments.length == 0
              ? rawType
              : JsonTypeUse.generatedParameterizedType(ownerType, rawType, arguments);
      return ordinary(type, null);
    } finally {
      visiting.remove(index);
    }
  }

  private ResolvedType ordinary(Type type, JsonTypeUse use) {
    if (use != null) {
      return new ResolvedType(type, use.rawType(), use);
    }
    JsonTypeUse[] arguments = NO_USES;
    JsonTypeUse component = null;
    JsonTypeUse[] upper = NO_USES;
    JsonTypeUse[] lower = NO_USES;
    if (type instanceof ParameterizedType) {
      Type[] values = ((ParameterizedType) type).getActualTypeArguments();
      arguments = new JsonTypeUse[values.length];
      for (int i = 0; i < values.length; i++) {
        arguments[i] = ordinary(values[i], null).type;
      }
    } else if (type instanceof GenericArrayType) {
      component = ordinary(((GenericArrayType) type).getGenericComponentType(), null).type;
    } else if (type instanceof Class && ((Class<?>) type).isArray()) {
      component = ordinary(((Class<?>) type).getComponentType(), null).type;
    } else if (type instanceof WildcardType) {
      Type[] upperTypes = ((WildcardType) type).getUpperBounds();
      Type[] lowerTypes = ((WildcardType) type).getLowerBounds();
      upper = ordinaryUses(upperTypes);
      lower = ordinaryUses(lowerTypes);
    }
    JsonTypeUse node =
        JsonTypeUse.generatedNode(type, null, null, "", arguments, component, upper, lower, -1);
    return new ResolvedType(type, rawType(type), node);
  }

  private JsonTypeUse[] ordinaryUses(Type[] types) {
    JsonTypeUse[] uses = new JsonTypeUse[types.length];
    for (int i = 0; i < types.length; i++) {
      uses[i] = ordinary(types[i], null).type;
    }
    return uses;
  }

  private Class<?> valueType(int token) {
    return resolver.generatedValueType(owner, JsonTypeMetadata.OBJECT, metadata, token);
  }

  private JsonDecodedMetadata.Operation operation(int index, int shape, int direction) {
    JsonDecodedMetadata.Operation operation =
        registry.operation(owner, JsonTypeMetadata.OBJECT, metadata, index, shape);
    if ((operation.directionMask() & direction) != direction) {
      throw invalidOperation(operation, "operation does not support selected direction");
    }
    return operation;
  }

  private Field declaredField(JsonDecodedMetadata.Operation operation) {
    Class<?> declaring = memberType(operation.ownerToken());
    try {
      Field field = declaring.getDeclaredField(operation.memberName());
      if (!descriptor(field.getType()).equals(operation.descriptor())) {
        throw invalidOperation(operation, "field descriptor does not match generated metadata");
      }
      return field;
    } catch (NoSuchFieldException | SecurityException e) {
      throw invalidOperation(operation, "field cannot be resolved", e);
    }
  }

  private Method declaredMethod(JsonDecodedMetadata.Operation operation) {
    Class<?> declaring = memberType(operation.ownerToken());
    for (Method method : declaring.getDeclaredMethods()) {
      if (method.getName().equals(operation.memberName())
          && methodDescriptor(method).equals(operation.descriptor())) {
        return method;
      }
    }
    throw invalidOperation(operation, "method cannot be resolved");
  }

  private Executable declaredExecutable(JsonDecodedMetadata.Operation operation) {
    if (operation.memberName().equals("<init>")) {
      Class<?> declaring = memberType(operation.ownerToken());
      for (Constructor<?> constructor : declaring.getDeclaredConstructors()) {
        if (constructorDescriptor(constructor).equals(operation.descriptor())) {
          return constructor;
        }
      }
      throw invalidOperation(operation, "constructor cannot be resolved");
    }
    return declaredMethod(operation);
  }

  private Class<?> memberType(int token) {
    return registry.memberType(owner, JsonTypeMetadata.OBJECT, metadata, token);
  }

  private static Class<?> primitive(int kind) {
    switch (kind) {
      case JsonMetadataFormat.BOOLEAN:
        return boolean.class;
      case JsonMetadataFormat.BYTE:
        return byte.class;
      case JsonMetadataFormat.SHORT:
        return short.class;
      case JsonMetadataFormat.INT:
        return int.class;
      case JsonMetadataFormat.LONG:
        return long.class;
      case JsonMetadataFormat.FLOAT:
        return float.class;
      case JsonMetadataFormat.DOUBLE:
        return double.class;
      case JsonMetadataFormat.CHAR:
        return char.class;
      case JsonMetadataFormat.VOID:
        return void.class;
      default:
        throw new ForyJsonException("Invalid generated primitive kind " + kind);
    }
  }

  private static Class<?> rawType(Type type) {
    if (type instanceof Class) {
      return (Class<?>) type;
    }
    if (type instanceof ParameterizedType) {
      return (Class<?>) ((ParameterizedType) type).getRawType();
    }
    if (type instanceof GenericArrayType) {
      return (Class<?>)
          JsonTypeUse.generatedArrayType(
              rawType(((GenericArrayType) type).getGenericComponentType()));
    }
    if (type instanceof TypeVariable) {
      return rawType(((TypeVariable<?>) type).getBounds()[0]);
    }
    if (type instanceof WildcardType) {
      return rawType(((WildcardType) type).getUpperBounds()[0]);
    }
    return Object.class;
  }

  private static Type[] types(ResolvedType[] values) {
    if (values.length == 0) {
      return NO_TYPES;
    }
    Type[] types = new Type[values.length];
    for (int i = 0; i < values.length; i++) {
      types[i] = values[i].javaType;
    }
    return types;
  }

  private static JsonTypeUse[] uses(ResolvedType[] values) {
    if (values.length == 0) {
      return NO_USES;
    }
    JsonTypeUse[] uses = new JsonTypeUse[values.length];
    for (int i = 0; i < values.length; i++) {
      uses[i] = values[i].type;
    }
    return uses;
  }

  private static String methodDescriptor(Method method) {
    return executableDescriptor(method.getParameterTypes(), method.getReturnType());
  }

  private static String constructorDescriptor(Constructor<?> constructor) {
    return executableDescriptor(constructor.getParameterTypes(), void.class);
  }

  private static String executableDescriptor(Class<?>[] parameters, Class<?> returnType) {
    StringBuilder builder = new StringBuilder("(");
    for (Class<?> parameter : parameters) {
      builder.append(descriptor(parameter));
    }
    return builder.append(')').append(descriptor(returnType)).toString();
  }

  private static String descriptor(Class<?> type) {
    if (type.isArray()) {
      return type.getName().replace('.', '/');
    }
    if (!type.isPrimitive()) {
      return 'L' + type.getName().replace('.', '/') + ';';
    }
    if (type == void.class) {
      return "V";
    }
    if (type == boolean.class) {
      return "Z";
    }
    if (type == byte.class) {
      return "B";
    }
    if (type == char.class) {
      return "C";
    }
    if (type == short.class) {
      return "S";
    }
    if (type == int.class) {
      return "I";
    }
    if (type == long.class) {
      return "J";
    }
    if (type == float.class) {
      return "F";
    }
    return "D";
  }

  private ForyJsonException invalidOperation(
      JsonDecodedMetadata.Operation operation, String reason) {
    return invalidOperation(operation, reason, null);
  }

  private ForyJsonException invalidOperation(
      JsonDecodedMetadata.Operation operation, String reason, Throwable cause) {
    String message =
        "Invalid generated JSON operation "
            + operation.memberName()
            + operation.descriptor()
            + " for "
            + owner.getName()
            + ": "
            + reason;
    return cause == null ? new ForyJsonException(message) : new ForyJsonException(message, cause);
  }

  static final class ResolvedType {
    final Type javaType;
    final Class<?> rawType;
    final JsonTypeUse type;

    private ResolvedType(Type javaType, Class<?> rawType, JsonTypeUse type) {
      this.javaType = javaType;
      this.rawType = rawType;
      this.type = type;
    }
  }

  private static final class CodecRef {
    private static final CodecRef NONE = new CodecRef(null, null, "");

    private final Class<? extends JsonValueCodec<?>> type;
    private final JsonCodecFactory factory;
    private final String source;

    private CodecRef(
        Class<? extends JsonValueCodec<?>> type, JsonCodecFactory factory, String source) {
      this.type = type;
      this.factory = factory;
      this.source = source;
    }
  }
}
