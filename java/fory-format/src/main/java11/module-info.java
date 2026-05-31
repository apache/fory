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

module org.apache.fory.format {
  requires transitive org.apache.fory.core;

  requires static java.sql;
  requires static transitive org.apache.arrow.memory.core;
  requires static transitive org.apache.arrow.vector;

  exports org.apache.fory.format.encoder;
  exports org.apache.fory.format.row;
  exports org.apache.fory.format.row.binary;
  exports org.apache.fory.format.row.binary.writer;
  exports org.apache.fory.format.type;
  exports org.apache.fory.format.vectorized;
}
