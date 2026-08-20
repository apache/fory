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

package org.apache.fory.json.data;

public final class BeanProperties {
  private BeanProperties() {}

  public static final class GetterBean {
    private int id = 7;
    private String name = "field";

    public int getId() {
      return id + 10;
    }

    public String getName() {
      return "getter-" + name;
    }
  }

  public static final class SetterBean {
    private int id;
    private String name;
    private int setterCalls;

    public void setId(int id) {
      this.id = id + 1;
      setterCalls++;
    }

    public void setName(String name) {
      this.name = "set-" + name;
      setterCalls++;
    }

    public static int id(SetterBean value) {
      return value.id;
    }

    public static String name(SetterBean value) {
      return value.name;
    }

    public static int setterCalls(SetterBean value) {
      return value.setterCalls;
    }
  }

  public static final class MixedBean {
    public int count = 3;
    private String name = "mixed";
    private int score = 5;

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public int getScore() {
      return score;
    }

    public void setScore(int score) {
      this.score = score;
    }
  }

  public static final class BooleanBean {
    private boolean ready = true;

    public boolean isReady() {
      return ready;
    }
  }

  public static final class GetterOnlyBean {
    private transient int seed = 3;

    public int getComputed() {
      return seed * 2;
    }
  }

  public static final class SetterOnlyBean {
    private transient String received;

    public void setSecret(String secret) {
      received = "set-" + secret;
    }

    public static String received(SetterOnlyBean value) {
      return value.received;
    }
  }

  public static final class DuplicateGetterBean {
    public boolean getActive() {
      return true;
    }

    public boolean isActive() {
      return true;
    }
  }

  public static final class OverloadedSetterBean {
    public void setValue(int value) {}

    public void setValue(String value) {}
  }

  public static final class ConflictingTypesBean {
    private String value = "text";

    public String getValue() {
      return value;
    }

    public void setValue(int value) {}
  }

  public static class InheritedParent {
    private int id = 4;

    public int getId() {
      return id;
    }

    public void setId(int id) {
      this.id = id + 1;
    }

    public static int id(InheritedParent value) {
      return value.id;
    }
  }

  public static final class InheritedChild extends InheritedParent {
    public String name = "child";
  }

  public static final class InvalidAccessorBean {
    public String value = "field";

    public static String getStaticValue() {
      return "static";
    }

    public String getWithArg(String ignored) {
      return ignored;
    }

    public void getVoidValue() {}

    public String setWrongReturn(String value) {
      return value;
    }

    public void setNoValue() {}
  }

  public static final class FinalFieldBean {
    public final int id = 1;
    public String name = "field";
  }
}
