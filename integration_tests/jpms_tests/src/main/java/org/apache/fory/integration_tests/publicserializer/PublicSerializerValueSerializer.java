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

package org.apache.fory.integration_tests.publicserializer;

import org.apache.fory.context.ReadContext;
import org.apache.fory.context.WriteContext;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.Serializer;

public final class PublicSerializerValueSerializer extends Serializer<PublicSerializerValue> {
  public PublicSerializerValueSerializer(
      TypeResolver typeResolver, Class<PublicSerializerValue> type) {
    super(typeResolver.getConfig(), type);
  }

  @Override
  public void write(WriteContext writeContext, PublicSerializerValue value) {
    writeContext.getBuffer().writeInt32(value.value);
  }

  @Override
  public PublicSerializerValue read(ReadContext readContext) {
    return new PublicSerializerValue(readContext.getBuffer().readInt32());
  }
}
