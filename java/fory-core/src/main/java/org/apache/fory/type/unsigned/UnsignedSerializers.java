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

package org.apache.fory.type.unsigned;

import org.apache.fory.config.Config;
import org.apache.fory.resolver.TypeResolver;

/** Utility class for registering Uint wrapper type serializers. */
public class UnsignedSerializers {

  /** Register all Uint wrapper type serializers. */
  public static void registerSerializers(TypeResolver resolver) {
    Config config = resolver.getConfig();
    resolver.registerSerializer(
        UInt8.class, new org.apache.fory.serializer.UnsignedSerializers.UInt8Serializer(config));
    resolver.registerSerializer(
        UInt16.class, new org.apache.fory.serializer.UnsignedSerializers.UInt16Serializer(config));
    resolver.registerSerializer(
        UInt32.class, new org.apache.fory.serializer.UnsignedSerializers.UInt32Serializer(config));
    resolver.registerSerializer(
        UInt64.class, new org.apache.fory.serializer.UnsignedSerializers.UInt64Serializer(config));
  }
}
