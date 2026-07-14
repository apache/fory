#!/usr/bin/env bash

# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
JAVA_ROOT="$ROOT/java"
VERSION="$(mvn -q -B -f "$JAVA_ROOT/pom.xml" help:evaluate -Dexpression=project.version -DforceStdout)"
JAVA_HOME="${JAVA_HOME:-$(
  java -XshowSettings:properties -version 2>&1 \
    | awk -F= '/java.home =/ { gsub(/^ +| +$/, "", $2); print $2; exit }'
)}"
JAVA_MAJOR="$(
  java -version 2>&1 \
    | awk -F '"' '/version/ {print $2; exit}' \
    | awk -F. '{ if ($1 == "1") print $2; else print $1 }'
)"

if [[ "$JAVA_MAJOR" -lt 11 ]]; then
  echo "Skipping jlink smoke test on JDK $JAVA_MAJOR; fory-format is Java 11+."
  exit 0
fi
if [[ ! -d "$JAVA_HOME/jmods" ]]; then
  echo "Cannot find JDK modules under JAVA_HOME=$JAVA_HOME; jlink smoke requires a JDK." >&2
  exit 1
fi

artifact_jar() {
  local artifact="$1"
  local module="$2"
  local target_jar="$JAVA_ROOT/$module/target/$artifact-$VERSION.jar"
  local repo_jar="$HOME/.m2/repository/org/apache/fory/$artifact/$VERSION/$artifact-$VERSION.jar"
  if [[ -f "$target_jar" ]]; then
    echo "$target_jar"
  elif [[ -f "$repo_jar" ]]; then
    echo "$repo_jar"
  else
    echo "Cannot find $artifact jar; run java/fory-core and java/fory-format package/install first." >&2
    exit 1
  fi
}

CORE_JAR="$(artifact_jar fory-core fory-core)"
FORMAT_JAR="$(artifact_jar fory-format fory-format)"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/fory-jpms-jlink.XXXXXX")"
trap 'rm -rf "$WORK_DIR"' EXIT

require_jar_entry() {
  local jar_file="$1"
  local entry="$2"
  if ! jar tf "$jar_file" | grep -qx "$entry"; then
    echo "Missing $entry in $jar_file" >&2
    exit 1
  fi
}

reject_jar_entry() {
  local jar_file="$1"
  local entry="$2"
  if jar tf "$jar_file" | grep -qx "$entry"; then
    echo "Unexpected $entry in $jar_file" >&2
    exit 1
  fi
}

require_jar_entry "$CORE_JAR" "META-INF/versions/9/module-info.class"
reject_jar_entry "$CORE_JAR" "module-info.class"
require_jar_entry "$CORE_JAR" "org/apache/fory/serializer/CompressedArraySerializers.class"
require_jar_entry "$CORE_JAR" "org/apache/fory/util/ArrayCompressionUtils.class"
require_jar_entry "$CORE_JAR" "org/apache/fory/util/PrimitiveArrayCompressionType.class"
require_jar_entry "$FORMAT_JAR" "META-INF/versions/11/module-info.class"
reject_jar_entry "$FORMAT_JAR" "module-info.class"
if [[ "$JAVA_MAJOR" -ge 17 ]]; then
  jar --validate --file "$CORE_JAR"
fi

if [[ "$JAVA_MAJOR" -ge 16 ]]; then
  require_jar_entry "$CORE_JAR" "META-INF/versions/16/module-info.class"
  require_jar_entry "$CORE_JAR" "META-INF/versions/16/org/apache/fory/util/ArrayCompressionUtils.class"
  reject_jar_entry "$CORE_JAR" "META-INF/versions/16/org/apache/fory/serializer/CompressedArraySerializers.class"
  reject_jar_entry "$CORE_JAR" "META-INF/versions/16/org/apache/fory/util/PrimitiveArrayCompressionType.class"
  jar --file "$CORE_JAR" --describe-module --release 16 | grep -q "requires jdk.incubator.vector static"
fi

jar --file "$CORE_JAR" --describe-module --release 9 | grep -q "requires java.sql static"
jar --file "$CORE_JAR" --describe-module --release 9 | grep -q "requires com.google.common static"
jar --file "$FORMAT_JAR" --describe-module --release 11 | grep -q "requires java.sql static"
jar --file "$FORMAT_JAR" --describe-module --release 11 | grep -q "requires org.apache.arrow.vector static transitive"
jar --file "$FORMAT_JAR" --describe-module --release 11 | grep -q "requires org.apache.arrow.memory.core static transitive"

DEPS_FILE="$WORK_DIR/format-deps.txt"
mvn -q -B -f "$JAVA_ROOT/pom.xml" -pl fory-format dependency:list \
  -DincludeScope=compile \
  -DoutputAbsoluteArtifactFilename=true \
  -DexcludeTransitive=false \
  -DoutputFile="$DEPS_FILE" \
  -Dstyle.color=never

COMPILE_MODULE_PATH="$WORK_DIR/compile-module-path.txt"
awk -F: '/:jar:/ {
  path = $NF
  sub(/ .*/, "", path)
  if (path ~ /^\// && path ~ /\.jar$/ && path !~ /\/org\/apache\/fory\/fory-core\//) {
    print path
  }
}' "$DEPS_FILE" > "$COMPILE_MODULE_PATH"

mkdir -p "$WORK_DIR/src/jpms.smoke/org/apache/fory/jpms" "$WORK_DIR/mods"
cat > "$WORK_DIR/src/jpms.smoke/module-info.java" <<'EOF'
module jpms.smoke {
  requires org.apache.fory.core;
  requires org.apache.fory.format;
}
EOF
cat > "$WORK_DIR/src/jpms.smoke/org/apache/fory/jpms/Smoke.java" <<'EOF'
package org.apache.fory.jpms;

import org.apache.fory.Fory;

public final class Smoke {
  public static void main(String[] args) throws Exception {
    Fory.builder().build();
    Class.forName("org.apache.fory.format.encoder.Encoders");
    System.out.println("ok");
  }
}
EOF

JOINED_COMPILE_MODULE_PATH="$CORE_JAR:$FORMAT_JAR"
if [[ -s "$COMPILE_MODULE_PATH" ]]; then
  JOINED_COMPILE_MODULE_PATH="$JOINED_COMPILE_MODULE_PATH:$(paste -sd: "$COMPILE_MODULE_PATH")"
fi

javac \
  --module-path "$JOINED_COMPILE_MODULE_PATH" \
  -d "$WORK_DIR/mods" \
  --module-source-path "$WORK_DIR/src" \
  -m jpms.smoke

jlink \
  --module-path "$JAVA_HOME/jmods:$CORE_JAR:$FORMAT_JAR:$WORK_DIR/mods" \
  --add-modules jpms.smoke \
  --output "$WORK_DIR/image"

# JDK25+ zero-Unsafe mode uses Fory as a named module in this image.
"$WORK_DIR/image/bin/java" \
  --add-opens=java.base/java.lang.invoke=org.apache.fory.core \
  -m jpms.smoke/org.apache.fory.jpms.Smoke \
  | grep -qx "ok"

IMAGE_MODULES="$("$WORK_DIR/image/bin/java" --list-modules)"
echo "$IMAGE_MODULES" | grep -q "^org.apache.fory.core"
echo "$IMAGE_MODULES" | grep -q "^org.apache.fory.format"
if echo "$IMAGE_MODULES" \
  | grep -Eq "^(java\.sql|com\.google|jsr305|org\.apache\.arrow|org\.slf4j|jdk\.incubator\.vector)"; then
  echo "Optional static modules leaked into the minimal jlink image:" >&2
  echo "$IMAGE_MODULES" \
    | grep -E "^(java\.sql|com\.google|jsr305|org\.apache\.arrow|org\.slf4j|jdk\.incubator\.vector)" >&2
  exit 1
fi

if [[ "$JAVA_MAJOR" -ge 16 ]] \
  && jar tf "$CORE_JAR" | grep -qx "META-INF/versions/16/org/apache/fory/util/ArrayCompressionUtils.class"; then
  mkdir -p "$WORK_DIR/vector-classes"
  cat > "$WORK_DIR/VectorSmoke.java" <<'EOF'
import org.apache.fory.util.ArrayCompressionUtils;
import org.apache.fory.util.PrimitiveArrayCompressionType;

public final class VectorSmoke {
  private static void assertIntType(
      int[] values, PrimitiveArrayCompressionType expected) {
    PrimitiveArrayCompressionType actual =
        ArrayCompressionUtils.determineIntCompressionType(values);
    if (actual != expected) {
      throw new AssertionError("Expected " + expected + " for int array, got " + actual);
    }
  }

  private static void assertLongType(
      long[] values, PrimitiveArrayCompressionType expected) {
    PrimitiveArrayCompressionType actual =
        ArrayCompressionUtils.determineLongCompressionType(values);
    if (actual != expected) {
      throw new AssertionError("Expected " + expected + " for long array, got " + actual);
    }
  }

  public static void main(String[] args) throws Exception {
    // Length 513 exercises both the Vector loop and its scalar tail on power-of-two species.
    int[] byteValues = new int[513];
    int[] shortValues = new int[513];
    int[] uncompressedInts = new int[513];
    int[] byteTail = new int[513];
    int[] shortTail = new int[513];
    long[] intValues = new long[513];
    long[] uncompressedLongs = new long[513];
    long[] intTail = new long[513];
    for (int i = 0; i < byteValues.length; i++) {
      byteValues[i] = i % 2 == 0 ? Byte.MIN_VALUE : Byte.MAX_VALUE;
      shortValues[i] = i % 2 == 0 ? Short.MIN_VALUE : Short.MAX_VALUE;
      uncompressedInts[i] = i % 2 == 0 ? Integer.MIN_VALUE : Integer.MAX_VALUE;
      intValues[i] = i % 2 == 0 ? Integer.MIN_VALUE : Integer.MAX_VALUE;
      uncompressedLongs[i] = i % 2 == 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
    }
    byteTail[512] = Byte.MAX_VALUE + 1;
    shortTail[512] = Short.MAX_VALUE + 1;
    intTail[512] = (long) Integer.MAX_VALUE + 1;
    assertIntType(byteValues, PrimitiveArrayCompressionType.INT_TO_BYTE);
    assertIntType(shortValues, PrimitiveArrayCompressionType.INT_TO_SHORT);
    assertIntType(uncompressedInts, PrimitiveArrayCompressionType.NONE);
    assertIntType(byteTail, PrimitiveArrayCompressionType.INT_TO_SHORT);
    assertIntType(shortTail, PrimitiveArrayCompressionType.NONE);
    assertIntType(new int[511], PrimitiveArrayCompressionType.NONE);
    assertLongType(intValues, PrimitiveArrayCompressionType.LONG_TO_INT);
    assertLongType(uncompressedLongs, PrimitiveArrayCompressionType.NONE);
    assertLongType(intTail, PrimitiveArrayCompressionType.NONE);
    assertLongType(new long[511], PrimitiveArrayCompressionType.NONE);

    String classResource =
        ArrayCompressionUtils.class.getResource("ArrayCompressionUtils.class").toString();
    if (!classResource.contains("META-INF/versions/16/")) {
      throw new AssertionError("JDK 16+ did not select the Vector implementation: " + classResource);
    }
    System.out.println("vector-ok");
  }
}
EOF
  javac -cp "$CORE_JAR" -d "$WORK_DIR/vector-classes" "$WORK_DIR/VectorSmoke.java"
  java \
    --add-modules jdk.incubator.vector \
    -cp "$CORE_JAR:$WORK_DIR/vector-classes" \
    VectorSmoke \
    | grep -qx "vector-ok"
fi

echo "JPMS jlink smoke test passed with minimal optional dependencies."
