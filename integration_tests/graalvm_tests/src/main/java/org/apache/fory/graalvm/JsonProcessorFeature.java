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

package org.apache.fory.graalvm;

import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeClassInitialization;

/**
 * Verifies that real processor artifacts are available but excluded from the Native Image graph.
 */
public final class JsonProcessorFeature implements Feature {
  private static final String[] GENERATED_TYPES = {
    "org.apache.fory.graalvm.JsonProcessorExample_d_User_ForyJsonCodec",
    "org.apache.fory.graalvm.JsonProcessorExample_d_TextMessage_ForyJsonCodec",
    "org.apache.fory.graalvm.JsonProcessorExample_d_Message_ForyJsonSubTypes",
    "org.apache.fory.graalvm.JsonProcessorExample_d_ExternalMixin_ForyJsonMixin_"
        + "org_x2e_apache_x2e_fory_x2e_graalvm_x2e_JsonProcessorExample_d_External_ForyJsonCodec"
  };

  @Override
  public void beforeAnalysis(BeforeAnalysisAccess access) {
    for (String name : GENERATED_TYPES) {
      Class<?> type = access.findClassByName(name);
      if (type == null) {
        throw new IllegalStateException("Missing annotation-processor fixture: " + name);
      }
      // Also reject hosted construction of transient artifacts, such as subtype tables, which
      // could otherwise be discarded before the reachability assertion below.
      RuntimeClassInitialization.initializeAtRunTime(type);
    }
  }

  @Override
  public void afterAnalysis(AfterAnalysisAccess access) {
    for (String name : GENERATED_TYPES) {
      if (access.isReachable(access.findClassByName(name))) {
        throw new IllegalStateException(
            "Native Image retained an annotation-processor artifact: " + name);
      }
    }
  }
}
