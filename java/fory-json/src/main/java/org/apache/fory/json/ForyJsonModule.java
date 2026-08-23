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

/** Installs one immutable set of JSON codecs while a {@link ForyJson} runtime is built. */
public interface ForyJsonModule {
  /**
   * Returns the deterministic semantic identity of this module configuration.
   *
   * <p>A configurable module must override this method and include every setting that can change
   * its installed JSON behavior. The key must not contain secrets or process-local state.
   */
  default String moduleKey() {
    return getClass().getName();
  }

  /** Installs this module's immutable codec registrations. */
  void install(ModuleContext context);
}
