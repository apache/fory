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
import org.apache.fory.memory.MemoryBuffer;

/** This class is used to track objects that have already been read or written. */
public interface RefResolver {
  /**
   * Write reference and tag for the obj if the obj has been written previously, write null/not-null
   * tag otherwise.
   *
   * @return true if no bytes need to be written for the object.
   */
  boolean writeRefOrNull(MemoryBuffer buffer, Object obj);

  /**
   * Write reference and tag for the obj if the obj has been written previously, otherwise do
   * nothing.
   *
   * @param buffer data buffer for writing flag.
   * @param obj object.
   * @return true if bytes need to be written for the object.
   */
  boolean writeRefValueFlag(MemoryBuffer buffer, Object obj);

  /**
   * Write null tag for the obj if the obj is null, otherwise do nothing.
   *
   * @param buffer data buffer for writing flag.
   * @param obj object.
   * @return true if no bytes need to be written for the object.
   */
  boolean writeNullFlag(MemoryBuffer buffer, Object obj);

  /**
   * Replace reference id of <code>original</code> with <code>newObject</code>.
   *
   * @param original original object
   * @param newObject new object
   */
  void replaceRef(Object original, Object newObject);

  /**
   * Returns {@link Fory#REF_FLAG} if a reference to a previously read object was read
   *
   * <p>Returns {@link Fory#NULL_FLAG} if the object is null.
   *
   * <p>Returns {@link Fory#REF_VALUE_FLAG} if the object is not null and reference tracking is not
   * enabled or the object is first read.
   */
  byte readRefOrNull(MemoryBuffer buffer);

  /**
   * Preserve a reference id, which is used by {@link #reference}/{@link #setReadObject} to set up
   * reference for object that is first deserialized.
   *
   * @return a reference id or -1 if reference is not enabled.
   */
  int preserveRefId();

  int preserveRefId(int refId);

  /**
   * Preserve and return a {@code refId} which is {@code >=} {@link Fory#NOT_NULL_VALUE_FLAG} if the
   * value is not null. If the value is referencable value, the {@code refId} will be {@link
   * #preserveRefId}.
   */
  int tryPreserveRefId(MemoryBuffer buffer);

  /** Returns last preserved reference id. */
  int lastPreservedRefId();

  /**
   * Call this method immediately after composited object such as object array/map/collection/bean
   * is created so that circular reference can be deserialized correctly.
   */
  void reference(Object object);

  /** Returns the object for the specified id. */
  Object getReadObject(int id);

  Object getReadObject();

  /**
   * Sets the id for an object that has been read.
   *
   * @param id The id from {@link #preserveRefId}.
   * @param object the object that has been read
   */
  void setReadObject(int id, Object object);

  void reset();

  void resetWrite();

  void resetRead();
}
