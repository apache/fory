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

import org.apache.fory.json.annotation.JsonProperty;
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
import org.testng.annotations.Factory;
import org.testng.annotations.Test;

public class JsonPropertyTest extends ForyJsonTestModels {
  @Factory(dataProvider = "enableCodegen")
  public JsonPropertyTest(boolean codegen) {
    super(codegen);
  }

  @Test
  public void writePrivateGetters() {
    ForyJson json = newJson();
    assertEquals(json.toJson(new GetterBean()), "{\"id\":17,\"name\":\"getter-field\"}");
    assertGeneratedWhenSupported(json, GetterBean.class);
  }

  @Test
  public void readPrivateSetters() {
    ForyJson json = newJson();
    SetterBean value = json.fromJson("{\"id\":4,\"name\":\"alpha\"}", SetterBean.class);
    assertEquals(SetterBean.id(value), 5);
    assertEquals(SetterBean.name(value), "set-alpha");
    assertEquals(SetterBean.setterCalls(value), 2);
    assertGeneratedWhenSupported(json, SetterBean.class);
  }

  @Test
  public void roundTripMixedProperties() {
    ForyJson json = newJson();
    String text = "{\"count\":3,\"name\":\"mixed\",\"score\":5}";
    assertEquals(json.toJson(new MixedBean()), text);
    assertEquals(json.toJson(json.fromJson(text, MixedBean.class)), text);
    assertGeneratedWhenSupported(json, MixedBean.class);
  }

  @Test
  public void writeBooleanIsGetter() {
    ForyJson json = newJson();
    assertEquals(json.toJson(new BooleanBean()), "{\"ready\":true}");
  }

  @Test
  public void getterOnlyWrites() {
    ForyJson json = newJson();
    assertEquals(json.toJson(new GetterOnlyBean()), "{\"computed\":6}");
    GetterOnlyBean value = json.fromJson("{\"computed\":99}", GetterOnlyBean.class);
    assertEquals(json.toJson(value), "{\"computed\":6}");
  }

  @Test
  public void setterOnlyReads() {
    ForyJson json = newJson();
    assertEquals(json.toJson(new SetterOnlyBean()), "{}");
    SetterOnlyBean value = json.fromJson("{\"secret\":\"alpha\"}", SetterOnlyBean.class);
    assertEquals(SetterOnlyBean.received(value), "set-alpha");
  }

  @Test
  public void fieldOnlyMode() {
    ForyJson json = newJsonBuilder().withFieldMode(true).build();
    assertEquals(json.toJson(new GetterBean()), "{\"id\":7,\"name\":\"field\"}");
    assertEquals(json.toJson(new GetterOnlyBean()), "{}");
    SetterOnlyBean value = json.fromJson("{\"secret\":\"alpha\"}", SetterOnlyBean.class);
    assertEquals(SetterOnlyBean.received(value), null);
  }

  @Test
  public void rejectPropertyConflicts() {
    ForyJson json = newJson();
    assertThrows(ForyJsonException.class, () -> json.toJson(new DuplicateGetterBean()));
    assertThrows(
        ForyJsonException.class,
        () -> json.fromJson("{\"value\":\"alpha\"}", OverloadedSetterBean.class));
    assertThrows(ForyJsonException.class, () -> json.toJson(new ConflictingTypesBean()));
  }

  @Test
  public void inheritedProperties() {
    ForyJson json = newJson();
    assertEquals(json.toJson(new InheritedChild()), "{\"id\":4,\"name\":\"child\"}");
    InheritedChild value = json.fromJson("{\"id\":7,\"name\":\"json\"}", InheritedChild.class);
    assertEquals(InheritedParent.id(value), 8);
    assertEquals(value.name, "json");
  }

  @Test
  public void ignoreInvalidAccessors() {
    ForyJson json = newJson();
    assertEquals(json.toJson(new InvalidAccessorBean()), "{\"value\":\"field\"}");
  }

  @Test
  public void finalFieldsStayReadOnly() {
    ForyJson json = newJson();
    assertEquals(json.toJson(new FinalFieldBean()), "{\"id\":1,\"name\":\"field\"}");
    FinalFieldBean value = json.fromJson("{\"id\":9,\"name\":\"json\"}", FinalFieldBean.class);
    assertEquals(value.id, 1);
    assertEquals(value.name, "json");
  }

  @Test
  public void propertyAnnotationAndNaming() {
    ForyJson json =
        newJsonBuilder()
            .withPropertyNamingStrategy(PropertyNamingStrategy.LOWER_SNAKE_CASE)
            .build();
    AnnotatedProperties value = new AnnotatedProperties();
    assertEquals(
        json.toJson(value),
        "{\"always_null\":null,\"URL_value\":\"fixed\",\"user_name\":\"alice\"}");
    AnnotatedProperties decoded =
        json.fromJson(
            "{\"URL_value\":\"changed\",\"always_null\":null,\"user_name\":\"bob\"}",
            AnnotatedProperties.class);
    assertEquals(decoded.userName, "bob");
    assertEquals(decoded.fixedName, "changed");
  }

  @Test
  public void namingExamples() {
    ForyJson json =
        newJsonBuilder()
            .withPropertyNamingStrategy(PropertyNamingStrategy.LOWER_SNAKE_CASE)
            .build();
    assertEquals(
        json.toJson(new NamingExamples()),
        "{\"url2_value\":1,\"url_value\":1,\"already_snake\":1,\"user_url\":1,"
            + "\"v2_api_client\":1,\"version2_fa\":1,\"x1_value\":1}");
  }

  @Test
  public void rejectAnnotationConflicts() {
    ForyJson json = newJson();
    assertThrows(ForyJsonException.class, () -> json.toJson(new NameConflict()));
    assertThrows(ForyJsonException.class, () -> json.toJson(new IncludeConflict()));
    assertThrows(ForyJsonException.class, () -> json.toJson(new ReadOnlyInclude()));
    assertThrows(
        NullPointerException.class, () -> ForyJson.builder().withPropertyNamingStrategy(null));
    assertThrows(
        ForyJsonException.class,
        () ->
            newJsonBuilder()
                .withPropertyNamingStrategy(PropertyNamingStrategy.LOWER_SNAKE_CASE)
                .build()
                .toJson(new CanonicalNameConflict()));
    assertThrows(
        ForyJsonException.class,
        () -> newJsonBuilder().withFieldMode(true).build().toJson(new IneligibleAnnotation()));
  }

  public static final class AnnotatedProperties {
    @JsonProperty(include = JsonProperty.Include.ALWAYS)
    public String alwaysNull;

    @JsonProperty("URL_value")
    public String fixedName = "fixed";

    public String userName = "alice";
  }

  public static final class NamingExamples {
    public int URL2Value = 1;
    public int URLValue = 1;
    public int already_snake = 1;
    public int userURL = 1;
    public int v2APIClient = 1;
    public int version2FA = 1;
    public int x1Value = 1;
  }

  public static final class NameConflict {
    @JsonProperty("left")
    private String value;

    @JsonProperty("right")
    public String getValue() {
      return value;
    }
  }

  public static final class IncludeConflict {
    @JsonProperty(include = JsonProperty.Include.ALWAYS)
    private String value;

    @JsonProperty(include = JsonProperty.Include.NON_NULL)
    public String getValue() {
      return value;
    }
  }

  public static final class ReadOnlyInclude {
    @JsonProperty(include = JsonProperty.Include.ALWAYS)
    public void setValue(String value) {}
  }

  public static final class CanonicalNameConflict {
    public String userName;
    public String user_name;
  }

  public static final class IneligibleAnnotation {
    @JsonProperty("value")
    public transient String value;
  }
}
