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

package org.apache.fory.type;

/**
 * Field type that only the descriptor warm-up of {@code DescriptorTest.WarmInner} loads. It is a
 * top-level class because on JDK 8, {@link java.lang.reflect.Field#getAnnotatedType()} on a field
 * of type {@code WarmInner} looks up its enclosing class and loads other member classes of {@code
 * DescriptorTest}. A member class leaf would then load on the test thread, not on a warm-up thread.
 */
public class DescriptorWarmLeaf {}
