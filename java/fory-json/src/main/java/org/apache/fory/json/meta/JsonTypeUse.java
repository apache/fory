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

package org.apache.fory.json.meta;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.fory.annotation.Internal;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.annotation.JsonCodec;
import org.apache.fory.json.codec.JsonValueCodec;
import org.apache.fory.platform.AndroidSupport;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.reflect.TypeUseMetadata;
import org.apache.fory.type.TypeUtils;
import org.apache.fory.util.record.RecordComponent;

/**
 * Immutable cold metadata for source-explicit {@link JsonCodec} annotations on one Java type use.
 *
 * <p>Raw-type declaration annotations are intentionally absent. They remain lazy defaults of a raw
 * target class and are resolved only when a value owner delegates to that node. Field and method
 * declarations are explicit annotations on the owned occurrence root. Instances are retained only
 * when the complete occurrence tree contains an explicit codec annotation.
 */
@Internal
public final class JsonTypeUse {
  private static final JsonTypeUse[] NO_CHILDREN = new JsonTypeUse[0];
  private static final String[] NO_SOURCES = new String[0];

  /** Generated-path step selecting the component of an array type. */
  public static final int ARRAY_COMPONENT = -1;

  /** Generated-path step identifying the upper bound of a wildcard. */
  public static final int WILDCARD_UPPER_BOUND = -2;

  /** Generated-path step identifying the lower bound of a wildcard. */
  public static final int WILDCARD_LOWER_BOUND = -3;

  private final Type type;
  private final Class<?> rawType;
  private final Class<? extends JsonValueCodec<?>> codecClass;
  private final String[] codecSources;
  private final JsonTypeUse[] arguments;
  private final JsonTypeUse arrayComponent;
  private final JsonTypeUse[] upperBounds;
  private final JsonTypeUse[] lowerBounds;
  private final boolean hasExplicitCodec;
  private final int hashCode;

  private JsonTypeUse(
      Type type,
      Class<? extends JsonValueCodec<?>> codecClass,
      String[] codecSources,
      JsonTypeUse[] arguments,
      JsonTypeUse arrayComponent,
      JsonTypeUse[] upperBounds,
      JsonTypeUse[] lowerBounds) {
    this.type = type;
    rawType = TypeUtils.getRawType(type);
    this.codecClass = codecClass;
    this.codecSources = emptyIfNull(codecSources);
    this.arguments = emptyIfNull(arguments);
    this.arrayComponent = arrayComponent;
    this.upperBounds = emptyIfNull(upperBounds);
    this.lowerBounds = emptyIfNull(lowerBounds);
    hasExplicitCodec =
        codecClass != null
            || containsExplicit(this.arguments)
            || (arrayComponent != null && arrayComponent.hasExplicitCodec)
            || containsExplicit(this.upperBounds)
            || containsExplicit(this.lowerBounds);
    hashCode = structuralHashCode();
  }

  /** Returns the explicit type-use tree for {@code field}, or {@code null} when none is present. */
  public static JsonTypeUse forField(Field field) {
    String source = "field " + field.getDeclaringClass().getName() + "." + field.getName();
    JsonCodec declaration = memberAnnotation(field, source);
    if (AndroidSupport.IS_ANDROID) {
      // Android API 26 exposes declaration annotations but none of the Java 8 AnnotatedType
      // methods on Field, Method, or Executable. Pure type-use facts come from the generated map.
      return rootTypeUse(field.getGenericType(), declaration, source);
    }
    Type type = field.getGenericType();
    if (declaration != null && !hasNestedTypeShape(type)) {
      return rootTypeUse(type, declaration, source);
    }
    return declarationFirst(type, declaration, fieldTypeUse(field, source), source);
  }

  /** Returns the explicit return type-use tree for {@code method}, or {@code null}. */
  public static JsonTypeUse forMethodReturn(Method method) {
    String source =
        "method return " + method.getDeclaringClass().getName() + "#" + method.getName();
    JsonCodec declaration = memberAnnotation(method, source);
    if (AndroidSupport.IS_ANDROID) {
      return rootTypeUse(method.getGenericReturnType(), declaration, source);
    }
    Type type = method.getGenericReturnType();
    if (declaration != null && !hasNestedTypeShape(type)) {
      return rootTypeUse(type, declaration, source);
    }
    return declarationFirst(type, declaration, methodReturnTypeUse(method, source), source);
  }

  /** Returns one explicit method-parameter type-use tree, or {@code null}. */
  public static JsonTypeUse forMethodParameter(Method method, int parameterIndex) {
    String source =
        "method parameter "
            + method.getDeclaringClass().getName()
            + "#"
            + method.getName()
            + "["
            + parameterIndex
            + "]";
    if (AndroidSupport.IS_ANDROID) {
      checkParameterIndex(null, method.getParameterCount(), parameterIndex, method.toString());
      return null;
    }
    Object[] parameters = TypeUseMetadata.methodParameterTypeUses(method);
    checkParameterIndex(parameters, method.getParameterCount(), parameterIndex, method.toString());
    return fromTypeUse(parameters == null ? null : parameters[parameterIndex], source);
  }

  /** Returns one explicit constructor-parameter type-use tree, or {@code null}. */
  public static JsonTypeUse forConstructorParameter(
      Constructor<?> constructor, int parameterIndex) {
    String source =
        "constructor parameter "
            + constructor.getDeclaringClass().getName()
            + "["
            + parameterIndex
            + "]";
    if (AndroidSupport.IS_ANDROID) {
      checkParameterIndex(
          null, constructor.getParameterCount(), parameterIndex, constructor.toString());
      return null;
    }
    Object[] parameters = TypeUseMetadata.constructorParameterTypeUses(constructor);
    checkParameterIndex(
        parameters, constructor.getParameterCount(), parameterIndex, constructor.toString());
    return fromTypeUse(parameters == null ? null : parameters[parameterIndex], source);
  }

  /** Returns the explicit type-use tree for {@code component}, or {@code null}. */
  public static JsonTypeUse forRecordComponent(RecordComponent component) {
    if (AndroidSupport.IS_ANDROID) {
      return null;
    }
    return fromTypeUse(
        TypeUseMetadata.recordComponentTypeUse(component),
        "record component " + component.getDeclaringRecord().getName() + "." + component.getName());
  }

  /**
   * Builds an explicit tree from a platform-neutral type-use handle.
   *
   * <p>The returned value is {@code null} when recursive type-use metadata is unavailable or no
   * codec occurs at any node. {@code source} identifies the field, accessor, record component, or
   * creator parameter in diagnostics.
   */
  public static JsonTypeUse fromTypeUse(Object typeUse, String source) {
    if (AndroidSupport.IS_ANDROID || typeUse == null) {
      return null;
    }
    if (!scanExplicit(typeUse, source, "$")) {
      return null;
    }
    return buildTypeUse(typeUse, source, "$");
  }

  /**
   * Builds one generated codec tree from an ordinary retained reflection type.
   *
   * <p>An empty path selects the root. Each path step is otherwise a non-negative generic-argument
   * index, {@link #ARRAY_COMPONENT}, {@link #WILDCARD_UPPER_BOUND}, or {@link
   * #WILDCARD_LOWER_BOUND}. Wildcard steps reproduce the existing rejection for codecs on wildcards
   * and their bounds rather than silently dropping invalid source annotations on platforms without
   * recursive type-use reflection.
   */
  public static JsonTypeUse generated(
      Type rootType, Class<? extends JsonValueCodec<?>> codecClass, String source, int... path) {
    Objects.requireNonNull(rootType, "rootType");
    Objects.requireNonNull(codecClass, "codecClass");
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(path, "path");
    return applyGenerated(plainType(rootType), codecClass, source, path, 0, "$");
  }

  /**
   * Resolves one declared member type and its explicit tree against its parameterized owner.
   *
   * <p>{@code declaredTypeUse} may be {@code null}. A temporary unannotated skeleton is then used
   * so an annotation supplied by an owner binding, such as {@code Envelope<@JsonCodec Money>}, is
   * still substituted into an unannotated member declared as {@code T}. The result is {@code null}
   * only when no explicit annotation survives substitution.
   */
  public static JsonTypeUse resolveMember(
      TypeRef<?> ownerType,
      JsonTypeUse annotatedOwner,
      Type declaredType,
      JsonTypeUse declaredTypeUse,
      String path) {
    JsonTypeUse declared =
        declaredTypeUse == null
            ? plainType(declaredType)
            : requireType(declaredTypeUse, declaredType);
    JsonTypeUse owner =
        annotatedOwner == null
            ? plainType(ownerType.getType())
            : requireOwner(annotatedOwner, ownerType);
    Map<TypeVariable<?>, JsonTypeUse> bindings = buildBindings(owner, path + ".owner");
    JsonTypeUse resolved = resolveNode(declared, ownerType, bindings, path);
    return resolved.hasExplicitCodec ? resolved : null;
  }

  /** Merges two already-resolved logical-property trees using exact-node codec semantics. */
  public static JsonTypeUse merge(JsonTypeUse left, JsonTypeUse right, String path) {
    if (left == null) {
      return right;
    }
    if (right == null) {
      return left;
    }
    requireSameShape(left, right, path);
    Class<? extends JsonValueCodec<?>> mergedCodec = left.codecClass;
    String[] mergedSources = left.codecSources;
    if (mergedCodec == null) {
      mergedCodec = right.codecClass;
      mergedSources = right.codecSources;
    } else if (right.codecClass != null) {
      if (mergedCodec != right.codecClass) {
        throw codecConflict(path, left, right);
      }
      mergedSources = mergeSources(left.codecSources, right.codecSources);
    }
    JsonTypeUse[] mergedArguments =
        mergeChildren(left.arguments, right.arguments, path, ".argument[");
    JsonTypeUse mergedComponent =
        merge(left.arrayComponent, right.arrayComponent, path + ".component");
    JsonTypeUse[] mergedUpper =
        mergeChildren(left.upperBounds, right.upperBounds, path, ".upperBound[");
    JsonTypeUse[] mergedLower =
        mergeChildren(left.lowerBounds, right.lowerBounds, path, ".lowerBound[");
    if (mergedCodec == left.codecClass
        && mergedSources == left.codecSources
        && mergedArguments == left.arguments
        && mergedComponent == left.arrayComponent
        && mergedUpper == left.upperBounds
        && mergedLower == left.lowerBounds) {
      return left;
    }
    return new JsonTypeUse(
        left.type,
        mergedCodec,
        mergedSources,
        mergedArguments,
        mergedComponent,
        mergedUpper,
        mergedLower);
  }

  /**
   * Projects this occurrence through its generic hierarchy to {@code targetRawType}.
   *
   * <p>Annotations on the concrete owner's arguments follow the matching type variables even when a
   * collection or map subclass reorders its parameters. An explicit descendant that does not map to
   * the target supertype is rejected because no target child can own it. The current root codec is
   * not copied: it selects the concrete occurrence, not its supertype's value node.
   */
  public JsonTypeUse projectTo(Class<?> targetRawType, String path) {
    if (!targetRawType.isAssignableFrom(rawType)) {
      throw new ForyJsonException(
          rawType.getTypeName() + " is not assignable to " + targetRawType.getTypeName());
    }
    Map<TypeVariable<?>, JsonTypeUse> bindings = buildBindings(this, path + ".owner");
    TypeRef<?> projectedRef = supertype(TypeRef.of(type), targetRawType);
    TypeVariable<?>[] parameters = targetRawType.getTypeParameters();
    JsonTypeUse[] projectedArguments = new JsonTypeUse[parameters.length];
    Type[] ordinaryArguments = typeArguments(projectedRef.getType());
    for (int i = 0; i < parameters.length; i++) {
      JsonTypeUse binding = bindings.get(parameters[i]);
      if (binding == null) {
        Type ordinary = i < ordinaryArguments.length ? ordinaryArguments[i] : parameters[i];
        binding = plainType(ordinary);
      }
      projectedArguments[i] = binding;
    }
    JsonTypeUse projected =
        new JsonTypeUse(
            projectedRef.getType(),
            null,
            NO_SOURCES,
            projectedArguments,
            null,
            NO_CHILDREN,
            NO_CHILDREN);
    rejectUnprojectedDescendants(projected, path, targetRawType);
    return projected;
  }

  /** Rejects an explicit codec anywhere in this subtree for a non-value position. */
  public void rejectExplicit(String position) {
    JsonTypeUse explicit = firstExplicit();
    if (explicit != null) {
      throw new ForyJsonException(
          "@JsonCodec from "
              + explicit.codecSource()
              + " cannot be used in "
              + position
              + " ("
              + explicit.type.getTypeName()
              + ")");
    }
  }

  /** Rejects an explicit codec in a map-key subtree. */
  public void rejectMapKey(String path) {
    rejectExplicit("map-key subtree " + path + "; map keys are owned by MapKeyCodec");
  }

  /** Rejects a source-explicit child hidden by a complete codec at this node. */
  public void rejectExplicitDescendants(String owner) {
    JsonTypeUse descendant = firstExplicitDescendant();
    if (descendant != null) {
      throw new ForyJsonException(
          "Complete JSON codec for "
              + owner
              + " hides descendant @JsonCodec from "
              + descendant.codecSource());
    }
  }

  public Type type() {
    return type;
  }

  public Class<?> rawType() {
    return rawType;
  }

  /** Returns the codec selected at this exact node, or {@code null}. */
  public Class<? extends JsonValueCodec<?>> codecClass() {
    return codecClass;
  }

  /** Returns one deterministic source for the codec at this node, or {@code null}. */
  public String codecSource() {
    return codecSources.length == 0 ? null : codecSources[0];
  }

  /** Returns all logical-property sources merged at this exact node. */
  public String[] codecSources() {
    return codecSources.clone();
  }

  /** Returns whether this exact occurrence node has a source-explicit codec. */
  public boolean hasCodec() {
    return codecClass != null;
  }

  /** Returns whether this node or any structurally nested node has an explicit codec. */
  public boolean hasExplicitCodec() {
    return hasExplicitCodec;
  }

  /** Returns whether a child below this node has an explicit codec. */
  public boolean hasExplicitDescendant() {
    return firstExplicitDescendant() != null;
  }

  /** Returns the first explicit child below this node in structural order, or {@code null}. */
  public JsonTypeUse firstExplicitDescendant() {
    JsonTypeUse explicit = firstExplicit(arguments);
    if (explicit != null) {
      return explicit;
    }
    if (arrayComponent != null) {
      explicit = arrayComponent.firstExplicit();
      if (explicit != null) {
        return explicit;
      }
    }
    explicit = firstExplicit(upperBounds);
    return explicit == null ? firstExplicit(lowerBounds) : explicit;
  }

  public int argumentCount() {
    return arguments.length;
  }

  public JsonTypeUse argument(int index) {
    return arguments[index];
  }

  public JsonTypeUse arrayComponent() {
    return arrayComponent;
  }

  public int upperBoundCount() {
    return upperBounds.length;
  }

  public JsonTypeUse upperBound(int index) {
    return upperBounds[index];
  }

  public int lowerBoundCount() {
    return lowerBounds.length;
  }

  public JsonTypeUse lowerBound(int index) {
    return lowerBounds[index];
  }

  @Override
  public boolean equals(Object object) {
    if (object == this) {
      return true;
    }
    if (!(object instanceof JsonTypeUse)) {
      return false;
    }
    JsonTypeUse other = (JsonTypeUse) object;
    return type.equals(other.type)
        && codecClass == other.codecClass
        && Arrays.equals(arguments, other.arguments)
        && Objects.equals(arrayComponent, other.arrayComponent)
        && Arrays.equals(upperBounds, other.upperBounds)
        && Arrays.equals(lowerBounds, other.lowerBounds);
  }

  @Override
  public int hashCode() {
    return hashCode;
  }

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder(type.getTypeName());
    if (codecClass != null) {
      builder.append(" @JsonCodec(").append(codecClass.getName()).append(')');
    }
    return builder.toString();
  }

  private static JsonTypeUse requireType(JsonTypeUse typeUse, Type expectedType) {
    if (!typeUse.type.equals(expectedType)) {
      throw new ForyJsonException(
          "Type-use metadata "
              + typeUse.type.getTypeName()
              + " does not match declared type "
              + expectedType.getTypeName());
    }
    return typeUse;
  }

  private static JsonTypeUse requireOwner(JsonTypeUse owner, TypeRef<?> ownerType) {
    Type expected = ownerType.getType();
    if (!owner.type.equals(expected)) {
      throw new ForyJsonException(
          "Annotated owner "
              + owner.type.getTypeName()
              + " does not match resolved owner "
              + expected.getTypeName());
    }
    return owner;
  }

  private static JsonCodec memberAnnotation(AnnotatedElement member, String source) {
    try {
      return member.getAnnotation(JsonCodec.class);
    } catch (RuntimeException | LinkageError e) {
      throw new ForyJsonException("Cannot read @JsonCodec from " + source, e);
    }
  }

  private static Object fieldTypeUse(Field field, String source) {
    try {
      return TypeUseMetadata.fieldTypeUse(field);
    } catch (RuntimeException | LinkageError e) {
      throw new ForyJsonException("Cannot read type-use @JsonCodec from " + source, e);
    }
  }

  private static Object methodReturnTypeUse(Method method, String source) {
    try {
      return TypeUseMetadata.methodReturnTypeUse(method);
    } catch (RuntimeException | LinkageError e) {
      throw new ForyJsonException("Cannot read type-use @JsonCodec from " + source, e);
    }
  }

  private static boolean hasNestedTypeShape(Type type) {
    return type instanceof ParameterizedType
        || type instanceof GenericArrayType
        || type instanceof Class<?> && ((Class<?>) type).isArray();
  }

  private static JsonTypeUse declarationFirst(
      Type type, JsonCodec declaration, Object typeUse, String source) {
    JsonTypeUse reflected = fromTypeUse(typeUse, source);
    return mergeDeclaration(type, declaration, reflected, source);
  }

  static JsonTypeUse mergeDeclaration(
      Type type, JsonCodec declaration, JsonTypeUse reflected, String source) {
    if (declaration == null) {
      return reflected;
    }
    JsonTypeUse root = rootTypeUse(type, declaration, source);
    if (reflected == null || !reflected.hasExplicitDescendant()) {
      return root;
    }
    JsonTypeUse descendants =
        new JsonTypeUse(
            reflected.type,
            null,
            NO_SOURCES,
            reflected.arguments,
            reflected.arrayComponent,
            reflected.upperBounds,
            reflected.lowerBounds);
    return merge(root, descendants, source);
  }

  private static JsonTypeUse rootTypeUse(Type type, JsonCodec annotation, String source) {
    if (annotation == null) {
      return null;
    }
    JsonTypeUse plain = plainType(type);
    return new JsonTypeUse(
        type,
        readCodecClass(annotation, source, "$"),
        new String[] {source + " at $"},
        plain.arguments,
        plain.arrayComponent,
        plain.upperBounds,
        plain.lowerBounds);
  }

  private static JsonTypeUse applyGenerated(
      JsonTypeUse node,
      Class<? extends JsonValueCodec<?>> codecClass,
      String source,
      int[] path,
      int pathIndex,
      String canonicalPath) {
    if (pathIndex == path.length) {
      if (node.type instanceof WildcardType) {
        throw new ForyJsonException(
            "@JsonCodec cannot target a wildcard at " + source + " " + canonicalPath);
      }
      return new JsonTypeUse(
          node.type,
          codecClass,
          new String[] {source + " at " + canonicalPath},
          node.arguments,
          node.arrayComponent,
          node.upperBounds,
          node.lowerBounds);
    }
    int step = path[pathIndex];
    if (step == WILDCARD_UPPER_BOUND || step == WILDCARD_LOWER_BOUND) {
      String bound = step == WILDCARD_UPPER_BOUND ? ".upperBound[0]" : ".lowerBound[0]";
      JsonTypeUse[] bounds = step == WILDCARD_UPPER_BOUND ? node.upperBounds : node.lowerBounds;
      if (!(node.type instanceof WildcardType) || bounds.length == 0) {
        throw invalidGeneratedPath(source, canonicalPath + bound, node.type);
      }
      throw new ForyJsonException(
          "@JsonCodec cannot target a wildcard or wildcard bound at "
              + source
              + " "
              + canonicalPath
              + bound);
    }
    if (step == ARRAY_COMPONENT) {
      if (node.arrayComponent == null) {
        throw invalidGeneratedPath(source, canonicalPath + ".component", node.type);
      }
      JsonTypeUse component =
          applyGenerated(
              node.arrayComponent,
              codecClass,
              source,
              path,
              pathIndex + 1,
              canonicalPath + ".component");
      return new JsonTypeUse(
          node.type,
          node.codecClass,
          node.codecSources,
          node.arguments,
          component,
          node.upperBounds,
          node.lowerBounds);
    }
    if (step < 0 || step >= node.arguments.length) {
      throw invalidGeneratedPath(source, canonicalPath + ".argument[" + step + "]", node.type);
    }
    JsonTypeUse[] arguments = node.arguments.clone();
    String childPath = canonicalPath + ".argument[" + step + "]";
    arguments[step] =
        applyGenerated(arguments[step], codecClass, source, path, pathIndex + 1, childPath);
    return new JsonTypeUse(
        node.type,
        node.codecClass,
        node.codecSources,
        arguments,
        node.arrayComponent,
        node.upperBounds,
        node.lowerBounds);
  }

  private static ForyJsonException invalidGeneratedPath(String source, String path, Type type) {
    return new ForyJsonException(
        "Generated @JsonCodec path "
            + path
            + " from "
            + source
            + " does not exist in "
            + type.getTypeName());
  }

  private static boolean scanExplicit(Object typeUse, String source, String path) {
    JsonCodec annotation = TypeUseMetadata.typeUseAnnotation(typeUse, JsonCodec.class);
    Object[] upperBounds = TypeUseMetadata.wildcardUpperBounds(typeUse);
    Object[] lowerBounds = TypeUseMetadata.wildcardLowerBounds(typeUse);
    if (upperBounds != null || lowerBounds != null) {
      boolean boundExplicit =
          scanChildren(upperBounds, source, path, ".upperBound[")
              | scanChildren(lowerBounds, source, path, ".lowerBound[");
      if (annotation != null || boundExplicit) {
        throw new ForyJsonException(
            "@JsonCodec cannot target a wildcard or wildcard bound at " + source + " " + path);
      }
      return false;
    }
    boolean explicit = annotation != null;
    explicit |= scanChildren(TypeUseMetadata.typeUseArguments(typeUse), source, path, ".argument[");
    Object component = TypeUseMetadata.arrayComponentTypeUse(typeUse);
    if (component != null) {
      explicit |= scanExplicit(component, source, path + ".component");
    }
    return explicit;
  }

  private static boolean scanChildren(
      Object[] children, String source, String path, String childPath) {
    boolean explicit = false;
    if (children != null) {
      for (int i = 0; i < children.length; i++) {
        explicit |= scanExplicit(children[i], source, path + childPath + i + "]");
      }
    }
    return explicit;
  }

  private static JsonTypeUse buildTypeUse(Object typeUse, String source, String path) {
    Type type = TypeUseMetadata.typeRef(typeUse).getType();
    JsonCodec annotation = TypeUseMetadata.typeUseAnnotation(typeUse, JsonCodec.class);
    Object[] argumentUses = TypeUseMetadata.typeUseArguments(typeUse);
    JsonTypeUse[] arguments = buildTypeUseChildren(argumentUses, source, path, ".argument[");
    if (arguments.length == 0 && type instanceof ParameterizedType) {
      arguments = plainTypes(((ParameterizedType) type).getActualTypeArguments());
    }
    Object componentUse = TypeUseMetadata.arrayComponentTypeUse(typeUse);
    JsonTypeUse component =
        componentUse == null
            ? plainArrayComponent(type)
            : buildTypeUse(componentUse, source, path + ".component");
    Object[] upperUses = TypeUseMetadata.wildcardUpperBounds(typeUse);
    Object[] lowerUses = TypeUseMetadata.wildcardLowerBounds(typeUse);
    JsonTypeUse[] upper = buildTypeUseChildren(upperUses, source, path, ".upperBound[");
    JsonTypeUse[] lower = buildTypeUseChildren(lowerUses, source, path, ".lowerBound[");
    if (upper.length == 0 && type instanceof WildcardType) {
      upper = plainTypes(((WildcardType) type).getUpperBounds());
    }
    if (lower.length == 0 && type instanceof WildcardType) {
      lower = plainTypes(((WildcardType) type).getLowerBounds());
    }
    return new JsonTypeUse(
        type,
        annotation == null ? null : readCodecClass(annotation, source, path),
        annotation == null ? NO_SOURCES : new String[] {source + " at " + path},
        arguments,
        component,
        upper,
        lower);
  }

  private static Class<? extends JsonValueCodec<?>> readCodecClass(
      JsonCodec annotation, String source, String path) {
    try {
      return annotation.value();
    } catch (RuntimeException | LinkageError e) {
      throw new ForyJsonException("Cannot resolve @JsonCodec at " + source + " " + path, e);
    }
  }

  private static JsonTypeUse[] buildTypeUseChildren(
      Object[] children, String source, String path, String childPath) {
    if (children == null || children.length == 0) {
      return NO_CHILDREN;
    }
    JsonTypeUse[] values = new JsonTypeUse[children.length];
    for (int i = 0; i < children.length; i++) {
      values[i] = buildTypeUse(children[i], source, path + childPath + i + "]");
    }
    return values;
  }

  private static JsonTypeUse plainType(Type type) {
    JsonTypeUse[] arguments = NO_CHILDREN;
    JsonTypeUse component = null;
    JsonTypeUse[] upper = NO_CHILDREN;
    JsonTypeUse[] lower = NO_CHILDREN;
    if (type instanceof ParameterizedType) {
      arguments = plainTypes(((ParameterizedType) type).getActualTypeArguments());
    } else if (type instanceof GenericArrayType) {
      component = plainType(((GenericArrayType) type).getGenericComponentType());
    } else if (type instanceof Class && ((Class<?>) type).isArray()) {
      component = plainType(((Class<?>) type).getComponentType());
    } else if (type instanceof WildcardType) {
      WildcardType wildcard = (WildcardType) type;
      upper = plainTypes(wildcard.getUpperBounds());
      lower = plainTypes(wildcard.getLowerBounds());
    }
    return new JsonTypeUse(type, null, NO_SOURCES, arguments, component, upper, lower);
  }

  private static JsonTypeUse[] plainTypes(Type[] types) {
    if (types == null || types.length == 0) {
      return NO_CHILDREN;
    }
    JsonTypeUse[] values = new JsonTypeUse[types.length];
    for (int i = 0; i < types.length; i++) {
      values[i] = plainType(types[i]);
    }
    return values;
  }

  private static JsonTypeUse plainArrayComponent(Type type) {
    if (type instanceof GenericArrayType) {
      return plainType(((GenericArrayType) type).getGenericComponentType());
    }
    if (type instanceof Class && ((Class<?>) type).isArray()) {
      return plainType(((Class<?>) type).getComponentType());
    }
    return null;
  }

  private static Map<TypeVariable<?>, JsonTypeUse> buildBindings(JsonTypeUse owner, String path) {
    Map<TypeVariable<?>, JsonTypeUse> bindings = new HashMap<>();
    populateBindings(owner, bindings, new HashSet<Class<?>>(), path);
    return bindings;
  }

  private static void populateBindings(
      JsonTypeUse current,
      Map<TypeVariable<?>, JsonTypeUse> bindings,
      Set<Class<?>> visited,
      String path) {
    Class<?> rawType = current.rawType;
    TypeVariable<?>[] variables = rawType.getTypeParameters();
    int bindingCount = Math.min(variables.length, current.arguments.length);
    for (int i = 0; i < bindingCount; i++) {
      TypeVariable<?> variable = variables[i];
      JsonTypeUse old = bindings.get(variable);
      JsonTypeUse value = current.arguments[i];
      bindings.put(
          variable,
          old == null
              ? value
              : merge(old, value, path + ".typeParameter[" + variable.getName() + "]"));
    }
    if (!visited.add(rawType)) {
      return;
    }
    TypeRef<?> context = TypeRef.of(current.type);
    Type superclass = rawType.getGenericSuperclass();
    if (superclass != null) {
      JsonTypeUse resolved =
          resolveNode(plainType(superclass), context, bindings, path + ".superclass");
      populateBindings(resolved, bindings, visited, path + ".superclass");
    }
    Type[] interfaces = rawType.getGenericInterfaces();
    for (int i = 0; i < interfaces.length; i++) {
      JsonTypeUse resolved =
          resolveNode(plainType(interfaces[i]), context, bindings, path + ".interface[" + i + "]");
      populateBindings(resolved, bindings, visited, path + ".interface[" + i + "]");
    }
  }

  private static JsonTypeUse resolveNode(
      JsonTypeUse node,
      TypeRef<?> context,
      Map<TypeVariable<?>, JsonTypeUse> bindings,
      String path) {
    Type declaredType = node.type;
    if (declaredType instanceof TypeVariable) {
      JsonTypeUse binding = bindings.get(declaredType);
      if (binding != null && !binding.type.equals(declaredType)) {
        return mergeVariableOccurrence(binding, node, path);
      }
      Type resolvedType = context.resolveType(declaredType).getType();
      JsonTypeUse resolved =
          resolvedType.equals(declaredType)
              ? node
              : mergeVariableOccurrence(plainType(resolvedType), node, path);
      requireConcreteCodecTarget(resolved, path);
      return resolved;
    }
    Type resolvedType = context.resolveType(declaredType).getType();
    JsonTypeUse[] arguments =
        resolveChildren(node.arguments, context, bindings, path, ".argument[");
    JsonTypeUse component =
        node.arrayComponent == null
            ? null
            : resolveNode(node.arrayComponent, context, bindings, path + ".component");
    JsonTypeUse[] upper =
        resolveChildren(node.upperBounds, context, bindings, path, ".upperBound[");
    JsonTypeUse[] lower =
        resolveChildren(node.lowerBounds, context, bindings, path, ".lowerBound[");
    JsonTypeUse resolved =
        new JsonTypeUse(
            resolvedType, node.codecClass, node.codecSources, arguments, component, upper, lower);
    requireConcreteCodecTarget(resolved, path);
    return resolved;
  }

  private static JsonTypeUse[] resolveChildren(
      JsonTypeUse[] children,
      TypeRef<?> context,
      Map<TypeVariable<?>, JsonTypeUse> bindings,
      String path,
      String childPath) {
    if (children.length == 0) {
      return children;
    }
    JsonTypeUse[] resolved = new JsonTypeUse[children.length];
    for (int i = 0; i < children.length; i++) {
      resolved[i] = resolveNode(children[i], context, bindings, path + childPath + i + "]");
    }
    return resolved;
  }

  private static JsonTypeUse mergeVariableOccurrence(
      JsonTypeUse binding, JsonTypeUse occurrence, String path) {
    if (occurrence.codecClass == null) {
      return binding;
    }
    Class<? extends JsonValueCodec<?>> codec = binding.codecClass;
    String[] sources = binding.codecSources;
    if (codec == null) {
      codec = occurrence.codecClass;
      sources = occurrence.codecSources;
    } else if (codec != occurrence.codecClass) {
      throw codecConflict(path, binding, occurrence);
    } else {
      sources = mergeSources(binding.codecSources, occurrence.codecSources);
    }
    JsonTypeUse resolved =
        new JsonTypeUse(
            binding.type,
            codec,
            sources,
            binding.arguments,
            binding.arrayComponent,
            binding.upperBounds,
            binding.lowerBounds);
    requireConcreteCodecTarget(resolved, path);
    return resolved;
  }

  private static void requireConcreteCodecTarget(JsonTypeUse node, String path) {
    if (node.codecClass != null
        && (node.type instanceof TypeVariable || node.type instanceof WildcardType)) {
      throw new ForyJsonException(
          "@JsonCodec from "
              + node.codecSource()
              + " does not resolve to one concrete target at "
              + path
              + ": "
              + node.type.getTypeName());
    }
  }

  private void rejectUnprojectedDescendants(
      JsonTypeUse projected, String path, Class<?> targetRawType) {
    Set<String> projectedSources = new HashSet<>();
    collectSources(projected, projectedSources, true);
    Set<String> descendantSources = new LinkedHashSet<>();
    for (JsonTypeUse argument : arguments) {
      collectSources(argument, descendantSources, true);
    }
    if (arrayComponent != null) {
      collectSources(arrayComponent, descendantSources, true);
    }
    for (String source : descendantSources) {
      if (!projectedSources.contains(source)) {
        throw new ForyJsonException(
            "@JsonCodec from "
                + source
                + " does not map to a delegated "
                + targetRawType.getTypeName()
                + " child at "
                + path);
      }
    }
  }

  private static void collectSources(
      JsonTypeUse node, Set<String> sources, boolean includeCurrent) {
    if (includeCurrent) {
      sources.addAll(Arrays.asList(node.codecSources));
    }
    for (JsonTypeUse argument : node.arguments) {
      collectSources(argument, sources, true);
    }
    if (node.arrayComponent != null) {
      collectSources(node.arrayComponent, sources, true);
    }
    for (JsonTypeUse bound : node.upperBounds) {
      collectSources(bound, sources, true);
    }
    for (JsonTypeUse bound : node.lowerBounds) {
      collectSources(bound, sources, true);
    }
  }

  private static JsonTypeUse[] mergeChildren(
      JsonTypeUse[] left, JsonTypeUse[] right, String path, String childPath) {
    if (left.length != right.length) {
      throw incompatibleTrees(path, left.length, right.length);
    }
    JsonTypeUse[] merged = null;
    for (int i = 0; i < left.length; i++) {
      JsonTypeUse value = merge(left[i], right[i], path + childPath + i + "]");
      if (value != left[i] && merged == null) {
        merged = left.clone();
      }
      if (merged != null) {
        merged[i] = value;
      }
    }
    return merged == null ? left : merged;
  }

  private static void requireSameShape(JsonTypeUse left, JsonTypeUse right, String path) {
    if (!left.type.equals(right.type)
        || left.arguments.length != right.arguments.length
        || (left.arrayComponent == null) != (right.arrayComponent == null)
        || left.upperBounds.length != right.upperBounds.length
        || left.lowerBounds.length != right.lowerBounds.length) {
      throw new ForyJsonException(
          "Cannot merge @JsonCodec metadata for unequal resolved types at "
              + path
              + ": "
              + left.type.getTypeName()
              + " and "
              + right.type.getTypeName());
    }
  }

  private static ForyJsonException codecConflict(String path, JsonTypeUse left, JsonTypeUse right) {
    return new ForyJsonException(
        "Conflicting @JsonCodec values at "
            + path
            + ": "
            + left.codecClass.getName()
            + " from "
            + Arrays.toString(left.codecSources)
            + " and "
            + right.codecClass.getName()
            + " from "
            + Arrays.toString(right.codecSources));
  }

  private static ForyJsonException incompatibleTrees(String path, int left, int right) {
    return new ForyJsonException(
        "Cannot merge @JsonCodec metadata with different child counts at "
            + path
            + ": "
            + left
            + " and "
            + right);
  }

  private static String[] mergeSources(String[] left, String[] right) {
    boolean hasNewSource = false;
    for (String source : right) {
      if (!contains(left, source)) {
        hasNewSource = true;
        break;
      }
    }
    if (!hasNewSource) {
      return left;
    }
    String[] merged = Arrays.copyOf(left, left.length + right.length);
    int size = left.length;
    for (String source : right) {
      if (!contains(merged, size, source)) {
        merged[size++] = source;
      }
    }
    return size == merged.length ? merged : Arrays.copyOf(merged, size);
  }

  private static boolean contains(String[] values, String value) {
    return contains(values, values.length, value);
  }

  private static boolean contains(String[] values, int length, String value) {
    for (int i = 0; i < length; i++) {
      if (values[i].equals(value)) {
        return true;
      }
    }
    return false;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static TypeRef<?> supertype(TypeRef<?> typeRef, Class<?> rawType) {
    return ((TypeRef) typeRef).getSupertype((Class) rawType);
  }

  private static Type[] typeArguments(Type type) {
    return type instanceof ParameterizedType
        ? ((ParameterizedType) type).getActualTypeArguments()
        : new Type[0];
  }

  private static JsonTypeUse firstExplicit(JsonTypeUse[] children) {
    for (JsonTypeUse child : children) {
      JsonTypeUse explicit = child.firstExplicit();
      if (explicit != null) {
        return explicit;
      }
    }
    return null;
  }

  private JsonTypeUse firstExplicit() {
    return codecClass == null ? firstExplicitDescendant() : this;
  }

  private static boolean containsExplicit(JsonTypeUse[] children) {
    for (JsonTypeUse child : children) {
      if (child.hasExplicitCodec) {
        return true;
      }
    }
    return false;
  }

  private int structuralHashCode() {
    int hash = 31 * type.hashCode() + System.identityHashCode(codecClass);
    hash = 31 * hash + Arrays.hashCode(arguments);
    hash = 31 * hash + Objects.hashCode(arrayComponent);
    hash = 31 * hash + Arrays.hashCode(upperBounds);
    return 31 * hash + Arrays.hashCode(lowerBounds);
  }

  private static JsonTypeUse[] emptyIfNull(JsonTypeUse[] values) {
    return values == null || values.length == 0 ? NO_CHILDREN : values;
  }

  private static String[] emptyIfNull(String[] values) {
    return values == null || values.length == 0 ? NO_SOURCES : values;
  }

  private static void checkParameterIndex(
      Object[] typeUses, int parameterCount, int parameterIndex, String executable) {
    if (parameterIndex < 0 || parameterIndex >= parameterCount) {
      throw new IndexOutOfBoundsException(
          "Parameter index " + parameterIndex + " is outside " + executable);
    }
    if (typeUses != null && typeUses.length != parameterCount) {
      throw new ForyJsonException(
          "Incomplete type-use metadata for "
              + executable
              + ": expected "
              + parameterCount
              + " parameters but found "
              + typeUses.length);
    }
  }
}
