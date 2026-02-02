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

package org.apache.fory.serializer.collection;

import org.apache.fory.Fory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.resolver.ClassInfoHolder;
import org.apache.fory.resolver.RefMode;
import org.apache.fory.serializer.Serializer;

// This polymorphic interface has cost, do not expose it as a public class
// If it's used in other packages in fory, duplicate it in those packages.
@SuppressWarnings({"rawtypes", "unchecked"})
// noinspection Duplicates
interface SerializationBinding {
  <T> void writeRef(MemoryBuffer buffer, T obj);

  <T> void writeRef(MemoryBuffer buffer, T obj, Serializer<T> serializer);

  void writeRef(MemoryBuffer buffer, Object obj, ClassInfoHolder classInfoHolder);

  void writeNonRef(MemoryBuffer buffer, Object elem);

  void write(MemoryBuffer buffer, Serializer serializer, Object value);

  Object read(MemoryBuffer buffer, Serializer serializer);

  <T> T readRef(MemoryBuffer buffer, Serializer<T> serializer);

  Object readRef(MemoryBuffer buffer, ClassInfoHolder classInfoHolder);

  Object readRef(MemoryBuffer buffer);

  Object readNonRef(MemoryBuffer buffer);

  Object readNonRef(MemoryBuffer buffer, ClassInfoHolder classInfoHolder);

  static SerializationBinding createBinding(Fory fory) {
    if (fory.isCrossLanguage()) {
      return new XlangSerializationBinding(fory);
    } else {
      return new JavaSerializationBinding(fory);
    }
  }

  final class JavaSerializationBinding implements SerializationBinding {
    private final Fory fory;

    JavaSerializationBinding(Fory fory) {
      this.fory = fory;
    }

    @Override
    public <T> void writeRef(MemoryBuffer buffer, T obj) {
      fory.writeRef(buffer, obj);
    }

    @Override
    public <T> void writeRef(MemoryBuffer buffer, T obj, Serializer<T> serializer) {
      serializer.write(buffer, RefMode.TRACKING, obj);
    }

    @Override
    public void writeRef(MemoryBuffer buffer, Object obj, ClassInfoHolder classInfoHolder) {
      fory.writeRef(buffer, obj, classInfoHolder);
    }

    @Override
    public <T> T readRef(MemoryBuffer buffer, Serializer<T> serializer) {
      // must go with ref mode approach, since inner fread may invoke `reference()`
      // Preserve a dummy ref ID so serializer.xread() can call reference() safely.
      return serializer.read(buffer, RefMode.TRACKING);
    }

    @Override
    public Object readRef(MemoryBuffer buffer, ClassInfoHolder classInfoHolder) {
      return fory.readRef(buffer, classInfoHolder);
    }

    @Override
    public Object readRef(MemoryBuffer buffer) {
      return fory.readRef(buffer);
    }

    @Override
    public Object readNonRef(MemoryBuffer buffer) {
      return fory.readNonRef(buffer);
    }

    @Override
    public Object readNonRef(MemoryBuffer buffer, ClassInfoHolder classInfoHolder) {
      return fory.readNonRef(buffer, classInfoHolder);
    }

    @Override
    public void write(MemoryBuffer buffer, Serializer serializer, Object value) {
      serializer.write(buffer, value);
    }

    @Override
    public Object read(MemoryBuffer buffer, Serializer serializer) {
      // must go with ref mode approach, since inner fread may invoke `reference()`
      // Preserve a dummy ref ID so serializer.xread() can call reference() safely.
      return serializer.read(buffer, RefMode.NONE);
    }

    @Override
    public void writeNonRef(MemoryBuffer buffer, Object elem) {
      fory.writeNonRef(buffer, elem);
    }
  }

  final class XlangSerializationBinding implements SerializationBinding {

    private final Fory fory;

    XlangSerializationBinding(Fory fory) {
      this.fory = fory;
    }

    @Override
    public <T> void writeRef(MemoryBuffer buffer, T obj) {
      fory.xwriteRef(buffer, obj);
    }

    @Override
    public <T> void writeRef(MemoryBuffer buffer, T obj, Serializer<T> serializer) {
      serializer.xwrite(buffer, RefMode.TRACKING, obj);
    }

    @Override
    public void writeRef(MemoryBuffer buffer, Object obj, ClassInfoHolder classInfoHolder) {
      fory.xwriteRef(buffer, obj);
    }

    @Override
    public <T> T readRef(MemoryBuffer buffer, Serializer<T> serializer) {
      // must go with ref mode approach, since inner fread may invoke `reference()`
      // Preserve a dummy ref ID so serializer.xread() can call reference() safely.
      return serializer.xread(buffer, RefMode.TRACKING);
    }

    @Override
    public Object readRef(MemoryBuffer buffer, ClassInfoHolder classInfoHolder) {
      return fory.xreadRef(buffer);
    }

    @Override
    public Object readRef(MemoryBuffer buffer) {
      return fory.xreadRef(buffer);
    }

    @Override
    public Object readNonRef(MemoryBuffer buffer) {
      return fory.xreadNonRef(buffer);
    }

    @Override
    public Object readNonRef(MemoryBuffer buffer, ClassInfoHolder classInfoHolder) {
      return fory.xreadNonRef(buffer, classInfoHolder);
    }

    @Override
    public void write(MemoryBuffer buffer, Serializer serializer, Object value) {
      serializer.xwrite(buffer, value);
    }

    @Override
    public Object read(MemoryBuffer buffer, Serializer serializer) {
      // must go with ref mode approach, since inner fread may invoke `reference()`
      // Preserve a dummy ref ID so serializer.xread() can call reference() safely.
      return serializer.xread(buffer, RefMode.NONE);
    }

    @Override
    public void writeNonRef(MemoryBuffer buffer, Object elem) {
      fory.xwriteNonRef(buffer, elem);
    }
  }
}
