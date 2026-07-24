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

package org.apache.fory.logging;

import java.lang.reflect.Member;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class LogOnceState {
  static final Object[] NO_ARGS = new Object[0];

  private final Set<LogKey> logged =
      Collections.newSetFromMap(new ConcurrentHashMap<LogKey, Boolean>());

  boolean shouldLog(int level, String msg, Object[] args) {
    return logged.add(new LogKey(level, msg, args));
  }

  private static final class LogKey {
    private final int level;
    private final String msg;
    private final Object[] args;
    private final int hashCode;

    private LogKey(int level, String msg, Object[] args) {
      this.level = level;
      this.msg = msg;
      this.args = normalizeArgs(args);
      int result = level;
      result = 31 * result + (msg == null ? 0 : msg.hashCode());
      result = 31 * result + Arrays.deepHashCode(this.args);
      hashCode = result;
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) {
        return true;
      }
      if (!(obj instanceof LogKey)) {
        return false;
      }
      LogKey other = (LogKey) obj;
      return level == other.level && msgEquals(other.msg) && Arrays.deepEquals(args, other.args);
    }

    @Override
    public int hashCode() {
      return hashCode;
    }

    private boolean msgEquals(String otherMsg) {
      return msg == null ? otherMsg == null : msg.equals(otherMsg);
    }
  }

  private static Object[] normalizeArgs(Object[] args) {
    if (args == null || args.length == 0) {
      return NO_ARGS;
    }
    Object[] normalized = new Object[args.length];
    for (int i = 0; i < args.length; i++) {
      normalized[i] = normalizeArg(args[i]);
    }
    return normalized;
  }

  private static Object normalizeArg(Object arg) {
    if (arg instanceof Class) {
      Class<?> cls = (Class<?>) arg;
      return classArg(cls);
    }
    if (arg instanceof Member) {
      Member member = (Member) arg;
      return new MemberArg(member.toString(), classArg(member.getDeclaringClass()));
    }
    if (arg instanceof Object[]) {
      return normalizeArgs((Object[]) arg);
    }
    return arg;
  }

  private static ClassArg classArg(Class<?> cls) {
    // Once keys live as long as the logger. Store class identity data, not the Class object, so
    // one-time logs do not pin user classes or their classloaders.
    return new ClassArg(cls.getName(), System.identityHashCode(cls));
  }

  private static final class ClassArg {
    private final String name;
    private final int identityHash;

    private ClassArg(String name, int identityHash) {
      this.name = name;
      this.identityHash = identityHash;
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) {
        return true;
      }
      if (!(obj instanceof ClassArg)) {
        return false;
      }
      ClassArg other = (ClassArg) obj;
      return identityHash == other.identityHash && name.equals(other.name);
    }

    @Override
    public int hashCode() {
      return 31 * identityHash + name.hashCode();
    }
  }

  private static final class MemberArg {
    private final String member;
    private final ClassArg declaringClass;

    private MemberArg(String member, ClassArg declaringClass) {
      this.member = member;
      this.declaringClass = declaringClass;
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) {
        return true;
      }
      if (!(obj instanceof MemberArg)) {
        return false;
      }
      MemberArg other = (MemberArg) obj;
      return member.equals(other.member) && declaringClass.equals(other.declaringClass);
    }

    @Override
    public int hashCode() {
      return 31 * declaringClass.hashCode() + member.hashCode();
    }
  }
}
