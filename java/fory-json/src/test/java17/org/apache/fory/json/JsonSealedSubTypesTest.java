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

import java.util.Arrays;
import java.util.List;
import org.apache.fory.exception.InsecureException;
import org.apache.fory.json.annotation.JsonSubTypes;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class JsonSealedSubTypesTest {
  @DataProvider
  public static Object[][] codegen() {
    return new Object[][] {{Boolean.FALSE}, {Boolean.TRUE}};
  }

  @Test(dataProvider = "codegen")
  public void inferredClosure(boolean codegen) {
    ForyJson json =
        ForyJson.builder().withCodegen(codegen).withAsyncCompilation(false).build();
    List<InferredShape> values =
        Arrays.asList(
            new Circle(2),
            new Leaf(3),
            new OpenBranch(4),
            new ConcreteSealed(5),
            new ConcreteLeaf(6));
    String[] names = {"Circle", "Leaf", "OpenBranch", "ConcreteSealed", "ConcreteLeaf"};
    for (int i = 0; i < values.size(); i++) {
      InferredShape value = values.get(i);
      String text = json.toJson(value, InferredShape.class);
      assertEquals(text.contains("\"kind\":\"" + names[i] + "\""), true, text);
      InferredShape decoded = json.fromJson(text, InferredShape.class);
      assertEquals(decoded.getClass(), value.getClass());
      assertEquals(decoded.value(), value.value());
    }
    assertThrows(
        ForyJsonException.class,
        () -> json.toJson(new OpenDescendant(7), InferredShape.class));
  }

  @Test(dataProvider = "codegen")
  public void generatedTablePrecedesReflection(boolean codegen) {
    ForyJson json =
        ForyJson.builder().withCodegen(codegen).withAsyncCompilation(false).build();
    String text = json.toJson(new GeneratedLeaf(8), GeneratedShape.class);
    assertEquals(text, "{\"kind\":\"StableLeaf\",\"value\":8}");
    assertEquals(json.fromJson(text, GeneratedShape.class).getClass(), GeneratedLeaf.class);
  }

  @Test
  public void checkerNarrowsExactBranches() {
    ForyJson json =
        ForyJson.builder()
            .withCodegen(false)
            .withTypeChecker(
                (name, context) -> {
                  if (name.equals(ConcreteSealed.class.getName())) {
                    throw new InsecureException("rejected branch");
                  }
                  return true;
                })
            .build();
    assertThrows(
        ForyJsonException.class,
        () -> json.toJson(new ConcreteSealed(1), InferredShape.class));
    ConcreteLeaf leaf =
        (ConcreteLeaf)
            json.fromJson("{\"kind\":\"ConcreteLeaf\",\"value\":2}", InferredShape.class);
    assertEquals(leaf.value, 2);
  }

  @Test
  public void rejectEmptyEffectiveClosure() {
    ForyJson json =
        ForyJson.builder()
            .withCodegen(false)
            .withTypeChecker(
                (name, context) -> name.equals(InferredShape.class.getName()))
            .build();
    assertThrows(ForyJsonException.class, () -> json.fromJson("{}", InferredShape.class));
  }

  @Test
  public void rejectOpenAbstractBranch() {
    ForyJson json = ForyJson.builder().withCodegen(false).build();
    assertThrows(ForyJsonException.class, () -> json.fromJson("{}", InvalidShape.class));
  }

  @Test
  public void validateFullClosureBeforeChecker() {
    ForyJson json =
        ForyJson.builder()
            .withCodegen(false)
            .withTypeChecker(
                (name, context) ->
                    name.equals(DuplicateNames.class.getName())
                        || name.equals(LeftBranch.Entry.class.getName()))
            .build();
    assertThrows(ForyJsonException.class, () -> json.fromJson("{}", DuplicateNames.class));
  }

  @JsonSubTypes(property = "kind")
  public sealed interface InferredShape
      permits Circle, Branch, OpenBranch, ConcreteSealed {
    int value();
  }

  public static final class Circle implements InferredShape {
    public int value;

    public Circle() {}

    Circle(int value) {
      this.value = value;
    }

    @Override
    public int value() {
      return value;
    }
  }

  public abstract static sealed class Branch implements InferredShape permits Leaf {}

  public static final class Leaf extends Branch {
    public int value;

    public Leaf() {}

    Leaf(int value) {
      this.value = value;
    }

    @Override
    public int value() {
      return value;
    }
  }

  public static non-sealed class OpenBranch implements InferredShape {
    public int value;

    public OpenBranch() {}

    OpenBranch(int value) {
      this.value = value;
    }

    @Override
    public int value() {
      return value;
    }
  }

  public static final class OpenDescendant extends OpenBranch {
    public OpenDescendant() {}

    OpenDescendant(int value) {
      super(value);
    }
  }

  public static sealed class ConcreteSealed implements InferredShape permits ConcreteLeaf {
    public int value;

    public ConcreteSealed() {}

    ConcreteSealed(int value) {
      this.value = value;
    }

    @Override
    public int value() {
      return value;
    }
  }

  public static final class ConcreteLeaf extends ConcreteSealed {
    public ConcreteLeaf() {}

    ConcreteLeaf(int value) {
      super(value);
    }
  }

  @JsonSubTypes(property = "kind")
  public sealed interface GeneratedShape permits GeneratedLeaf {}

  public static final class GeneratedLeaf implements GeneratedShape {
    public int value;

    public GeneratedLeaf() {}

    GeneratedLeaf(int value) {
      this.value = value;
    }
  }

  @JsonSubTypes(property = "kind")
  public sealed interface InvalidShape permits OpenAbstractBranch {}

  public abstract static non-sealed class OpenAbstractBranch implements InvalidShape {}

  @JsonSubTypes(property = "kind")
  public sealed interface DuplicateNames permits LeftBranch, RightBranch {}

  public sealed interface LeftBranch extends DuplicateNames permits LeftBranch.Entry {
    public final class Entry implements LeftBranch {}
  }

  public sealed interface RightBranch extends DuplicateNames permits RightBranch.Entry {
    public final class Entry implements RightBranch {}
  }
}
