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

package org.apache.fory.resolver;

import java.rmi.server.UnicastRemoteObject;
import java.util.Set;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.exception.InsecureException;
import org.apache.fory.memory.Platform;
import org.testng.Assert;
import org.testng.annotations.Test;

public class DisallowedListTest extends ForyTestBase {

  @Test
  public void testDisallowedListNotEmpty() {
    Set<String> disallowedClasses = DisallowedList.getDisallowedClasses();
    Assert.assertFalse(disallowedClasses.isEmpty(), "Disallowed list should not be empty");
    Assert.assertTrue(
        disallowedClasses.size() > 200, "Disallowed list should contain many classes");
  }

  @Test
  public void testKnownDangerousClasses() {
    Set<String> disallowedClasses = DisallowedList.getDisallowedClasses();

    // Test some known dangerous classes are in the list
    Assert.assertTrue(disallowedClasses.contains("java.rmi.server.UnicastRemoteObject"));
    Assert.assertTrue(disallowedClasses.contains("com.sun.jndi.rmi.registry.BindingEnumeration"));
    Assert.assertTrue(disallowedClasses.contains("java.beans.Expression"));
    Assert.assertTrue(
        disallowedClasses.contains("org.apache.commons.collections.functors.InvokerTransformer"));
    Assert.assertTrue(disallowedClasses.contains("org.apache.xalan.xsltc.trax.TemplatesImpl"));
  }

  @Test
  public void testCheckHitDisallowedList() {
    // Hit the disallowed list.
    Assert.assertThrows(
        InsecureException.class,
        () -> DisallowedList.checkNotInDisallowedList("java.rmi.server.UnicastRemoteObject"));
    Assert.assertThrows(
        InsecureException.class,
        () ->
            DisallowedList.checkNotInDisallowedList(
                "com.sun.jndi.rmi.registry.BindingEnumeration"));
    Assert.assertThrows(
        InsecureException.class,
        () -> DisallowedList.checkNotInDisallowedList(java.beans.Expression.class.getName()));
    Assert.assertThrows(
        InsecureException.class,
        () -> DisallowedList.checkNotInDisallowedList(UnicastRemoteObject.class.getName()));

    // Not in the disallowed list.
    DisallowedList.checkNotInDisallowedList("java.util.HashMap");
  }

  @Test
  public void testSerializeDisallowedClass() {
    Fory[] allFory = new Fory[3];
    for (int i = 0; i < 3; i++) {
      boolean requireClassRegistration = i % 2 == 0;
      Fory fory =
          Fory.builder()
              .withXlang(false)
              .requireClassRegistration(requireClassRegistration)
              .build();
      if (requireClassRegistration) {
        // Registered or unregistered Classes should be subject to disallowed list restrictions.
        fory.register(UnicastRemoteObject.class);
      }
      allFory[i] = fory;
    }

    for (Fory fory : allFory) {
      Assert.assertThrows(
          InsecureException.class,
          () -> fory.serialize(Platform.newInstance(UnicastRemoteObject.class)));
      serDe(fory, new String[] {"a", "b"});
    }
  }
}
