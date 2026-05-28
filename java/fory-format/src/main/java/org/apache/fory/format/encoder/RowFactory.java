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

package org.apache.fory.format.encoder;

import org.apache.fory.format.row.binary.BinaryRow;

/**
 * Allocates fresh {@link BinaryRow} instances for a fixed schema. Obtained once per schema from
 * {@link Encoding#newRowFactory}, so any schema-derived layout (compact offsets, widths,
 * nullability) is computed a single time and reused by every {@link #newRow} call. The schema-
 * evolution decode path holds one factory per historical schema, giving it the same per-decode cost
 * as the current-schema path that reads through the writer's cached layout.
 */
@FunctionalInterface
interface RowFactory {
  BinaryRow newRow();
}
