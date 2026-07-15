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
 * Marks a reachable JSON model for build-time reflection configuration.
 *
 * <p>When the Fory annotation processor is enabled, it generates exact R8 rules for the model and
 * the codec classes selected by its {@link JsonCodec} declarations. A class-literal subtype listed
 * by an annotated {@link JsonSubTypes} base is processed automatically. The annotation is also used
 * for reflection metadata registration in GraalVM native images.
 *
 * <p>This annotation does not change JSON codec semantics and is intentionally not inherited. It is
 * not required for Android runtime use: applications may supply equivalent exact R8 rules
 * themselves.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface JsonType {}
