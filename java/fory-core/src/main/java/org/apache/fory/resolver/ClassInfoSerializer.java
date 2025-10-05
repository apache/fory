package org.apache.fory.resolver;

import org.apache.fory.memory.MemoryBuffer;

/**
 * ClassInfoSerializer provides a mechanism to customize the recording of the ClassInfo
 * default implementation uses a short value, however when the class registration needs to be customized
 * this can be set of the ClassResolver to handle storing the ClassInfo is a customized manner
 */
public interface ClassInfoSerializer {
    /**
     * Writes the provided ClassInfo into the buffer in a customized manner which is consistent
     * with the readClassInfo method
     */
    void writeClassInfo(ClassResolver classResolver, MemoryBuffer buffer, ClassInfo classInfo);

    /**
     * Reads the ClassInfo from the provided buffer in a manner which is consistent with writeClassInfo
     */
    ClassInfo readClassInfo(ClassResolver classResolver, MemoryBuffer buffer);
}
