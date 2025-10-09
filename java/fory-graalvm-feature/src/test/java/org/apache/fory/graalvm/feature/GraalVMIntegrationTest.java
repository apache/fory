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

package org.apache.fory.graalvm.feature;

import org.apache.fory.Fory;
import org.apache.fory.config.Language;
import org.apache.fory.reflect.ObjectCreator;
import org.apache.fory.reflect.ObjectCreators;


public class GraalVMIntegrationTest {

    // Test classes for ObjectCreator functionality
    public static class SimpleClass {
        private String name;
        private int value;

        public SimpleClass() {}

        public SimpleClass(String name, int value) {
            this.name = name;
            this.value = value;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getValue() { return value; }
        public void setValue(int value) { this.value = value; }

        @Override
        public String toString() {
            return "SimpleClass{name='" + name + "', value=" + value + "}";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof SimpleClass)) return false;
            SimpleClass that = (SimpleClass) o;
            return value == that.value && 
                   (name != null ? name.equals(that.name) : that.name == null);
        }
    }

    public static abstract class AbstractParent {
        // No no-arg constructor - forces use of special ObjectCreator
    }

    public static class ProblematicChild extends AbstractParent {
        private String data;

        public ProblematicChild(String data) {
            this.data = data;
        }

        public String getData() { return data; }
        public void setData(String data) { this.data = data; }

        @Override
        public String toString() {
            return "ProblematicChild{data='" + data + "'}";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ProblematicChild)) return false;
            ProblematicChild that = (ProblematicChild) o;
            return data != null ? data.equals(that.data) : that.data == null;
        }
    }

    public static class AnotherProblematicChild extends AbstractParent {
        private int number;

        public AnotherProblematicChild(int number) {
            this.number = number;
        }

        public int getNumber() { return number; }
        public void setNumber(int number) { this.number = number; }

        @Override
        public String toString() {
            return "AnotherProblematicChild{number=" + number + "}";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof AnotherProblematicChild)) return false;
            AnotherProblematicChild that = (AnotherProblematicChild) o;
            return number == that.number;
        }
    }

    public static void main(String[] args) {
        System.out.println("=== GraalVM Integration Test ===");
        System.out.println("Java version: " + System.getProperty("java.version"));
        System.out.println("Java vendor: " + System.getProperty("java.vendor"));
        System.out.println("Runtime: " + System.getProperty("java.runtime.name"));
        System.out.println();

        GraalVMIntegrationTest test = new GraalVMIntegrationTest();
        
        try {
            // Test 1: Basic Fory serialization
            test.testBasicSerialization();
            
            // Test 2: GraalVM Feature functionality
            test.testGraalVMFeature();
            
            // Test 3: ObjectCreator tests
            test.testObjectCreators();
            
            // Test 4: Problematic class handling
            test.testProblematicClasses();
            
            // Test 5: ReflectionFactory availability
            test.testReflectionFactoryAvailability();
            
            System.out.println("\n✅ All tests passed successfully!");
            
        } catch (Exception e) {
            System.err.println("\n❌ Test failed:");
            e.printStackTrace();
            System.exit(1);
        }
    }

    public void testBasicSerialization() {
        System.out.println("--- Test 1: Basic Fory Serialization ---");
        
        Fory fory = Fory.builder()
                .withLanguage(Language.JAVA)
                .requireClassRegistration(true)
                .build();

        fory.register(SimpleClass.class);

        SimpleClass original = new SimpleClass("test", 42);
        System.out.println("Original: " + original);

        byte[] serialized = fory.serialize(original);
        System.out.println("Serialized " + serialized.length + " bytes");

        SimpleClass deserialized = (SimpleClass) fory.deserialize(serialized);
        System.out.println("Deserialized: " + deserialized);

        if (!original.equals(deserialized)) {
            throw new RuntimeException("Serialization test failed: objects not equal");
        }
        
        System.out.println("✓ Basic serialization test passed\n");
    }

    public void testGraalVMFeature() {
        System.out.println("--- Test 2: GraalVM Feature ---");
        
        ForyGraalVMFeature feature = new ForyGraalVMFeature();
        System.out.println("Feature description: " + feature.getDescription());

        Fory fory = Fory.builder()
                .withLanguage(Language.JAVA)
                .requireClassRegistration(true)
                .build();

        int initialSize = Fory.getRegisteredClasses().size();
        System.out.println("Initial registered classes count: " + initialSize);
        
        fory.register(SimpleClass.class);
        
        int newSize = Fory.getRegisteredClasses().size();
        System.out.println("After registration count: " + newSize);
        System.out.println("Contains SimpleClass: " + Fory.getRegisteredClasses().contains(SimpleClass.class));
        
        if (!Fory.getRegisteredClasses().contains(SimpleClass.class)) {
            throw new RuntimeException("Class registration tracking failed");
        }
        
        // Note: SimpleClass may have been registered in previous test
        if (newSize < initialSize) {
            throw new RuntimeException("Registered classes count decreased unexpectedly");
        }
        
        System.out.println("✓ GraalVM Feature test passed\n");
    }

    public void testObjectCreators() {
        System.out.println("--- Test 3: ObjectCreator Tests ---");
        
        // Test simple class with no-arg constructor
        ObjectCreator<SimpleClass> simpleCreator = ObjectCreators.getObjectCreator(SimpleClass.class);
        System.out.println("SimpleClass ObjectCreator: " + simpleCreator.getClass().getSimpleName());
        
        SimpleClass simpleInstance = simpleCreator.newInstance();
        if (simpleInstance == null) {
            throw new RuntimeException("SimpleClass instance creation failed");
        }
        
        if (!simpleInstance.getClass().equals(SimpleClass.class)) {
            throw new RuntimeException("SimpleClass instance type incorrect: " + simpleInstance.getClass());
        }
        
        System.out.println("✓ SimpleClass ObjectCreator test passed");
        
        // Test problematic class detection
        boolean isProblematic = ObjectCreators.isProblematicForCreation(ProblematicChild.class);
        System.out.println("ProblematicChild is problematic: " + isProblematic);
        
        if (!isProblematic) {
            throw new RuntimeException("ProblematicChild should be detected as problematic");
        }
        
        System.out.println("✓ ObjectCreator tests passed\n");
    }

    public void testProblematicClasses() {
        System.out.println("--- Test 4: Problematic Class Handling ---");
        
        // Test ProblematicChild
        ObjectCreator<ProblematicChild> problematicCreator = ObjectCreators.getObjectCreator(ProblematicChild.class);
        System.out.println("ProblematicChild ObjectCreator: " + problematicCreator.getClass().getSimpleName());
        
        ProblematicChild problematicInstance = problematicCreator.newInstance();
        if (problematicInstance == null) {
            throw new RuntimeException("ProblematicChild instance creation failed");
        }
        
        if (!problematicInstance.getClass().equals(ProblematicChild.class)) {
            throw new RuntimeException("ProblematicChild instance type incorrect: " + problematicInstance.getClass());
        }
        
        System.out.println("✓ ProblematicChild creation successful: " + problematicInstance);
        
        // Test AnotherProblematicChild
        ObjectCreator<AnotherProblematicChild> anotherCreator = ObjectCreators.getObjectCreator(AnotherProblematicChild.class);
        System.out.println("AnotherProblematicChild ObjectCreator: " + anotherCreator.getClass().getSimpleName());
        
        AnotherProblematicChild anotherInstance = anotherCreator.newInstance();
        if (anotherInstance == null) {
            throw new RuntimeException("AnotherProblematicChild instance creation failed");
        }
        
        if (!anotherInstance.getClass().equals(AnotherProblematicChild.class)) {
            throw new RuntimeException("AnotherProblematicChild instance type incorrect: " + anotherInstance.getClass());
        }
        
        System.out.println("✓ AnotherProblematicChild creation successful: " + anotherInstance);
        System.out.println("✓ Problematic class handling test passed\n");
    }

    public void testReflectionFactoryAvailability() {
        System.out.println("--- Test 5: ReflectionFactory Availability ---");
        
        try {
            Class<?> reflectionFactoryClass = Class.forName("jdk.internal.reflect.ReflectionFactory");
            System.out.println("✓ ReflectionFactory class found: " + reflectionFactoryClass.getName());
            
            java.lang.reflect.Method getFactoryMethod = reflectionFactoryClass.getMethod("getReflectionFactory");
            Object factory = getFactoryMethod.invoke(null);
            System.out.println("✓ ReflectionFactory instance obtained: " + factory);
            
        } catch (ClassNotFoundException e) {
            System.out.println("⚠ ReflectionFactory not found - may indicate GraalVM Native Image: " + e.getMessage());
        } catch (Exception e) {
            System.out.println("⚠ Error accessing ReflectionFactory: " + e.getMessage());
        }
        
        System.out.println("✓ ReflectionFactory availability test completed\n");
    }
}