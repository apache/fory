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

import type { TypeInfo } from "../typeInfo";

const skipReadActions = new WeakSet<TypeInfo>();

export function markCompatibleSkipRead(typeInfo: TypeInfo): TypeInfo {
  // A removed compatible field owns the whole declared codec tree. Propagate
  // the marker during regeneration so nested dynamic children use discard-only
  // type resolution without adding checks to ordinary generated readers.
  const pending = [typeInfo];
  for (let i = 0; i < pending.length; i++) {
    const current = pending[i];
    if (skipReadActions.has(current)) {
      continue;
    }
    skipReadActions.add(current);
    const options = current.options;
    if (options?.inner !== undefined) {
      pending.push(options.inner);
    }
    if (options?.key !== undefined) {
      pending.push(options.key);
    }
    if (options?.value !== undefined) {
      pending.push(options.value);
    }
  }
  return typeInfo;
}

export function shouldSkipCompatibleRead(typeInfo: TypeInfo): boolean {
  return skipReadActions.has(typeInfo);
}
