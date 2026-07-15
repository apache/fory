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

import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.annotation.JsonAnyGetter;
import org.apache.fory.json.annotation.JsonAnyProperty;
import org.apache.fory.json.annotation.JsonBase64;
import org.apache.fory.json.annotation.JsonCodec;
import org.apache.fory.json.annotation.JsonIgnore;
import org.apache.fory.json.annotation.JsonProperty;
import org.apache.fory.json.annotation.JsonRawValue;
import org.apache.fory.json.annotation.JsonUnwrapped;
import org.apache.fory.json.annotation.JsonValue;
import org.apache.fory.json.codec.GeneratedJsonCodec;
import org.apache.fory.json.codec.JsonValueCodec;
import org.apache.fory.json.meta.JsonCreatorDeclaration;
import org.apache.fory.json.meta.JsonFieldAccessor;
import org.apache.fory.util.record.RecordUtils;

/** Immutable result of resolving one effective {@link JsonValue} declaration. */
final class JsonValueDeclaration {
  private static final Comparator<Member> MEMBER_ORDER =
      Comparator.comparing((Member member) -> member.getDeclaringClass().getName())
          .thenComparing(Member::getName)
          .thenComparing(Member::toString);

  private final JsonValueCodec<Object> codec;

  private JsonValueDeclaration(JsonValueCodec<Object> codec) {
    this.codec = codec;
  }

  JsonValueCodec<Object> codec() {
    return codec;
  }

  static JsonValueDeclaration resolve(Class<?> type, GeneratedJsonCodec<?> generatedCodec) {
    List<Member> members = new ArrayList<>();
    collectFields(type, members);
    collectMethods(type, members);
    coalesceRecordMember(type, members, generatedCodec);
    if (members.isEmpty()) {
      return null;
    }
    if (members.size() != 1) {
      members.sort(MEMBER_ORDER);
      throw multipleValuesException(type, members);
    }
    Member member = members.get(0);
    JsonFieldAccessor accessor;
    boolean raw;
    if (member instanceof Field) {
      Field field = (Field) member;
      validateField(field);
      accessor = generatedAccessor(generatedCodec, field);
      raw = field.isAnnotationPresent(JsonRawValue.class);
    } else {
      Method method = (Method) member;
      validateMethod(method);
      accessor = generatedAccessor(generatedCodec, method);
      raw = method.isAnnotationPresent(JsonRawValue.class);
    }
    Executable creator = valueCreator(type, generatedCodec);
    GeneratedJsonCodec<?> creatorBackend =
        generatedCodec != null && generatedCodec.matchesCreator(creator) ? generatedCodec : null;
    return new JsonValueDeclaration(
        new JsonStringValueCodec(type, accessor, creator, creatorBackend, raw));
  }

  private static JsonFieldAccessor generatedAccessor(
      GeneratedJsonCodec<?> generatedCodec, Member member) {
    JsonFieldAccessor accessor =
        generatedCodec == null ? null : generatedCodec.validatedAccessor(member);
    if (accessor != null) {
      return accessor;
    }
    return member instanceof Field
        ? JsonFieldAccessor.forField((Field) member)
        : JsonFieldAccessor.forGetter((Method) member);
  }

  private static void collectFields(Class<?> type, List<Member> members) {
    for (Class<?> current = type;
        current != null && current != Object.class;
        current = current.getSuperclass()) {
      for (Field field : current.getDeclaredFields()) {
        if (field.isAnnotationPresent(JsonValue.class)) {
          validateField(field);
          members.add(field);
        }
      }
    }
  }

  private static void collectMethods(Class<?> type, List<Member> members) {
    for (Method method : type.getMethods()) {
      if (method.isAnnotationPresent(JsonValue.class)) {
        validateMethod(method);
        members.add(method);
      }
    }
    for (Class<?> current = type;
        current != null && current != Object.class;
        current = current.getSuperclass()) {
      for (Method method : current.getDeclaredMethods()) {
        if (!method.isAnnotationPresent(JsonValue.class)
            || Modifier.isPublic(method.getModifiers())) {
          continue;
        }
        validateMethod(method);
      }
    }
  }

  private static void coalesceRecordMember(
      Class<?> type, List<Member> members, GeneratedJsonCodec<?> generatedCodec) {
    // Android desugaring removes the platform Record identity but preserves the propagated field
    // and accessor annotations. The validated companion is therefore the authoritative Record
    // identity and lets this semantic owner select the direct accessor without structural guesses.
    boolean record =
        generatedCodec == null ? RecordUtils.isRecord(type) : generatedCodec.validatedRecord();
    if (!record || members.size() != 2) {
      return;
    }
    Field field = null;
    Method method = null;
    for (Member member : members) {
      if (member instanceof Field) {
        field = (Field) member;
      } else if (member instanceof Method) {
        method = (Method) member;
      }
    }
    if (field != null
        && method != null
        && field.getDeclaringClass() == type
        && method.getDeclaringClass() == type
        && field.getName().equals(method.getName())
        && field.isAnnotationPresent(JsonRawValue.class)
            == method.isAnnotationPresent(JsonRawValue.class)) {
      members.clear();
      members.add(
          generatedCodec != null && generatedCodec.validatedAccessor(method) != null
              ? method
              : field);
    }
  }

  private static void validateField(Field field) {
    int modifiers = field.getModifiers();
    if (Modifier.isStatic(modifiers)
        || Modifier.isTransient(modifiers)
        || field.isSynthetic()
        || field.getType() != String.class) {
      throw new ForyJsonException("Invalid @JsonValue field " + field);
    }
    if (field.isAnnotationPresent(JsonCodec.class)
        || field.isAnnotationPresent(JsonBase64.class)
        || field.isAnnotationPresent(JsonAnyProperty.class)
        || field.isAnnotationPresent(JsonUnwrapped.class)
        || field.isAnnotationPresent(JsonIgnore.class)) {
      throw new ForyJsonException("Conflicting JSON annotations on @JsonValue field " + field);
    }
  }

  private static void validateMethod(Method method) {
    int modifiers = method.getModifiers();
    if (!Modifier.isPublic(modifiers)
        || Modifier.isStatic(modifiers)
        || method.isSynthetic()
        || method.isBridge()
        || method.isVarArgs()
        || method.getTypeParameters().length != 0
        || method.getParameterCount() != 0
        || method.getReturnType() != String.class) {
      throw new ForyJsonException("Invalid @JsonValue method " + method);
    }
    if (method.isAnnotationPresent(JsonCodec.class)
        || method.isAnnotationPresent(JsonBase64.class)
        || method.isAnnotationPresent(JsonAnyGetter.class)
        || method.isAnnotationPresent(JsonUnwrapped.class)
        || method.isAnnotationPresent(JsonIgnore.class)) {
      throw new ForyJsonException("Conflicting JSON annotations on @JsonValue method " + method);
    }
  }

  private static Executable valueCreator(Class<?> type, GeneratedJsonCodec<?> generatedCodec) {
    JsonCreatorDeclaration declaration = JsonCreatorDeclaration.find(type);
    if (declaration == null) {
      return null;
    }
    Executable executable = declaration.executable();
    if (declaration.annotation().value().length != 0
        || executable.getParameterCount() != 1
        || executable.getParameterTypes()[0] != String.class) {
      rejectRecordCreator(type, generatedCodec);
      return null;
    }
    Parameter parameter = executable.getParameters()[0];
    if (parameter.isAnnotationPresent(JsonProperty.class)) {
      rejectRecordCreator(type, generatedCodec);
      return null;
    }
    return executable;
  }

  private static void rejectRecordCreator(Class<?> type, GeneratedJsonCodec<?> generatedCodec) {
    if (generatedCodec == null ? RecordUtils.isRecord(type) : generatedCodec.validatedRecord()) {
      throw new ForyJsonException("@JsonCreator is not supported on record " + type.getName());
    }
  }

  private static ForyJsonException multipleValuesException(Class<?> type, List<Member> members) {
    StringBuilder message =
        new StringBuilder("Multiple effective @JsonValue declarations on ")
            .append(type.getName())
            .append(':');
    for (Member member : members) {
      message.append(' ').append(member).append(';');
    }
    return new ForyJsonException(message.toString());
  }
}
