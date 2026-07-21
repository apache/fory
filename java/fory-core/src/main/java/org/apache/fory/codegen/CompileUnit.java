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

package org.apache.fory.codegen;

import java.util.function.Supplier;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.util.Preconditions;
import org.apache.fory.util.StringUtils;

/** A CompileUnit corresponds to java file, which have a package, main class and code. */
public class CompileUnit {
  private static final Logger LOG = LoggerFactory.getLogger(CompileUnit.class);

  String pkg;
  String mainClassName;
  private String code;
  private Supplier<String> genCodeFunc;

  public CompileUnit(String pkg, String mainClassName, String code) {
    this.pkg = pkg;
    this.mainClassName = mainClassName;
    this.code = code;
  }

  public CompileUnit(String pkg, String mainClassName, Supplier<String> genCodeFunc) {
    this.pkg = pkg;
    this.mainClassName = mainClassName;
    this.genCodeFunc = genCodeFunc;
  }

  public String getCode() {
    if (code == null) {
      Preconditions.checkNotNull(genCodeFunc);
      long startTime = System.nanoTime();
      code = genCodeFunc.get();
      long durationMs = (System.nanoTime() - startTime) / 1000_000;
      LOG.info("Generate code for {} took {} ms.", getQualifiedClassName(), durationMs);
    }
    return code;
  }

  public String getQualifiedClassName() {
    if (StringUtils.isNotBlank(pkg)) {
      return pkg + "." + mainClassName;
    } else {
      return mainClassName;
    }
  }

  @Override
  public String toString() {
    return "CompileUnit{" + "pkg='" + pkg + '\'' + ", mainClassName='" + mainClassName + '\'' + '}';
  }
}
