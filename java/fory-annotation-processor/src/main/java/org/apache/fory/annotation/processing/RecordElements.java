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

package org.apache.fory.annotation.processing;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;

/** Java 8-linkable access to record declarations in newer compiler models. */
final class RecordElements {
  private RecordElements() {}

  static boolean isRecord(TypeElement type) {
    return type.getKind().name().equals("RECORD");
  }

  static List<Element> components(TypeElement type) {
    Object value;
    try {
      Method method = TypeElement.class.getMethod("getRecordComponents");
      value = method.invoke(type);
    } catch (NoSuchMethodException e) {
      throw new ProcessingException(
          "Record processing requires a compiler with record component support", type, e);
    } catch (ReflectiveOperationException e) {
      throw new ProcessingException("Failed to inspect record components", type, e);
    }
    if (!(value instanceof List<?>)) {
      throw new ProcessingException("Unexpected record component model for " + type, type);
    }
    List<?> values = (List<?>) value;
    List<Element> result = new ArrayList<>(values.size());
    for (Object component : values) {
      if (!(component instanceof Element)) {
        throw new ProcessingException("Unexpected record component model for " + type, type);
      }
      result.add((Element) component);
    }
    return result;
  }
}
