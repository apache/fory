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

package org.apache.fory.data;

import org.apache.fory.annotation.UInt16Type;
import org.apache.fory.annotation.UInt32Type;
import org.apache.fory.annotation.UInt64Type;
import org.apache.fory.annotation.UInt8Type;
import org.apache.fory.config.Int32Encoding;
import org.apache.fory.config.Int64Encoding;

/** Test class with both unsigned integer scalar and array fields. */
public class AllUnsignedFields {
  public @UInt8Type int u8;

  public @UInt16Type int u16;

  public @UInt32Type(encoding = Int32Encoding.FIXED) long u32;

  public @UInt64Type(encoding = Int64Encoding.FIXED) long u64;

  public @UInt8Type byte[] u8Array;

  public @UInt16Type short[] u16Array;

  public @UInt32Type int[] u32Array;

  public @UInt64Type long[] u64Array;
}
