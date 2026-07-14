/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

module org.apache.fory.graalvm.tests {
  requires org.apache.fory.core;

  // Fory-generated codecs are defined by the existing codegen class loader, so they access the
  // registered test models from its unnamed module.
  exports org.apache.fory.graalvm;
  exports org.apache.fory.graalvm.record;

  opens org.apache.fory.graalvm.record to
      org.apache.fory.core;
}
