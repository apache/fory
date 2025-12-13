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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.fory.Fory;
import org.apache.fory.collection.IdentityObjectIntMap;
import org.apache.fory.collection.IntArray;
import org.apache.fory.collection.MapStatistics;
import org.apache.fory.collection.ObjectArray;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.util.Preconditions;

/** Resolving reference by tracking reference by an IdentityMap. */
// FIXME Will binding a separate reference resolver to every type have better performance?
//  If so, we can have sophisticated reference control for every type.
public final class MapRefResolver implements RefResolver {
  private static final boolean ENABLE_FORY_REF_PROFILING =
      "true".equalsIgnoreCase(System.getProperty("fory.enable_ref_profiling"));

  // Map clean will zero all key array elements, which is unnecessary for
  private static final int DEFAULT_MAP_CAPACITY = 4;
  private static final int DEFAULT_ARRAY_CAPACITY = 4;
  // use average size to amortise resize/clear cost.
  // exponential smoothing can't reflect overall reference size, thus not
  // suitable for amortization.
  // FIXME median may be better, but calculate median streaming is complicated.
  // FIXME is there a more accurate way to predict reference size?
  // maybe more complicated exponential smoothing?
  private long writeCounter;
  private long writeTotalObjectSize = 0;
  private long readCounter;
  private long readTotalObjectSize = 0;
  private final IdentityObjectIntMap<Object> writtenObjects;
  private final ObjectArray readObjects = new ObjectArray(DEFAULT_ARRAY_CAPACITY);
  private final IntArray readRefIds = new IntArray(DEFAULT_ARRAY_CAPACITY);

  // last read object which is not a reference
  private Object readObject;

  public MapRefResolver(float loadFactor) {
    writtenObjects = new IdentityObjectIntMap<>(DEFAULT_MAP_CAPACITY, loadFactor);
  }

  @Override
  public boolean writeRefOrNull(MemoryBuffer buffer, Object obj) {
    buffer.grow(10);
    if (obj == null) {
      buffer._unsafeWriteByte(Fory.NULL_FLAG);
      return true;
    } else {
      // The id should be consistent with `#nextReadRefId`
      int newWriteRefId = writtenObjects.size;
      int writtenRefId;
      if (ENABLE_FORY_REF_PROFILING) {
        // replaceRef is rare, just ignore it for profiling.
        writtenRefId = writtenObjects.profilingPutOrGet(obj, newWriteRefId);
      } else {
        writtenRefId = writtenObjects.putOrGet(obj, newWriteRefId);
      }
      if (writtenRefId >= 0) {
        // The obj has been written previously.
        buffer._unsafeWriteByte(Fory.REF_FLAG);
        buffer._unsafeWriteVarUint32(writtenRefId);
        return true;
      } else {
        // The object is being written for the first time.
        buffer._unsafeWriteByte(Fory.REF_VALUE_FLAG);
        return false;
      }
    }
  }

  @Override
  public boolean writeRefValueFlag(MemoryBuffer buffer, Object obj) {
    assert obj != null;
    buffer.grow(10);
    // The id should be consistent with `#nextReadRefId`
    int newWriteRefId = writtenObjects.size;
    int writtenRefId;
    if (ENABLE_FORY_REF_PROFILING) {
      // replaceRef is rare, just ignore it for profiling.
      writtenRefId = writtenObjects.profilingPutOrGet(obj, newWriteRefId);
    } else {
      writtenRefId = writtenObjects.putOrGet(obj, newWriteRefId);
    }
    if (writtenRefId >= 0) {
      // The obj has been written previously.
      buffer._unsafeWriteByte(Fory.REF_FLAG);
      buffer._unsafeWriteVarUint32(writtenRefId);
      return false;
    } else {
      // The object is being written for the first time.
      buffer._unsafeWriteByte(Fory.REF_VALUE_FLAG);
      return true;
    }
  }

  @Override
  public boolean writeNullFlag(MemoryBuffer buffer, Object obj) {
    if (obj == null) {
      buffer._unsafeWriteByte(Fory.NULL_FLAG);
      return true;
    }
    return false;
  }

  @Override
  public void replaceRef(Object original, Object newObject) {
    int newObjectId = writtenObjects.get(newObject, -1);
    Preconditions.checkArgument(newObjectId != -1);
    writtenObjects.put(original, newObjectId);
  }

  /**
   * Returns {@link Fory#NULL_FLAG} if the object is null and set {@link #readObject} to null.
   *
   * <p>Returns {@link Fory#NOT_NULL_VALUE_FLAG} if the object is not null and the object isn't a
   * referencable value and first read.
   *
   * <p>Returns {@link Fory#REF_FLAG} if a reference to a previously read object was read, which is
   * stored in {@link #readObject}.
   *
   * <p>Returns {@link Fory#REF_VALUE_FLAG} if the object is a referencable value and not null and
   * the object is first read.
   */
  @Override
  public byte readRefOrNull(MemoryBuffer buffer) {
    byte headFlag = buffer.readByte();
    if (headFlag == Fory.REF_FLAG) {
      // read reference id and get object from reference resolver
      int referenceId = buffer.readVarUint32Small14();
      readObject = getReadObject(referenceId);
    } else {
      readObject = null;
    }
    return headFlag;
  }

  @Override
  public int preserveRefId() {
    int nextReadRefId = readObjects.size();
    readObjects.add(null);
    readRefIds.add(nextReadRefId);
    return nextReadRefId;
  }

  @Override
  public int preserveRefId(int refId) {
    readRefIds.add(refId);
    return refId;
  }

  @Override
  public int tryPreserveRefId(MemoryBuffer buffer) {
    byte headFlag = buffer.readByte();
    if (headFlag == Fory.REF_FLAG) {
      // read reference id and get object from reference resolver
      readObject = getReadObject(buffer.readVarUint32Small14());
    } else {
      readObject = null;
      if (headFlag == Fory.REF_VALUE_FLAG) {
        return preserveRefId();
      }
    }
    // `headFlag` except `REF_FLAG` can be used as stub reference id because we use
    // `refId >= NOT_NULL_VALUE_FLAG` to read data.
    return headFlag;
  }

  @Override
  public int lastPreservedRefId() {
    return readRefIds.get(readRefIds.size - 1);
  }

  @Override
  public void reference(Object object) {
    int refId = readRefIds.pop();
    setReadObject(refId, object);
  }

  @Override
  public Object getReadObject(int id) {
    return readObjects.get(id);
  }

  @Override
  public Object getReadObject() {
    return readObject;
  }

  @Override
  public void setReadObject(int id, Object object) {
    if (id >= 0) {
      readObjects.set(id, object);
    }
  }

  public ObjectArray getReadObjects() {
    return readObjects;
  }

  @Override
  public void reset() {
    resetWrite();
    resetRead();
  }

  @Override
  public void resetWrite() {
    IdentityObjectIntMap<Object> writtenObjects = this.writtenObjects;
    // TODO handle outlier big size.
    long writeTotalObjectSize = this.writeTotalObjectSize + writtenObjects.size;
    long writeCounter = this.writeCounter + 1;
    if (writeCounter < 0 || writeTotalObjectSize < 0) { // overflow;
      writeCounter = 1;
      writeTotalObjectSize = writtenObjects.size;
    }
    this.writeCounter = writeCounter;
    this.writeTotalObjectSize = writeTotalObjectSize;
    int avg = (int) (writeTotalObjectSize / writeCounter);
    if (avg <= DEFAULT_MAP_CAPACITY) {
      avg = DEFAULT_MAP_CAPACITY;
    }
    writtenObjects.clearApproximate(avg);
  }

  @Override
  public void resetRead() {
    ObjectArray readObjects = this.readObjects;
    // TODO handle outlier big size.
    long readTotalObjectSize = this.readTotalObjectSize + readObjects.size();
    long readCounter = this.readCounter + 1;
    if (readCounter < 0 || readTotalObjectSize < 0) { // overflow;
      readCounter = 1;
      readTotalObjectSize = readObjects.size();
    }
    this.readCounter = readCounter;
    this.readTotalObjectSize = readTotalObjectSize;
    int avg = (int) (readTotalObjectSize / readCounter);
    if (avg <= DEFAULT_ARRAY_CAPACITY) {
      avg = DEFAULT_ARRAY_CAPACITY;
    }
    readObjects.clearApproximate(avg);
    readRefIds.clear();
    readObject = null;
  }

  public static class RefStatistics {
    LinkedHashMap<Class<?>, Integer> refTypeSummary;
    int refCount;
    MapStatistics mapStatistics;

    public RefStatistics(
        LinkedHashMap<Class<?>, Integer> refTypeSummary, MapStatistics mapStatistics) {
      this.refTypeSummary = refTypeSummary;
      this.mapStatistics = mapStatistics;
      refCount = refTypeSummary.values().stream().reduce(0, Integer::sum, Integer::sum);
    }

    @Override
    public String toString() {
      return "RefStatistics{"
          + "referenceTypeSummary="
          + refTypeSummary
          + ", referenceCount="
          + refCount
          + ", mapProbeStatistics="
          + mapStatistics
          + '}';
    }
  }

  public RefStatistics referenceStatistics() {
    return new RefStatistics(referenceTypeSummary(), writtenObjects.getAndResetStatistics());
  }

  /** Returns a map which indicates counter for reference object type. */
  public LinkedHashMap<Class<?>, Integer> referenceTypeSummary() {
    Map<Class<?>, Integer> typeCounter = new HashMap<>();
    writtenObjects.forEach(
        (k, v) -> typeCounter.compute(k.getClass(), (key, value) -> value == null ? 1 : value + 1));
    List<Map.Entry<Class<?>, Integer>> entries = new ArrayList<>(typeCounter.entrySet());
    entries.sort(
        (o1, o2) -> {
          if (o1.getValue().equals(o2.getValue())) {
            return o1.getKey().getName().compareTo(o2.getKey().getName());
          } else {
            return o2.getValue() - o1.getValue();
          }
        });
    LinkedHashMap<Class<?>, Integer> result = new LinkedHashMap<>(entries.size());
    entries.forEach(e -> result.put(e.getKey(), e.getValue()));
    return result;
  }
}
