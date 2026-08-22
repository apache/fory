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

import plot_json_benchmark


class JsonBenchmarkPlotTest(unittest.TestCase):
    def test_resolve_jackson(self) -> None:
        serializers = plot_json_benchmark.parse_libs("fory-json,jackson,gson")
        self.assertEqual(
            plot_json_benchmark.resolve_serializers(serializers, "standard"),
            ("fory", "jackson", "gson"),
        )
        self.assertEqual(
            plot_json_benchmark.resolve_serializers(serializers, "blackbird"),
            ("fory", "blackbird", "gson"),
        )
        self.assertEqual(
            plot_json_benchmark.format_library_names(("fory", "blackbird", "gson")),
            "fory-json, Jackson Blackbird, and Gson",
        )

    def test_reject_mixed_jackson_results(self) -> None:
        benchmarks = [
            {"benchmark": "suite.jacksonToJsonBytes"},
            {"benchmark": "suite.blackbirdToJsonBytes"},
        ]
        with self.assertRaisesRegex(ValueError, "both Jackson"):
            plot_json_benchmark.validate_jackson_results(
                benchmarks, ("jackson",), "standard"
            )

    def test_reject_wrong_jackson_result(self) -> None:
        benchmarks = [{"benchmark": "suite.jacksonToJsonBytes"}]
        with self.assertRaisesRegex(ValueError, "selected blackbird"):
            plot_json_benchmark.validate_jackson_results(
                benchmarks, ("jackson",), "blackbird"
            )


if __name__ == "__main__":
    unittest.main()
