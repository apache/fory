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

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import plot_json_benchmark
import run_json_benchmark


class ScalaJsonPlotTest(unittest.TestCase):
    def test_collects_three_bars_for_every_case(self) -> None:
        benchmarks = []
        for representation in ("String", "Bytes"):
            for operation in ("To", "From"):
                for index, serializer in enumerate(("fory", "jsoniter", "jackson"), 1):
                    benchmarks.append(
                        {
                            "benchmark": f"suite.{serializer}{operation}Json{representation}",
                            "primaryMetric": {
                                "score": index,
                                "scoreError": 0,
                                "scoreUnit": "ops/ms",
                            },
                        }
                    )
        results = plot_json_benchmark.collect_results(benchmarks)
        self.assertEqual(len(results[("to", "string")]), 3)
        self.assertEqual(results[("from", "bytes")]["jackson"][0], 3_000)

    def test_alternating_median(self) -> None:
        self.assertEqual(
            run_json_benchmark.alternating_order(1),
            ("jsoniter", "jackson", "fory"),
        )
        trials = []
        for score in (10.0, 30.0, 20.0):
            trial = []
            for representation in ("String", "Bytes"):
                for operation in ("To", "From"):
                    for serializer in ("fory", "jsoniter", "jackson"):
                        trial.append(
                            {
                                "benchmark": (
                                    f"suite.{serializer}{operation}Json{representation}"
                                ),
                                "primaryMetric": {
                                    "score": score,
                                    "scoreError": 1.0,
                                    "scoreConfidence": [score - 1.0, score + 1.0],
                                    "scorePercentiles": {"50.0": score},
                                    "rawData": [[score]],
                                    "scoreUnit": "ops/s",
                                },
                            }
                        )
            trials.append(trial)
        results = run_json_benchmark.aggregate_results(trials, 3)
        self.assertEqual(len(results), 12)
        self.assertTrue(
            all(result["primaryMetric"]["score"] == 20.0 for result in results)
        )
        for result in results:
            metric = result["primaryMetric"]
            self.assertNotIn("rawData", metric)
            self.assertNotIn("scoreConfidence", metric)
            self.assertNotIn("scorePercentiles", metric)


if __name__ == "__main__":
    unittest.main()
