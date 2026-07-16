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

package org.apache.fory.android;

import android.content.pm.ApplicationInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ForyAndroidInstrumentedTest {
  @Test
  public void plainJsonReflectionWithoutRules() {
    ApplicationInfo applicationInfo =
        InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationInfo();
    AndroidJsonScenarios.plainReflectionWithoutRules(
        (applicationInfo.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0);
  }

  @Test
  public void manualPlainJsonRules() {
    AndroidJsonScenarios.manualPlainRules();
  }

  @Test
  public void generatedPlainJsonRules() {
    AndroidJsonScenarios.generatedPlainRules();
  }

  @Test
  public void generatedRecordJson() {
    AndroidJsonScenarios.generatedRecord();
  }

  @Test
  public void generatedValueRecordJson() {
    AndroidJsonScenarios.generatedValueRecord();
  }

  @Test
  public void manualJsonCodecs() {
    AndroidJsonScenarios.manualCodecs();
  }

  @Test
  public void generatedJsonCodecs() {
    AndroidJsonScenarios.generatedCodecs();
  }

  @Test
  public void generatedJsonUnwrapped() {
    AndroidJsonScenarios.generatedUnwrapped();
  }

  @Test
  public void generatedJsonMixin() {
    AndroidJsonScenarios.generatedMixin();
  }

  @Test
  public void androidRuntimeDisablesCodegenAndUnsafeCopies() {
    AndroidForyRuntimeScenarios.androidRuntimeDisablesCodegenAndUnsafeCopies();
  }

  @Test
  public void structEnumCollectionAndMapRoundTrip() {
    AndroidForyRuntimeScenarios.structEnumCollectionAndMapRoundTrip();
  }

  @Test
  public void customSerializerExtRoundTrip() {
    AndroidForyRuntimeScenarios.customSerializerExtRoundTrip();
  }

  @Test
  public void xlangUnionRoundTrip() {
    AndroidForyRuntimeScenarios.xlangUnionRoundTrip();
  }

  @Test
  public void staticGeneratedSerializerSurvivesReleaseMinification() {
    AndroidForyRuntimeScenarios.staticGeneratedSerializerSurvivesReleaseMinification();
  }

  @Test
  public void jdkProxyCycleRoundTripAndCopy() {
    AndroidForyRuntimeScenarios.jdkProxyCycleRoundTripAndCopy();
  }

  @Test
  public void internalCollectionMapAndSetWrappersRoundTrip() {
    AndroidForyRuntimeScenarios.internalCollectionMapAndSetWrappersRoundTrip();
  }

  @Test
  public void byteBufferStreamChannelAndOutOfBandRoundTrip() throws Exception {
    AndroidForyRuntimeScenarios.byteBufferStreamChannelAndOutOfBandRoundTrip();
  }
}
