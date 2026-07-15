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

package org.apache.fory.json.resolver;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.IdentityHashMap;
import java.util.Map;
import org.apache.fory.annotation.Internal;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.meta.JsonDecodedMetadata;
import org.apache.fory.json.meta.JsonMetadataDecoder;
import org.apache.fory.json.meta.JsonMetadataFormat;
import org.apache.fory.json.meta.JsonTypeMetadata;
import org.apache.fory.json.meta.JsonTypeMetadataData;

/** Per-{@link JsonSharedRegistry} owner for Android generated JSON metadata. */
@Internal
public final class JsonTypeMetadataRegistry {
  private final Map<Class<?>, Entry> entries = new IdentityHashMap<>();
  private final Map<Class<?>, Failure> failures = new IdentityHashMap<>();

  /** Returns one independently decoded and cached inert section. */
  public JsonDecodedMetadata decodedSection(Class<?> type, int section) {
    return entry(type).section(section);
  }

  /**
   * Resolves a primitive or generated direct-literal token without applying value-type policy.
   * Inaccessible-name tokens must be resolved by their semantic owner in its required security
   * order.
   */
  public Class<?> generatedType(
      Class<?> owner, int section, JsonDecodedMetadata decoded, int tokenIndex) {
    requireSection(owner, section, decoded);
    JsonDecodedMetadata.Token token = token(decoded, tokenIndex, owner);
    if (token.mode() == JsonMetadataFormat.TOKEN_PRIMITIVE) {
      return primitiveType(token.primitiveKind());
    }
    if (token.mode() != JsonMetadataFormat.TOKEN_DIRECT) {
      throw invalidMetadata(
          owner,
          entry(owner).metadata.getClass().getName(),
          "type token " + tokenIndex + " is an inaccessible-name token",
          null);
    }
    Class<?> type = entry(owner).metadataType(section, token.directIndex());
    if (type == null || type.isPrimitive()) {
      throw invalidMetadata(
          owner,
          entry(owner).metadata.getClass().getName(),
          "direct type token " + tokenIndex + " returned " + String.valueOf(type),
          null);
    }
    return type;
  }

  /** Returns a validated inaccessible binary name without loading its class. */
  public String inaccessibleName(JsonDecodedMetadata decoded, int tokenIndex) {
    JsonDecodedMetadata.Token token = token(decoded, tokenIndex, null);
    if (token.mode() != JsonMetadataFormat.TOKEN_INACCESSIBLE) {
      throw new ForyJsonException(
          "Generated JSON metadata type token " + tokenIndex + " is not name-backed");
    }
    return token.inaccessibleName();
  }

  /**
   * Resolves an exact member-signature token through the annotated target's defining loader.
   * Serialized values and subtype tokens must use their role-specific resolver instead.
   */
  public Class<?> memberType(
      Class<?> owner, int section, JsonDecodedMetadata decoded, int tokenIndex) {
    JsonDecodedMetadata.Token token = token(decoded, tokenIndex, owner);
    if (token.mode() != JsonMetadataFormat.TOKEN_INACCESSIBLE) {
      return generatedType(owner, section, decoded, tokenIndex);
    }
    requireSection(owner, section, decoded);
    try {
      return Class.forName(token.inaccessibleName(), false, owner.getClassLoader());
    } catch (ClassNotFoundException | LinkageError e) {
      throw invalidMetadata(
          owner,
          entry(owner).metadata.getClass().getName(),
          "cannot resolve member type " + token.inaccessibleName(),
          e);
    }
  }

  /** Returns one validated inert operation recipe without constructing its implementation. */
  public JsonDecodedMetadata.Operation operation(
      Class<?> owner,
      int section,
      JsonDecodedMetadata decoded,
      int operationIndex,
      int expectedShape) {
    requireSection(owner, section, decoded);
    if (operationIndex < 0 || operationIndex >= decoded.operationCount()) {
      throw invalidMetadata(
          owner,
          entry(owner).metadata.getClass().getName(),
          "operation index " + operationIndex + " is out of bounds in section " + section,
          null);
    }
    JsonDecodedMetadata.Operation operation = decoded.operation(operationIndex);
    if (operation.shape() != expectedShape) {
      throw invalidMetadata(
          owner,
          entry(owner).metadata.getClass().getName(),
          "operation "
              + operationIndex
              + " has shape "
              + operation.shape()
              + ", expected "
              + expectedShape,
          null);
    }
    return operation;
  }

  /** Initializes and returns one selected direct generated operation singleton. */
  public <T> T directOperation(
      Class<?> owner,
      int section,
      JsonDecodedMetadata decoded,
      int operationIndex,
      int expectedShape,
      Class<T> expectedType) {
    JsonDecodedMetadata.Operation operation =
        operation(owner, section, decoded, operationIndex, expectedShape);
    if (operation.mode() != JsonMetadataFormat.OP_DIRECT) {
      throw invalidMetadata(
          owner,
          entry(owner).metadata.getClass().getName(),
          "operation " + operationIndex + " is handle-backed rather than generated direct code",
          null);
    }
    Object value = entry(owner).metadataOperation(section, operation.directIndex());
    if (!expectedType.isInstance(value)) {
      throw invalidMetadata(
          owner,
          entry(owner).metadata.getClass().getName(),
          "operation "
              + operationIndex
              + " returned "
              + (value == null ? "null" : value.getClass().getName())
              + ", expected "
              + expectedType.getName(),
          null);
    }
    return expectedType.cast(value);
  }

  private void requireSection(Class<?> owner, int section, JsonDecodedMetadata decoded) {
    if (decoded == null
        || decoded.section() != section
        || entry(owner).section(section) != decoded) {
      throw invalidMetadata(
          owner,
          entry(owner).metadata.getClass().getName(),
          "decoded section does not belong to this target and section",
          null);
    }
  }

  private static JsonDecodedMetadata.Token token(
      JsonDecodedMetadata decoded, int tokenIndex, Class<?> owner) {
    if (decoded == null || tokenIndex < 0 || tokenIndex >= decoded.tokenCount()) {
      String target = owner == null ? "generated metadata" : owner.getName();
      throw new ForyJsonException(
          "Generated JSON metadata type token " + tokenIndex + " is out of bounds for " + target);
    }
    return decoded.token(tokenIndex);
  }

  private Entry entry(Class<?> type) {
    if (type == null) {
      throw new ForyJsonException("Generated JSON metadata target must not be null");
    }
    synchronized (entries) {
      Entry entry = entries.get(type);
      if (entry != null) {
        return entry;
      }
      Failure failure = failures.get(type);
      if (failure != null) {
        throw failure.exception();
      }
      try {
        entry = load(type);
      } catch (ForyJsonException e) {
        failures.put(type, new Failure(e.getMessage(), e.getCause()));
        throw e;
      }
      entries.put(type, entry);
      return entry;
    }
  }

  private Entry load(Class<?> type) {
    String companionName = JsonTypeMetadata.generatedBinaryName(type.getName());
    Class<?> companionClass;
    try {
      companionClass = Class.forName(companionName, false, type.getClassLoader());
    } catch (ClassNotFoundException e) {
      throw missingMetadata(type, e);
    } catch (LinkageError e) {
      throw invalidMetadata(type, companionName, "cannot be linked", e);
    }
    if (!JsonTypeMetadata.class.isAssignableFrom(companionClass)
        || companionClass.isInterface()
        || Modifier.isAbstract(companionClass.getModifiers())) {
      throw invalidMetadata(
          type, companionName, "must be a concrete JsonTypeMetadata companion", null);
    }
    Constructor<?> constructor;
    try {
      constructor = companionClass.getConstructor(Class.class);
    } catch (NoSuchMethodException | SecurityException e) {
      throw invalidMetadata(type, companionName, "must expose public (Class) constructor", e);
    }
    MethodHandle constructorHandle;
    try {
      constructorHandle = MethodHandles.lookup().unreflectConstructor(constructor);
    } catch (IllegalAccessException | RuntimeException e) {
      throw invalidMetadata(type, companionName, "constructor is not accessible", e);
    }
    JsonTypeMetadata metadata = construct(type, companionName, constructorHandle);
    if (metadata.abiVersion() != JsonTypeMetadata.ABI_VERSION) {
      throw invalidMetadata(type, companionName, "reported an inconsistent ABI version", null);
    }
    return new Entry(type, metadata);
  }

  private static JsonTypeMetadata construct(
      Class<?> type, String companionName, MethodHandle constructor) {
    try {
      // Android 8 uses a constructor handle's return type as the class to instantiate. Adapting
      // the raw handle to abstract JsonTypeMetadata makes API 26 instantiate that base instead of
      // the concrete generated companion.
      return (JsonTypeMetadata) constructor.invoke(type);
    } catch (Throwable cause) {
      if (cause instanceof Error) {
        throw (Error) cause;
      }
      if (cause instanceof RuntimeException) {
        throw (RuntimeException) cause;
      }
      throw invalidMetadata(type, companionName, "constructor failed", cause);
    }
  }

  private static Class<?> primitiveType(int kind) {
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
        throw new ForyJsonException("Invalid generated JSON primitive kind " + kind);
    }
  }

  private static ForyJsonException missingMetadata(Class<?> type, Throwable cause) {
    String message =
        "Missing generated JSON metadata for "
            + type.getName()
            + ". Add @JsonType, enable the matching fory-annotation-processor, and preserve its "
            + "generated R8 rules.";
    return new ForyJsonException(message, cause);
  }

  private static ForyJsonException invalidMetadata(
      Class<?> type, String companion, String reason, Throwable cause) {
    String message =
        "Invalid generated JSON metadata "
            + companion
            + " for "
            + type.getName()
            + ": "
            + reason
            + ". Align fory-json and fory-annotation-processor and recompile the model.";
    return cause == null ? new ForyJsonException(message) : new ForyJsonException(message, cause);
  }

  private static final class Entry {
    private final Class<?> target;
    private final JsonTypeMetadata metadata;
    private JsonDecodedMetadata declarations;
    private JsonDecodedMetadata subtypes;
    private JsonDecodedMetadata object;
    private Failure declarationFailure;
    private Failure subtypeFailure;
    private Failure objectFailure;

    private Entry(Class<?> target, JsonTypeMetadata metadata) {
      this.target = target;
      this.metadata = metadata;
    }

    private synchronized JsonDecodedMetadata section(int section) {
      JsonDecodedMetadata decoded;
      Failure failure;
      switch (section) {
        case JsonTypeMetadata.DECLARATIONS:
          decoded = declarations;
          failure = declarationFailure;
          break;
        case JsonTypeMetadata.SUBTYPES:
          decoded = subtypes;
          failure = subtypeFailure;
          break;
        case JsonTypeMetadata.OBJECT:
          decoded = object;
          failure = objectFailure;
          break;
        default:
          throw new ForyJsonException(
              "Invalid generated JSON metadata section " + section + " for " + target.getName());
      }
      if (decoded != null) {
        return decoded;
      }
      if (failure != null) {
        throw failure.exception();
      }
      try {
        decoded = loadSection(section);
      } catch (ForyJsonException e) {
        cacheFailure(section, new Failure(e.getMessage(), e.getCause()));
        throw e;
      }
      cacheSection(section, decoded);
      return decoded;
    }

    private JsonDecodedMetadata loadSection(int section) {
      Object value;
      try {
        value = metadata.metadata(section);
      } catch (LinkageError | RuntimeException e) {
        throw invalidMetadata(
            target, metadata.getClass().getName(), "section " + section + " failed", e);
      }
      if (!(value instanceof JsonTypeMetadataData)) {
        throw invalidMetadata(
            target,
            metadata.getClass().getName(),
            "section " + section + " returned " + String.valueOf(value),
            null);
      }
      return JsonMetadataDecoder.decode(target, section, (JsonTypeMetadataData) value);
    }

    private void cacheSection(int section, JsonDecodedMetadata decoded) {
      if (section == JsonTypeMetadata.DECLARATIONS) {
        declarations = decoded;
      } else if (section == JsonTypeMetadata.SUBTYPES) {
        subtypes = decoded;
      } else {
        object = decoded;
      }
    }

    private void cacheFailure(int section, Failure failure) {
      if (section == JsonTypeMetadata.DECLARATIONS) {
        declarationFailure = failure;
      } else if (section == JsonTypeMetadata.SUBTYPES) {
        subtypeFailure = failure;
      } else {
        objectFailure = failure;
      }
    }

    private Class<?> metadataType(int section, int index) {
      try {
        return metadata.metadataType(section, index);
      } catch (LinkageError | RuntimeException e) {
        throw invalidMetadata(
            target,
            metadata.getClass().getName(),
            "type index " + index + " in section " + section + " failed",
            e);
      }
    }

    private Object metadataOperation(int section, int index) {
      try {
        return metadata.metadataOperation(section, index);
      } catch (LinkageError | RuntimeException e) {
        throw invalidMetadata(
            target,
            metadata.getClass().getName(),
            "operation index " + index + " in section " + section + " failed",
            e);
      }
    }
  }

  private static final class Failure {
    private final String message;
    private final Throwable cause;

    private Failure(String message, Throwable cause) {
      this.message = message;
      this.cause = cause;
    }

    private ForyJsonException exception() {
      return cause == null ? new ForyJsonException(message) : new ForyJsonException(message, cause);
    }
  }
}
