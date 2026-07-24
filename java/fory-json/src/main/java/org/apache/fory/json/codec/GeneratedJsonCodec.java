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

import java.lang.reflect.Executable;
import java.lang.reflect.Member;
import java.util.Collections;
import java.util.Map;
import org.apache.fory.annotation.Internal;
import org.apache.fory.json.meta.JsonAnySetterAccessor;
import org.apache.fory.json.meta.JsonFieldAccessor;

/** Source-generated execution operations for one JSON model. */
@Internal
public abstract class GeneratedJsonCodec<T> {
  private Map<Member, JsonFieldAccessor> validatedAccessors;
  private JsonAnySetterAccessor validatedAnySetterAccessor;
  private Member validatedAnySetterMember;
  private String[] validatedCreatorParameterNames;
  private Class<?>[] validatedCreatorParameterTypes;
  private String validatedCreatorFactoryName;
  private Executable validatedCreator;
  private boolean validatedRecord;
  private boolean initialized;

  protected GeneratedJsonCodec() {}

  /** Returns the exact model class owned by this companion. */
  public abstract Class<T> type();

  /** Returns fresh generated member-access candidates for cold schema binding. */
  public abstract JsonFieldAccessor[] fieldAccessors();

  /** Returns the generated dynamic any-setter operation, or {@code null}. */
  public JsonAnySetterAccessor anySetterAccessor() {
    return null;
  }

  /** Returns creator parameter names in executable order, or {@code null} for mutable objects. */
  public String[] creatorParameterNames() {
    return null;
  }

  /** Returns exact erased creator parameter types, or {@code null} for mutable objects. */
  public Class<?>[] creatorParameterTypes() {
    return null;
  }

  /** Returns the static creator factory name, or {@code null} for a constructor. */
  public String creatorFactoryName() {
    return null;
  }

  /** Returns whether the source model is a Java Record. */
  public boolean isRecord() {
    return false;
  }

  /** Constructs a creator-backed object from arguments in creator parameter order. */
  public T newInstance(Object[] arguments) {
    throw new UnsupportedOperationException();
  }

  /** Publishes registry-validated hook results exactly once. */
  public final synchronized void initializeValidated(
      Map<Member, JsonFieldAccessor> accessors,
      JsonAnySetterAccessor anySetterAccessor,
      Member anySetterMember,
      String[] creatorParameterNames,
      Class<?>[] creatorParameterTypes,
      String creatorFactoryName,
      Executable creator,
      boolean record) {
    if (initialized) {
      throw new IllegalStateException("Generated JSON codec is already initialized");
    }
    validatedAccessors = Collections.unmodifiableMap(accessors);
    validatedAnySetterAccessor = anySetterAccessor;
    validatedAnySetterMember = anySetterMember;
    validatedCreatorParameterNames = creatorParameterNames;
    validatedCreatorParameterTypes = creatorParameterTypes;
    validatedCreatorFactoryName = creatorFactoryName;
    validatedCreator = creator;
    validatedRecord = record;
    initialized = true;
  }

  /** Returns the validated generated accessor for the exact selected member. */
  public final JsonFieldAccessor validatedAccessor(Member member) {
    requireInitialized();
    return validatedAccessors.get(member);
  }

  /** Returns the generated any-setter operation for the exact selected method. */
  final JsonAnySetterAccessor anySetter(Member member) {
    requireInitialized();
    return member != null && member.equals(validatedAnySetterMember)
        ? validatedAnySetterAccessor
        : null;
  }

  /** Returns whether the generated companion declared an any-setter operation. */
  final boolean hasAnySetter() {
    requireInitialized();
    return validatedAnySetterAccessor != null;
  }

  /** Returns the registry-owned creator parameter names. */
  final String[] validatedCreatorParameterNames() {
    requireInitialized();
    return validatedCreatorParameterNames;
  }

  /** Returns the registry-owned creator parameter types. */
  final Class<?>[] validatedCreatorParameterTypes() {
    requireInitialized();
    return validatedCreatorParameterTypes;
  }

  /** Returns the validated static creator factory name. */
  final String validatedCreatorFactoryName() {
    requireInitialized();
    return validatedCreatorFactoryName;
  }

  /** Returns the exact registry-validated creator executable, or {@code null}. */
  final Executable validatedCreator() {
    requireInitialized();
    return validatedCreator;
  }

  /** Returns validated source-level Record identity. */
  public final boolean validatedRecord() {
    requireInitialized();
    return validatedRecord;
  }

  /** Returns whether the generated construction operation targets the exact executable. */
  public final boolean matchesCreator(Executable creator) {
    requireInitialized();
    return creator != null && creator.equals(validatedCreator);
  }

  /** Rethrows a checked creator failure without widening the generated ABI. */
  protected static RuntimeException creatorFailure(Throwable throwable) {
    GeneratedJsonCodec.<RuntimeException>throwUnchecked(throwable);
    throw new AssertionError();
  }

  @SuppressWarnings("unchecked")
  private static <E extends Throwable> void throwUnchecked(Throwable throwable) throws E {
    throw (E) throwable;
  }

  private void requireInitialized() {
    if (!initialized) {
      throw new IllegalStateException("Generated JSON codec has not been initialized");
    }
  }
}
