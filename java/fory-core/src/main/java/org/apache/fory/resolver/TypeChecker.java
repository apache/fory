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

package org.apache.fory.resolver;

/** Checks whether a class name may be serialized or deserialized. */
public interface TypeChecker {
  /**
   * Check whether a class should be allowed. An array name received during deserialization is
   * passed as its complete JVM descriptor. If this checker is used by multiple resolvers, it must
   * be thread safe.
   *
   * @param resolver type resolver
   * @param className binary class name or complete JVM array descriptor
   * @return true if the class is allowed
   */
  boolean checkType(TypeResolver resolver, String className);
}
