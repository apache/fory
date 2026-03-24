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

echo "AJDK8 Serialization"
REGISTER_CLASS=false java -jar target/benchmarks.jar "io.*\.UserTypeSerializeSuite\.*" -f 3 -wi 10 -i 10 -t 1 -w 2s -r 2s -rf csv
cp jmh-result.csv jmh-result-ajdk8-serialization.csv
python analyze.py
echo "AJDK8 Deserialization"
REGISTER_CLASS=false java -jar target/benchmarks.jar "io.*\.UserTypeDeserializeSuite\.*" -f 3 -wi 10 -i 10 -t 1 -w 2s -r 2s -rf csv
cp jmh-result.csv jmh-result-ajdk8-deserialization.csv
python analyze.py

source ~/.bashrc
for v in {8,11,17} ; do
  REGISTER_CLASS_FLAGS=('false' 'true')
  for flag in "${REGISTER_CLASS_FLAGS[@]}"; do
    echo "Oracle JDK$v Serialization: $flag"
    oracle_jdk $v
    REGISTER_CLASS=false java -jar target/benchmarks.jar "io.*\.UserTypeSerializeSuite\.*" -f 3 -wi 10 -i 10 -t 1 -w 2s -r 2s -rf csv
    cp jmh-result.csv jmh-result-jdk-$v-$flag-serialization.csv
    python analyze.py
    echo "Oracle JDK$v Deserialization: $flag"
    oracle_jdk $v
    REGISTER_CLASS=false java -jar target/benchmarks.jar "io.*\.UserTypeDeserializeSuite\.*" -f 3 -wi 10 -i 10 -t 1 -w 2s -r 2s -rf csv
    cp jmh-result.csv jmh-result-jdk-$v-$flag-deserialization.csv
    python analyze.py
  done
done

echo "Oracle JDK11 Serialization: ZeroCopySuite"
oracle_jdk 11
REGISTER_CLASS=false java -jar target/benchmarks.jar "io.*\.ZeroCopySuite\.*" -f 3 -wi 10 -i 10 -t 1 -w 2s -r 2s -rf csv
cp jmh-result.csv jmh-result-jdk-8-false-zerocopy-deserialization.csv
python analyze.py
