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

package org.apache.fory.serializer.shim;

import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.fory.Fory;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.util.ExceptionUtils;

@SuppressWarnings({"unchecked", "rawtypes"})
public class ProtobufDispatcher {
  private static final Logger LOG = LoggerFactory.getLogger(ProtobufDispatcher.class);

  private static Class<?> pbByteStringClass;
  private static Class<? extends Serializer> pbByteStringSerializerClass;
  private static Class<?> pbMessageClass;
  private static Class<? extends Serializer> pbMessageSerializerClass;
  private static final AtomicBoolean warningLogged = new AtomicBoolean(false);

  static {
    try {
      pbMessageClass = ReflectionUtils.loadClass("com.google.protobuf.Message");
      pbByteStringClass = ReflectionUtils.loadClass("com.google.protobuf.ByteString");
    } catch (Exception e) {
      ExceptionUtils.ignore(e);
    }
    try {
      pbMessageSerializerClass =
          (Class<? extends Serializer>)
              ReflectionUtils.loadClass(
                  Fory.class.getPackage().getName() + ".extension.serializer.ProtobufSerializer");
      pbByteStringSerializerClass =
          (Class<? extends Serializer>)
              ReflectionUtils.loadClass(
                  Fory.class.getPackage().getName() + ".extension.serializer.ByteStringSerializer");
    } catch (Exception e) {
      ExceptionUtils.ignore(e);
    }
  }

  public static Class<? extends Serializer> getSerializerClass(Class<?> type) {
    if (pbMessageClass == null) {
      return null;
    }
    if (pbMessageSerializerClass == null) {
      if (type.getName().startsWith("com.google.protobuf") && !warningLogged.getAndSet(true)) {
        LOG.warn("ProtobufSerializer not loaded, please add fory-extensions dependency.");
      }
      return null;
    }
    if (pbMessageClass.isAssignableFrom(type)) {
      return pbMessageSerializerClass;
    }
    if (pbByteStringClass.isAssignableFrom(type)) {
      return pbByteStringSerializerClass;
    }
    return null;
  }
}
