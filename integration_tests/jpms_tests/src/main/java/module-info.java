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

module org.apache.fory.integration_tests {
  requires org.apache.fory.core;
  requires org.apache.fory.format;
  requires org.apache.fory.test.core;

  // we can't really test any classes from this module because it only contains test-classes
  requires org.apache.fory.test.suite;

  exports org.apache.fory.integration_tests.model;
  exports org.apache.fory.integration_tests.publicserializer;
}
