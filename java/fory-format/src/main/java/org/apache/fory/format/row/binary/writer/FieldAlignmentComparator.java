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

package org.apache.fory.format.row.binary.writer;

import java.util.Comparator;
import org.apache.arrow.vector.types.pojo.Field;

class FieldAlignmentComparator implements Comparator<Field> {
  @Override
  public int compare(final Field f1, final Field f2) {
    final int f1Size = CompactBinaryRowWriter.fixedRegionSpaceFor(f1);
    final int f2Size = CompactBinaryRowWriter.fixedRegionSpaceFor(f2);
    final int f1Align = tryAlign(f1Size);
    final int f2Align = tryAlign(f2Size);
    final int alignCmp = Integer.compare(f1Align, f2Align);
    if (alignCmp != 0) {
      return alignCmp;
    }
    return -Integer.compare(f1Size, f2Size);
  }

  protected int tryAlign(final int size) {
    return size == 4 || size == 2 || (size & 7) == 0 ? 0 : 1;
  }
}
