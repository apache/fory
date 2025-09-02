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

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import javax.annotation.concurrent.ThreadSafe;
import org.apache.fory.Fory;
import org.apache.fory.exception.InsecureException;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.serializer.Serializer;

/** White/black list based class checker. */
@ThreadSafe
public class AllowListChecker implements ClassChecker {
  private static final Logger LOG = LoggerFactory.getLogger(AllowListChecker.class);

  public enum CheckLevel {
    /** Disable serialize check for all classes. */
    DISABLE,

    /** Only deny danger classes, warn if other classes are not in allow list. */
    WARN,

    /** Only allow classes in allow list, deny if other classes are not in allow list. */
    STRICT
  }

  private volatile CheckLevel checkLevel;
  private final Set<String> allowList;
  private final Set<String> allowListPrefix;
  private final Set<String> disallowList;
  private final Set<String> disallowListPrefix;
  private final transient WeakHashMap<ClassResolver, Boolean> listeners;
  private final transient ReadWriteLock lock;

  public AllowListChecker() {
    this(CheckLevel.WARN);
  }

  public AllowListChecker(CheckLevel checkLevel) {
    this.checkLevel = checkLevel;
    allowList = new HashSet<>();
    allowListPrefix = new HashSet<>();
    disallowList = new HashSet<>();
    disallowListPrefix = new HashSet<>();
    lock = new ReentrantReadWriteLock();
    listeners = new WeakHashMap<>();
  }

  public CheckLevel getCheckLevel() {
    return checkLevel;
  }

  public void setCheckLevel(CheckLevel checkLevel) {
    this.checkLevel = checkLevel;
  }

  @Override
  public boolean checkClass(ClassResolver classResolver, String className) {
    try {
      lock.readLock().lock();
      return check(className);
    } finally {
      lock.readLock().unlock();
    }
  }

  private boolean check(String className) {
    switch (checkLevel) {
      case DISABLE:
        return true;
      case WARN:
        if (containsPrefix(disallowList, disallowListPrefix, className)) {
          throw new InsecureException(
              String.format("Class %s is forbidden for serialization.", className));
        }
        if (!containsPrefix(allowList, allowListPrefix, className)) {
          LOG.warn(
              "Class {} not in allow list, please check whether objects of this class "
                  + "are allowed for serialization.",
              className);
        }
        return true;
      case STRICT:
        if (containsPrefix(disallowList, disallowListPrefix, className)) {
          throw new InsecureException(
              String.format("Class %s is forbidden for serialization.", className));
        }
        if (!containsPrefix(allowList, allowListPrefix, className)) {
          throw new InsecureException(
              String.format(
                  "Class %s isn't in allow list for serialization. If this class is allowed for "
                      + "serialization, please add it to allow list by AllowListChecker#addAllowClass",
                  className));
        }
        return true;
      default:
        throw new UnsupportedOperationException("Unsupported check level " + checkLevel);
    }
  }

  static boolean containsPrefix(Set<String> set, Set<String> prefixSet, String className) {
    if (set.contains(className)) {
      return true;
    }
    for (String prefix : prefixSet) {
      if (className.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Add class to allow list.
   *
   * @param classNameOrPrefix class name or name prefix ends with *.
   */
  public void allowClass(String classNameOrPrefix) {
    try {
      lock.writeLock().lock();
      allow(classNameOrPrefix);
    } finally {
      lock.writeLock().unlock();
    }
  }

  /**
   * Add classes to allow list.
   *
   * @param classNamesOrPrefixes class names or name prefixes ends with *.
   */
  public void allowClasses(Collection<String> classNamesOrPrefixes) {
    try {
      lock.writeLock().lock();
      for (String namesOrPrefix : classNamesOrPrefixes) {
        allow(namesOrPrefix);
      }
    } finally {
      lock.writeLock().unlock();
    }
  }

  private void allow(String classNameOrPrefix) {
    if (classNameOrPrefix.endsWith("*")) {
      allowListPrefix.add(classNameOrPrefix.substring(0, classNameOrPrefix.length() - 1));
    } else {
      allowList.add(classNameOrPrefix);
    }
  }

  /**
   * Add class to disallow list.
   *
   * @param classNameOrPrefix class name or class name prefix ends with *.
   */
  public void disallowClass(String classNameOrPrefix) {
    try {
      lock.writeLock().lock();
      disallow(classNameOrPrefix);
    } finally {
      lock.writeLock().unlock();
    }
  }

  /**
   * Add classes to disallow list.
   *
   * @param classNamesOrPrefixes class names or name prefixes ends with *.
   */
  public void disallowClasses(Collection<String> classNamesOrPrefixes) {
    try {
      lock.writeLock().lock();
      for (String prefix : classNamesOrPrefixes) {
        disallow(prefix);
      }
    } finally {
      lock.writeLock().unlock();
    }
  }

  private void disallow(String classNameOrPrefix) {
    if (classNameOrPrefix.endsWith("*")) {
      String prefix = classNameOrPrefix.substring(0, classNameOrPrefix.length() - 1);
      disallowListPrefix.add(prefix);
      for (ClassResolver classResolver : listeners.keySet()) {
        try {
          classResolver.getFory().getJITContext().lock();
          // clear serializer may throw NullPointerException for field serialization.
          classResolver.setSerializers(prefix, DisallowSerializer.class);
        } finally {
          classResolver.getFory().getJITContext().unlock();
        }
      }
    } else {
      disallowList.add(classNameOrPrefix);
      for (ClassResolver classResolver : listeners.keySet()) {
        try {
          classResolver.getFory().getJITContext().lock();
          // clear serializer may throw NullPointerException for field serialization.
          classResolver.setSerializer(classNameOrPrefix, DisallowSerializer.class);
        } finally {
          classResolver.getFory().getJITContext().unlock();
        }
      }
    }
  }

  /**
   * Add listener to in response to disallow list. So if object of a class is serialized before,
   * future serialization will be refused.
   */
  public void addListener(ClassResolver classResolver) {
    try {
      lock.writeLock().lock();
      listeners.put(classResolver, true);
    } finally {
      lock.writeLock().unlock();
    }
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static class DisallowSerializer extends Serializer {

    public DisallowSerializer(Fory fory, Class type) {
      super(fory, type);
    }

    @Override
    public void write(MemoryBuffer buffer, Object value) {
      throw new InsecureException(String.format("Class %s not allowed for serialization.", type));
    }

    @Override
    public Object read(MemoryBuffer buffer) {
      throw new InsecureException(String.format("Class %s not allowed for serialization.", type));
    }
  }
}
