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

package org.apache.fory.json.scala;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds direct Scala 2 {@code scala.Enumeration.Value} occurrences to their singleton owners.
 *
 * <p>The selected classes must be Scala {@code Enumeration} singleton classes such as the class
 * literal produced by {@code classOf[Weekday.type]}. Child slots describe only the direct element,
 * content, key, or value occurrence of the annotated property.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
public @interface JsonEnumeration {
  /** Owner of a directly declared {@code Enumeration.Value}. */
  Class<?> value() default Void.class;

  /** Owner of a direct Scala collection or array element. */
  Class<?> element() default Void.class;

  /** Owner of direct {@code Option} content. */
  Class<?> content() default Void.class;

  /** Owner of a direct Scala map key. */
  Class<?> mapKey() default Void.class;

  /** Owner of a direct Scala map value. */
  Class<?> mapValue() default Void.class;
}
