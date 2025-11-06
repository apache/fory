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
import org.apache.fory.memory.MemoryBuffer;

/**
 * Serializer for class which
 * 1) has jdk `writeReplace`/`readResolve` method defined,
 * 2) is a final field of a class.
 * //TODO do we ned to write the flag REPLACED_NEW_TYPE/REPLACED_SAME_TYPE even for the final field?
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public class FinalFieldReplaceResolveSerializer extends ReplaceResolveSerializer {

  public FinalFieldReplaceResolveSerializer(Fory fory, Class type) {
    super(fory, type, true);
  }

  @Override
  protected void writeObject(MemoryBuffer buffer, Object value, MethodInfoCache jdkMethodInfoCache) {
    jdkMethodInfoCache.objectSerializer.write(buffer, value);
  }

  @Override
  protected Object readObject(MemoryBuffer buffer) {
    MethodInfoCache jdkMethodInfoCache = getMethodInfoCache();
    Object o = jdkMethodInfoCache.objectSerializer.read(buffer);
    ReplaceResolveInfo replaceResolveInfo = jdkMethodInfoCache.info;
    if (replaceResolveInfo.readResolveMethod == null) {
      return o;
    }
    return replaceResolveInfo.readResolve(o);
  }

  @Override
  public Object copy(Object originObj) {
    ReplaceResolveInfo replaceResolveInfo = jdkMethodInfoWriteCache.info;
    if (replaceResolveInfo.writeReplaceMethod == null) {
      return jdkMethodInfoWriteCache.objectSerializer.copy(originObj);
    }
    Object newObj = originObj;
    newObj = replaceResolveInfo.writeReplace(newObj);
    if (needToCopyRef) {
      fory.reference(originObj, newObj);
    }
    MethodInfoCache jdkMethodInfoCache = getMethodInfoCache();
    newObj = jdkMethodInfoCache.objectSerializer.copy(newObj);
    replaceResolveInfo = jdkMethodInfoCache.info;
    if (replaceResolveInfo.readResolveMethod != null) {
      newObj = replaceResolveInfo.readResolve(newObj);
    }
    return newObj;
  }

  private ReplaceResolveSerializer.MethodInfoCache getMethodInfoCache() {
    ReplaceResolveSerializer.MethodInfoCache jdkMethodInfoCache = classClassInfoHolderMap.get(type);
    if (jdkMethodInfoCache == null) {
      jdkMethodInfoCache = newJDKMethodInfoCache(type, fory);
      classClassInfoHolderMap.put(type, jdkMethodInfoCache);
    }
    return jdkMethodInfoCache;
  }
}
