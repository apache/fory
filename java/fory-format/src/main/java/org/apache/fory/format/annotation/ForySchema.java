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

package org.apache.fory.format.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Class-level row-codec schema metadata used when the codec builder enables schema evolution.
 *
 * <p>Live fields without a {@link ForyVersion} annotation are treated as present from the first
 * version, so a class can adopt versioning by annotating only the fields added later.
 *
 * <p>{@link #removedFields()} points at a class (conventionally a nested {@code interface}) whose
 * accessor methods describe fields that have been removed from this bean but still appear on the
 * wire in older payloads. Each method's return type is the original Java type of the removed field;
 * each method must carry a {@link ForyVersion} annotation with {@code until} set, since removed
 * fields have a known end-of-life version.
 *
 * <p>Example:
 *
 * <pre>
 * &#64;Data
 * &#64;ForySchema(removedFields = MyBean.History.class)
 * public class MyBean {
 *   private String name;
 *
 *   interface History {
 *     &#64;ForyVersion(until = 3)
 *     List&lt;String&gt; tags();
 *
 *     &#64;ForyVersion(since = 2, until = 5)
 *     Map&lt;String, Long&gt; counters();
 *   }
 * }
 * </pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ForySchema {
  /**
   * A class whose accessor methods describe historically-present-but-now-removed fields. Default
   * {@code void.class} means there are no removed fields. The class is never instantiated; the
   * codec reads its method signatures and annotations.
   */
  Class<?> removedFields() default void.class;
}
