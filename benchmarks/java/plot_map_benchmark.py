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

import pandas as pd
import matplotlib.pyplot as plt
import numpy as np

# Load the CSV data
no_chunk_file = "nochunk-jmh-result.csv"
chunk_file = "chunk-jmh-result.csv"
# Read the CSV files
no_chunk_df = pd.read_csv(no_chunk_file)
chunk_df = pd.read_csv(chunk_file)


# Function to plot the figures
def plot_benchmark(ax, data1, data2, operation, struct, datatype, title):
    # Filter data
    filtered_data1 = data1[
        (data1["Benchmark"] == (operation))
        & (data1["struct"] == struct)
        & (data1["datatype"] == datatype)
    ]
    filtered_data2 = data2[
        (data2["Benchmark"] == (operation))
        & (data2["struct"] == struct)
        & (data2["datatype"] == datatype)
    ]

    # Sort data according to 'mapSize'
    filtered_data1 = filtered_data1.sort_values("mapSize")
    filtered_data2 = filtered_data2.sort_values("mapSize")

    # Plotting
    x_labels = filtered_data1["mapSize"].astype(str).tolist()
    x = np.arange(len(x_labels))
    width = 0.35

    ax.bar(
        x - width / 2,
        filtered_data1["Score"],
        width,
        yerr=filtered_data1["ScoreError"],
        label="No Chunk",
    )
    ax.bar(
        x + width / 2,
        filtered_data2["Score"],
        width,
        yerr=filtered_data2["ScoreError"],
        label="Chunk",
    )
    ax.set_xlabel("Map Size")
    ax.set_ylabel("Score (ops/s)")
    ax.set_title(title)
    ax.set_xticks(x)
    ax.set_xticklabels(x_labels)
    ax.legend()


# Create the subplots for datatype "int"
fig1, axs1 = plt.subplots(2, 2, figsize=(10, 8))
plot_benchmark(
    axs1[0, 0],
    no_chunk_df,
    chunk_df,
    "serialize",
    True,
    "int",
    "Serialize | Datatype: Int",
)
plot_benchmark(
    axs1[0, 1],
    no_chunk_df,
    chunk_df,
    "serialize",
    True,
    "string",
    "Serialize | Datatype: String",
)
plot_benchmark(
    axs1[1, 0],
    no_chunk_df,
    chunk_df,
    "deserialize",
    True,
    "int",
    "Deserialize | Datatype: Int",
)
plot_benchmark(
    axs1[1, 1],
    no_chunk_df,
    chunk_df,
    "deserialize",
    True,
    "string",
    "Deserialize | Datatype: String",
)
plt.tight_layout()
plt.suptitle("Benchmarks for codegen", y=1.05)


# Create the subplots for datatype "string"
fig2, axs2 = plt.subplots(2, 2, figsize=(10, 8))
plot_benchmark(
    axs2[0, 0],
    no_chunk_df,
    chunk_df,
    "serialize",
    False,
    "int",
    "Serialize | Datatype: Int",
)
plot_benchmark(
    axs2[0, 1],
    no_chunk_df,
    chunk_df,
    "serialize",
    False,
    "string",
    "Serialize | Datatype: String",
)
plot_benchmark(
    axs2[1, 0],
    no_chunk_df,
    chunk_df,
    "deserialize",
    False,
    "int",
    "Deserialize | Datatype: Int",
)
plot_benchmark(
    axs2[1, 1],
    no_chunk_df,
    chunk_df,
    "deserialize",
    False,
    "string",
    "Deserialize | Datatype: String",
)
plt.tight_layout()
plt.suptitle("Benchmarks for no codegen", y=1.05)

plt.show()
