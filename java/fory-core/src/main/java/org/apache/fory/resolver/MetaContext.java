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

import org.apache.fory.collection.IdentityObjectIntMap;
import org.apache.fory.collection.ObjectArray;

/**
 * Context for sharing class meta across multiple serialization. Class name, field name and field
 * type will be shared between different serialization.
 */
public class MetaContext {
  /** Classes which has sent definitions to peer. */
  public final IdentityObjectIntMap<Class<?>> classMap = new IdentityObjectIntMap<>(1, 0.5f);

  /** TypeInfos read from peer for reference lookup during deserialization. */
  public final ObjectArray<TypeInfo> readTypeInfos = new ObjectArray<>();
}
