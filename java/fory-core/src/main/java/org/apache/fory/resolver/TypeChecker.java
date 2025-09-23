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

/**
 * Check whether class or objects of class should be serialized. If class checker will be invoked by
 * multiple {@link ClassResolver}, class checker should be thread safe.
 */
public interface TypeChecker {
  /**
   * Check whether class should be allowed for serialization.
   *
   * @param resolver type resolver
   * @param className full name of class
   * @return true if class is allowed for serialization.
   */
  boolean checkType(TypeResolver resolver, String className);
}
