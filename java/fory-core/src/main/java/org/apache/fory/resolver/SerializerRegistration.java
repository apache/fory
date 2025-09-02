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

import org.apache.fory.Fory;

/**
 * Interface for modules to register their serializers with Fory.
 * 
 * Modules can implement this interface and be discovered automatically
 * through service loading.
 */
public interface SerializerRegistration {

  /**
   * Register serializers with the given Fory instance if enabled.
   * Implementations should check configuration flags and only register
   * serializers when appropriate features are enabled.
   *
   * @param fory the Fory instance to register serializers with
   */
  void registerIfEnabled(Fory fory);

  /**
   * Check if this registration is applicable for the given Fory configuration.
   * This allows registrations to opt-out based on configuration without
   * being loaded at all.
   *
   * @param fory the Fory instance to check
   * @return true if this registration should be applied
   */
  default boolean isApplicable(Fory fory) {
    return true;
  }
}
