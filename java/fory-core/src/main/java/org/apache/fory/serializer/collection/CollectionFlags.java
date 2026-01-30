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

/** bitmap flags for collection serialization. */
public class CollectionFlags {
  /** Whether track elements ref. */
  public static final int TRACKING_REF = 0b1;

  /** Whether collection has null elements. */
  public static final int HAS_NULL = 0b10;

  /** Whether collection elements type is declared type. */
  public static final int IS_DECL_ELEMENT_TYPE = 0b100;

  /** Whether collection elements type are same. */
  public static final int IS_SAME_TYPE = 0b1000;

  public static final int DECL_SAME_TYPE_TRACKING_REF =
      IS_DECL_ELEMENT_TYPE | IS_SAME_TYPE | TRACKING_REF;

  public static final int DECL_SAME_TYPE_NOT_TRACKING_REF = IS_DECL_ELEMENT_TYPE | IS_SAME_TYPE;

  public static final int DECL_SAME_TYPE_HAS_NULL = IS_DECL_ELEMENT_TYPE | IS_SAME_TYPE | HAS_NULL;

  public static final int DECL_SAME_TYPE_NOT_HAS_NULL = IS_DECL_ELEMENT_TYPE | IS_SAME_TYPE;
}
