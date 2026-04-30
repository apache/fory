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

/** Test class with all unsigned integer scalar fields. */
public class UnsignedScalarFields {
  @UInt8Type public int u8;

  @UInt16Type public int u16;

  @UInt32Type(encoding = Int32Encoding.FIXED)
  public long u32;

  @UInt32Type public long u32Var;

  @UInt64Type(encoding = Int64Encoding.FIXED)
  public long u64;

  @UInt64Type(encoding = Int64Encoding.VARINT)
  public long u64Var;

  @UInt64Type(encoding = Int64Encoding.TAGGED)
  public long u64Tagged;
}
