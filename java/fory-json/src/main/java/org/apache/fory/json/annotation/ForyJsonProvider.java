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

package org.apache.fory.json.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.apache.fory.json.ForyJson;

/**
 * Supplies Fory JSON configurations for GraalVM Native Image hosted code generation.
 *
 * <p>Annotate a reachable public concrete class with a public no-argument constructor. Every
 * effective public, non-static, zero-argument instance method whose exact return type is {@link
 * ForyJson} is invoked once while the native image is built. This includes inherited superclass
 * methods and public interface default methods. Fory JSON always generates reachable models for the
 * default configuration, then adds generated codecs for every returned configuration. Models
 * without a matching generated codec use the interpreted codec. The provider package does not need
 * to be exported or opened to Fory.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ForyJsonProvider {}
