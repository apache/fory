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

module org.apache.fory.core {
  requires java.logging;
  requires jdk.unsupported;

  requires static java.sql;
  requires static com.google.common;
  requires static org.slf4j;
  requires static jsr305;
  requires static jdk.incubator.vector;

  exports org.apache.fory;
  exports org.apache.fory.annotation;
  exports org.apache.fory.builder;
  exports org.apache.fory.codegen;
  exports org.apache.fory.collection;
  exports org.apache.fory.config;
  exports org.apache.fory.context;
  exports org.apache.fory.exception;
  exports org.apache.fory.io;
  exports org.apache.fory.logging;
  exports org.apache.fory.memory;
  exports org.apache.fory.meta;
  exports org.apache.fory.platform;
  exports org.apache.fory.pool;
  exports org.apache.fory.reflect;
  exports org.apache.fory.resolver;
  exports org.apache.fory.serializer;
  exports org.apache.fory.serializer.collection;
  exports org.apache.fory.serializer.converter;
  exports org.apache.fory.serializer.scala;
  exports org.apache.fory.serializer.struct;
  exports org.apache.fory.type;
  exports org.apache.fory.type.union;
  exports org.apache.fory.type.unsigned;
  exports org.apache.fory.util;
  exports org.apache.fory.util.function;
  exports org.apache.fory.util.record;
}
