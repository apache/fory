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

import unittest

if __package__:
    from . import release
else:
    import release


class ReleaseDocVersionTest(unittest.TestCase):
    def test_updates_release_doc_dependencies(self):
        lines = [
            "python -m pip install pyfory==1.5.0\n",
            'python -m pip install "pyfory[format]==1.5.0"\n',
            "go get github.com/apache/fory/go/fory@v1.5.0\n",
            "npm install @apache-fory/core@1.5.0 @apache-fory/hps@1.5.0\n",
            "python -m pip install pyfory==1.5.0 grpcio==1.73.0 # Fory 1.4.0\n",
            "go get github.com/apache/fory/go/fory@v1.5.0 # Fory 1.4.0\n",
            "npm install @apache-fory/core@1.5.0 grpc@1.73.0 @apache-fory/hps@1.5.0\n",
            'fory = "1.5.0"\n',
            "  fory: 1.5.0\n",
            '    .package(url: "https://github.com/apache/fory.git", exact: "1.5.0")\n',
            'implementation("org.apache.fory:fory-core:1.5.0")\n',
            'libraryDependencies += "org.apache.fory" %% "fory-scala" % "1.5.0"\n',
            'implementation("io.grpc:grpc-api:1.73.0")\n',
            "The wire format was introduced in version 1.5.0.\n",
        ]
        expected = [
            "python -m pip install pyfory==1.6.0\n",
            'python -m pip install "pyfory[format]==1.6.0"\n',
            "go get github.com/apache/fory/go/fory@v1.6.0\n",
            "npm install @apache-fory/core@1.6.0 @apache-fory/hps@1.6.0\n",
            "python -m pip install pyfory==1.6.0 grpcio==1.73.0 # Fory 1.4.0\n",
            "go get github.com/apache/fory/go/fory@v1.6.0 # Fory 1.4.0\n",
            "npm install @apache-fory/core@1.6.0 grpc@1.73.0 @apache-fory/hps@1.6.0\n",
            'fory = "1.6.0"\n',
            "  fory: 1.6.0\n",
            '    .package(url: "https://github.com/apache/fory.git", exact: "1.6.0")\n',
            'implementation("org.apache.fory:fory-core:1.6.0")\n',
            'libraryDependencies += "org.apache.fory" %% "fory-scala" % "1.6.0"\n',
            'implementation("io.grpc:grpc-api:1.73.0")\n',
            "The wire format was introduced in version 1.5.0.\n",
        ]

        updated = release._update_release_doc_lines(lines, "1.6.0")

        self.assertEqual(expected, updated)
        self.assertEqual(expected, release._update_release_doc_lines(updated, "1.6.0"))


if __name__ == "__main__":
    unittest.main()
