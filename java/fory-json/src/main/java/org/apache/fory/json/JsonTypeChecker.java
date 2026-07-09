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

package org.apache.fory.json;

import org.apache.fory.exception.InsecureException;

/**
 * Checks whether Fory JSON may create or reuse metadata for a Java class name.
 *
 * <p>Implementations must be thread-safe. Fory JSON invokes the checker from shared resolver and
 * codec setup paths and does not synchronize around user checker state. The class name is passed as
 * a string so future class-name materialization paths can run policy before loading a class.
 *
 * <p>Classes served by default built-in exact JSON codecs do not invoke the configured checker
 * unless a custom codec is registered for the same class.
 */
@FunctionalInterface
public interface JsonTypeChecker {
  /**
   * Returns whether {@code className} is allowed for Fory JSON serialization or parsing.
   *
   * @param className full Java class name
   * @param context checker context owned by the {@link ForyJson} instance
   * @return true when the class name is allowed
   * @throws InsecureException if the implementation rejects the class name by throwing instead of
   *     returning false
   */
  boolean checkType(String className, JsonTypeCheckContext context);
}
