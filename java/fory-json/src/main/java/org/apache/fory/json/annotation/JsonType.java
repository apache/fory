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

package org.apache.fory.json.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a JSON model for build-time language processing and retention metadata.
 *
 * <p>The Java Fory annotation processor generates a type-owned JSON companion for an eligible
 * concrete object model or a Record with an effective {@link JsonValue}, together with exact R8
 * rules for the model, companion, and codec classes selected by its {@link JsonCodec} declarations.
 * The companion provides direct member access and creator invocation on the JVM and Android. The
 * Kotlin Symbol Processing (KSP) processor emits exact retention rules only; the installed Kotlin
 * module supplies the metadata-backed object model used by interpreted and generated execution.
 * GraalVM Native Image discovers this annotation directly and does not require annotation-processor
 * output. Hosted analysis snapshots a Java companion when one is available, but reflection
 * reachability and hosted-generated capabilities remain sufficient without it.
 *
 * <p>A concrete Java subtype listed only by a class-literal {@link JsonSubTypes} entry receives
 * retention metadata but needs its own direct {@code JsonType} annotation to receive a Java
 * companion on the JVM or Android. Outside Native Image, a directly annotated Java model that
 * reaches the default object codec fails during codec creation when its generated companion is
 * missing.
 *
 * <p>This annotation does not change the JSON schema and is intentionally not inherited. An
 * ordinary mutable class may omit it and use reflection, with application-authored exact R8 rules
 * on Android. Android-desugared Records, including Records represented by {@link JsonValue},
 * require processor-generated operations from either a direct {@code JsonType} declaration or an
 * exact {@link JsonMixin} pair because Android does not expose the Java Record reflection APIs.
 *
 * <p>{@link JsonMixin} cannot contribute or remove this build-time marker. A non-empty Mixin is its
 * own processor entry point. The Java annotation processor generates the operations and platform
 * configuration for its exact target/source mapping; Kotlin KSP emits only the corresponding
 * retention rules.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface JsonType {}
