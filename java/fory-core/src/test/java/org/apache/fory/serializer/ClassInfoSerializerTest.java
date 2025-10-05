package org.apache.fory.serializer;


import lombok.Data;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.config.Language;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.resolver.ClassInfo;
import org.apache.fory.resolver.ClassInfoSerializer;
import org.apache.fory.resolver.ClassResolver;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.Serializable;
import java.util.UUID;

public class ClassInfoSerializerTest extends ForyTestBase {

    @Data
    public static class JavaCustomClass implements Serializable {
        public String name;
        public transient int age;
        public static final UUID UUID = new UUID(123L, 456L);

        public JavaCustomClass(String name, int age) {
            this.name = name;
            this.age = age;
        }

        private void writeObject(java.io.ObjectOutputStream s) throws IOException {
            s.defaultWriteObject();
            s.writeInt(age);
        }

        private void readObject(java.io.ObjectInputStream s) throws Exception {
            s.defaultReadObject();
            this.age = s.readInt();
        }
    }

    @Data
    public static class CustomClass {
        public String name;
        public static final UUID UUID = new UUID(444L, 555L);

        public CustomClass(String name) {
            this.name = name;
        }
    }


    static class Mapper implements ClassInfoSerializer {

        @Override
        public void writeClassInfo(ClassResolver classResolver, MemoryBuffer buffer, ClassInfo classInfo) {
            UUID uuid;

            if(classInfo.getCls().equals(JavaCustomClass.class))
                uuid = JavaCustomClass.UUID;
            else if(classInfo.getCls().equals(CustomClass.class))
                uuid = CustomClass.UUID;
            else
                throw new RuntimeException("unknown class");

            buffer.writeVarUint64(uuid.getLeastSignificantBits());
            buffer.writeVarUint64(uuid.getMostSignificantBits());
        }

        @Override
        public ClassInfo readClassInfo(ClassResolver classResolver, MemoryBuffer buffer) {
            long least = buffer.readVarUint64();
            long most = buffer.readVarUint64();
            UUID uuid = new UUID(most, least);

            if(uuid.equals(JavaCustomClass.UUID))
                return classResolver.getClassInfo(JavaCustomClass.class);
            else if(uuid.equals(CustomClass.UUID))
                return classResolver.getClassInfo(CustomClass.class);
            else
                throw new RuntimeException("unknown class");
        }
    }

    @Test
    public void testJavaObject() {
        Fory fory =
                Fory.builder()
                        .withLanguage(Language.JAVA)
                        .withRefTracking(false)
                        .requireClassRegistration(false)
                        .build();

        fory.getClassResolver().setClassMapper(new Mapper());

        JavaCustomClass deser = serDe(fory, new JavaCustomClass("bob", 10));
        Assert.assertEquals(deser.name, "bob");
        Assert.assertEquals(deser.age, 10);
    }


    @Test
    public void testObject() {
        Fory fory = Fory.builder()
                .requireClassRegistration(false)
                .build();

        fory.getClassResolver().setClassMapper(new Mapper());

        CustomClass deser = serDe(fory, new CustomClass("mike"));
        Assert.assertEquals(deser.name, "mike");
    }

}

