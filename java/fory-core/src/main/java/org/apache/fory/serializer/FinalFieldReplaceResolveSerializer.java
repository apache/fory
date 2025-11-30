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

package org.apache.fory.serializer;

import org.apache.fory.Fory;
import org.apache.fory.config.CompatibleMode;
import org.apache.fory.memory.MemoryBuffer;

/**
 * Serializer for class which: - has jdk `writeReplace`/`readResolve` method defined, - is a final
 * class. Main advantage of this serializer is that it does not write class name to the payload.
 * NOTE: this serializer is used only with {@link CompatibleMode#SCHEMA_CONSISTENT} mode.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public class FinalFieldReplaceResolveSerializer extends ReplaceResolveSerializer {

  public FinalFieldReplaceResolveSerializer(Fory fory, Class type) {
    // the serializer does not write class info
    // and does not set itself for the provided class
    // see checks in ReplaceResolveSerializer constructor
    super(fory, type, true, false);
  }

  @Override
  protected void writeObject(
      MemoryBuffer buffer, Object value, MethodInfoCache jdkMethodInfoCache) {
    jdkMethodInfoCache.objectSerializer.write(buffer, value);
  }

  @Override
  protected Object readObject(MemoryBuffer buffer) {
    MethodInfoCache jdkMethodInfoCache = getMethodInfoCache(type);
    Object o = jdkMethodInfoCache.objectSerializer.read(buffer);
    ReplaceResolveInfo replaceResolveInfo = jdkMethodInfoCache.info;
    if (replaceResolveInfo.readResolveMethod == null) {
      return o;
    }
    return replaceResolveInfo.readResolve(o);
  }
}
