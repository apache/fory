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

package org.apache.fory.json;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;

import org.apache.fory.json.data.BeanProperties.BooleanBean;
import org.apache.fory.json.data.BeanProperties.ConflictingTypesBean;
import org.apache.fory.json.data.BeanProperties.DuplicateGetterBean;
import org.apache.fory.json.data.BeanProperties.FinalFieldBean;
import org.apache.fory.json.data.BeanProperties.GetterBean;
import org.apache.fory.json.data.BeanProperties.GetterOnlyBean;
import org.apache.fory.json.data.BeanProperties.InheritedChild;
import org.apache.fory.json.data.BeanProperties.InheritedParent;
import org.apache.fory.json.data.BeanProperties.InvalidAccessorBean;
import org.apache.fory.json.data.BeanProperties.MixedBean;
import org.apache.fory.json.data.BeanProperties.OverloadedSetterBean;
import org.apache.fory.json.data.BeanProperties.SetterBean;
import org.apache.fory.json.data.BeanProperties.SetterOnlyBean;
import org.testng.annotations.Test;

public class JsonPropertyTest extends ForyJsonTestModels {
  @Test
  public void writePrivateGetters() {
    ForyJson json = ForyJson.builder().build();
    assertEquals(json.toJson(new GetterBean()), "{\"id\":17,\"name\":\"getter-field\"}");
    assertGeneratedWhenSupported(json, GetterBean.class);
  }

  @Test
  public void readPrivateSetters() {
    ForyJson json = ForyJson.builder().build();
    SetterBean value = json.fromJson("{\"id\":4,\"name\":\"alpha\"}", SetterBean.class);
    assertEquals(SetterBean.id(value), 5);
    assertEquals(SetterBean.name(value), "set-alpha");
    assertEquals(SetterBean.setterCalls(value), 2);
    assertGeneratedWhenSupported(json, SetterBean.class);
  }

  @Test
  public void roundTripMixedProperties() {
    ForyJson json = ForyJson.builder().build();
    String text = "{\"count\":3,\"name\":\"mixed\",\"score\":5}";
    assertEquals(json.toJson(new MixedBean()), text);
    assertEquals(json.toJson(json.fromJson(text, MixedBean.class)), text);
    assertGeneratedWhenSupported(json, MixedBean.class);
  }

  @Test
  public void writeBooleanIsGetter() {
    ForyJson json = ForyJson.builder().build();
    assertEquals(json.toJson(new BooleanBean()), "{\"ready\":true}");
  }

  @Test
  public void getterOnlyWrites() {
    ForyJson json = ForyJson.builder().build();
    assertEquals(json.toJson(new GetterOnlyBean()), "{\"computed\":6}");
    GetterOnlyBean value = json.fromJson("{\"computed\":99}", GetterOnlyBean.class);
    assertEquals(json.toJson(value), "{\"computed\":6}");
  }

  @Test
  public void setterOnlyReads() {
    ForyJson json = ForyJson.builder().build();
    assertEquals(json.toJson(new SetterOnlyBean()), "{}");
    SetterOnlyBean value = json.fromJson("{\"secret\":\"alpha\"}", SetterOnlyBean.class);
    assertEquals(SetterOnlyBean.received(value), "set-alpha");
  }

  @Test
  public void fieldOnlyMode() {
    ForyJson json = ForyJson.builder().withFieldMode(true).build();
    assertEquals(json.toJson(new GetterBean()), "{\"id\":7,\"name\":\"field\"}");
    assertEquals(json.toJson(new GetterOnlyBean()), "{}");
    SetterOnlyBean value = json.fromJson("{\"secret\":\"alpha\"}", SetterOnlyBean.class);
    assertEquals(SetterOnlyBean.received(value), null);
  }

  @Test
  public void rejectPropertyConflicts() {
    ForyJson json = ForyJson.builder().build();
    assertThrows(ForyJsonException.class, () -> json.toJson(new DuplicateGetterBean()));
    assertThrows(
        ForyJsonException.class,
        () -> json.fromJson("{\"value\":\"alpha\"}", OverloadedSetterBean.class));
    assertThrows(ForyJsonException.class, () -> json.toJson(new ConflictingTypesBean()));
  }

  @Test
  public void inheritedProperties() {
    ForyJson json = ForyJson.builder().build();
    assertEquals(json.toJson(new InheritedChild()), "{\"id\":4,\"name\":\"child\"}");
    InheritedChild value = json.fromJson("{\"id\":7,\"name\":\"json\"}", InheritedChild.class);
    assertEquals(InheritedParent.id(value), 8);
    assertEquals(value.name, "json");
  }

  @Test
  public void ignoreInvalidAccessors() {
    ForyJson json = ForyJson.builder().build();
    assertEquals(json.toJson(new InvalidAccessorBean()), "{\"value\":\"field\"}");
  }

  @Test
  public void finalFieldsStayReadOnly() {
    ForyJson json = ForyJson.builder().build();
    assertEquals(json.toJson(new FinalFieldBean()), "{\"id\":1,\"name\":\"field\"}");
    FinalFieldBean value = json.fromJson("{\"id\":9,\"name\":\"json\"}", FinalFieldBean.class);
    assertEquals(value.id, 1);
    assertEquals(value.name, "json");
  }
}
