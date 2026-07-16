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

package mixins;

import org.apache.fory.json.resolver.GeneratedJsonMixinMetadata;

/**
 * Exact processor-shaped metadata fixture for {@link MixinFixture.StateMixin}.
 *
 * <p>This module probe lives in {@code fory-json}, which cannot depend on the annotation processor:
 * the processor's tests already depend on {@code fory-json}, so the reverse test dependency would
 * create a reactor cycle. Processor tests verify generation; this fixture isolates construction of
 * generated metadata from a named-module package that is neither exported nor opened.
 */
public final class MixinFixture_d_StateMixin_ForyJsonMixin_mixins_x2e_MixinFixture_d_State
    implements GeneratedJsonMixinMetadata {
  public MixinFixture_d_StateMixin_ForyJsonMixin_mixins_x2e_MixinFixture_d_State() {}

  @Override
  public String targetName() {
    return "mixins.MixinFixture$State";
  }

  @Override
  public String mixinName() {
    return "mixins.MixinFixture$StateMixin";
  }

  @Override
  public boolean codecRequired() {
    return false;
  }

  @Override
  public boolean valueMetadata() {
    return false;
  }
}
