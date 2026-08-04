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

package org.apache.fory.graalvm.closed;

import org.apache.fory.graalvm.ForyJsonExample.CodegenProbeCodec;
import org.apache.fory.graalvm.ForyJsonExample.CodegenProbeValue;
import org.apache.fory.graalvm.ForyJsonExample.CodegenRejectingClassLoader;
import org.apache.fory.graalvm.ForyJsonExample.CoreCompileStateMixin;
import org.apache.fory.graalvm.ForyJsonExample.EmptyMixin;
import org.apache.fory.graalvm.ForyJsonExample.InheritedJsonConfig;
import org.apache.fory.json.ForyJson;
import org.apache.fory.json.PropertyNamingStrategy;
import org.apache.fory.json.annotation.ForyJsonProvider;

/** Provider whose constructor and inherited method need no package export or open. */
@ForyJsonProvider
public final class ClosedJsonConfigs extends ClosedJsonConfigParent implements InheritedJsonConfig {
  public ClosedJsonConfigs() {}
}

class ClosedJsonConfigParent {
  public ForyJson aRestrictedConfiguration() {
    return ForyJson.builder()
        .writeNullFields(true)
        .withPropertyNamingStrategy(PropertyNamingStrategy.SNAKE_CASE)
        .registerCodec(CodegenProbeValue.class, new CodegenProbeCodec())
        .registerMixin(CoreCompileStateMixin.class)
        .registerMixin(EmptyMixin.class)
        .withClassLoader(new CodegenRejectingClassLoader())
        .withTypeChecker((className, context) -> false)
        .build();
  }

  public ForyJson generatedConfiguration() {
    return ForyJson.builder()
        .writeNullFields(true)
        .withPropertyNamingStrategy(PropertyNamingStrategy.SNAKE_CASE)
        .registerCodec(CodegenProbeValue.class, new CodegenProbeCodec())
        .registerMixin(CoreCompileStateMixin.class)
        .registerMixin(EmptyMixin.class)
        .build();
  }

  public ForyJson ignoredOverload(boolean ignored) {
    throw new AssertionError("Provider overload must not be invoked");
  }

  public static ForyJson ignoredStaticMethod() {
    throw new AssertionError("Static provider helper must not be invoked");
  }
}
