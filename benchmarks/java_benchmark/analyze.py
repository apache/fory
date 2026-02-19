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

"""
process fory/kryo/fst/hession performance data
"""

import matplotlib.pyplot as plt
import os
import pandas as pd
from pathlib import Path
import re

dir_path = os.path.dirname(os.path.realpath(__file__))
repo_root = Path(dir_path).parent.parent
java_benchmark_dir = repo_root / "docs/benchmarks/java"
java_benchmark_data_dir = java_benchmark_dir / "data"
java_benchmark_readme = java_benchmark_dir / "README.md"

lib_order = [
    "Fory",
    "ForyMetaShared",
    "Kryo",
    "Fst",
    "Hession",
    "Jdk",
    "Protostuff",
]

java_serialization_files = [
    "jmh-jdk-11-serialization.csv",
    "jmh-jdk-11-deserialization.csv",
]
java_zero_copy_file = "jmh-jdk-11-zerocopy.csv"

java_plot_combined_groups = [
    {
        "alt": "Java Heap Schema Consistent Serialization",
        "combined": "java_heap_serialize_consistent.png",
        "sources": [
            "serialization/bench_serialize_STRUCT_to_array_tps.png",
            "serialization/bench_serialize_STRUCT2_to_array_tps.png",
            "serialization/bench_serialize_MEDIA_CONTENT_to_array_tps.png",
            "serialization/bench_serialize_SAMPLE_to_array_tps.png",
        ],
    },
    {
        "alt": "Java Heap Schema Compatible Serialization",
        "combined": "java_heap_serialize_compatible.png",
        "sources": [
            "serialization/bench_serialize_compatible_STRUCT_to_array_tps.png",
            "serialization/bench_serialize_compatible_STRUCT2_to_array_tps.png",
            "compatible/bench_serialize_compatible_MEDIA_CONTENT_to_array_tps.png",
            "serialization/bench_serialize_compatible_SAMPLE_to_array_tps.png",
        ],
    },
    {
        "alt": "Java Heap Schema Consistent Deserialization",
        "combined": "java_heap_deserialize_consistent.png",
        "sources": [
            "deserialization/bench_deserialize_STRUCT_from_array_tps.png",
            "deserialization/bench_deserialize_STRUCT2_from_array_tps.png",
            "deserialization/bench_deserialize_MEDIA_CONTENT_from_array_tps.png",
            "deserialization/bench_deserialize_SAMPLE_from_array_tps.png",
        ],
    },
    {
        "alt": "Java Heap Schema Compatible Deserialization",
        "combined": "java_heap_deserialize_compatible.png",
        "sources": [
            "deserialization/bench_deserialize_compatible_STRUCT_from_array_tps.png",
            "deserialization/bench_deserialize_compatible_STRUCT2_from_array_tps.png",
            "compatible/bench_deserialize_compatible_MEDIA_CONTENT_from_array_tps.png",
            "deserialization/bench_deserialize_compatible_SAMPLE_from_array_tps.png",
        ],
    },
    {
        "alt": "Java Off Heap Schema Consistent Serialization",
        "combined": "java_offheap_serialize_consistent.png",
        "sources": [
            "serialization/bench_serialize_STRUCT_to_directBuffer_tps.png",
            "serialization/bench_serialize_STRUCT2_to_directBuffer_tps.png",
            "serialization/bench_serialize_MEDIA_CONTENT_to_directBuffer_tps.png",
            "serialization/bench_serialize_compatible_SAMPLE_to_directBuffer_tps.png",
        ],
    },
    {
        "alt": "Java Off Heap Schema Compatible Serialization",
        "combined": "java_offheap_serialize_compatible.png",
        "sources": [
            "compatible/bench_serialize_compatible_STRUCT_to_directBuffer_tps.png",
            "serialization/bench_serialize_compatible_STRUCT2_to_directBuffer_tps.png",
            "serialization/bench_serialize_compatible_MEDIA_CONTENT_to_directBuffer_tps.png",
            "serialization/bench_serialize_SAMPLE_to_directBuffer_tps.png",
        ],
    },
    {
        "alt": "Java Off Heap Schema Consistent Deserialization",
        "combined": "java_offheap_deserialize_consistent.png",
        "sources": [
            "deserialization/bench_deserialize_STRUCT_from_directBuffer_tps.png",
            "deserialization/bench_deserialize_STRUCT2_from_directBuffer_tps.png",
            "deserialization/bench_deserialize_MEDIA_CONTENT_from_directBuffer_tps.png",
            "deserialization/bench_deserialize_SAMPLE_from_directBuffer_tps.png",
        ],
    },
    {
        "alt": "Java Off Heap Schema Compatible Deserialization",
        "combined": "java_offheap_deserialize_compatible.png",
        "sources": [
            "compatible/bench_deserialize_compatible_STRUCT_from_directBuffer_tps.png",
            "deserialization/bench_deserialize_compatible_STRUCT2_from_directBuffer_tps.png",
            "deserialization/bench_deserialize_compatible_MEDIA_CONTENT_from_directBuffer_tps.png",
            "deserialization/bench_deserialize_compatible_SAMPLE_from_directBuffer_tps.png",
        ],
    },
    {
        "alt": "Java Zero Copy Serialization",
        "combined": "java_zero_copy_serialize.png",
        "sources": [
            "zerocopy/zero_copy_bench_serialize_BUFFER_to_array_tps.png",
            "zerocopy/zero_copy_bench_serialize_BUFFER_to_directBuffer_tps.png",
            "zerocopy/zero_copy_bench_serialize_PRIMITIVE_ARRAY_to_array_tps.png",
            "zerocopy/zero_copy_bench_serialize_PRIMITIVE_ARRAY_to_directBuffer_tps.png",
        ],
    },
    {
        "alt": "Java Zero Copy Deserialization",
        "combined": "java_zero_copy_deserialize.png",
        "sources": [
            "zerocopy/zero_copy_bench_deserialize_BUFFER_from_array_tps.png",
            "zerocopy/zero_copy_bench_deserialize_BUFFER_from_directBuffer_tps.png",
            "zerocopy/zero_copy_bench_deserialize_PRIMITIVE_ARRAY_from_array_tps.png",
            "zerocopy/zero_copy_bench_deserialize_PRIMITIVE_ARRAY_from_directBuffer_tps.png",
        ],
    },
]

repo_plot_combined_groups = [
    {
        "alt": "Java Serialization Throughput",
        "combined": "docs/benchmarks/java/java_repo_serialization_throughput.png",
        "sources": [
            "docs/benchmarks/java/compatible/bench_serialize_compatible_STRUCT_to_directBuffer_tps.png",
            "docs/benchmarks/java/compatible/bench_serialize_compatible_MEDIA_CONTENT_to_array_tps.png",
            "docs/benchmarks/java/serialization/bench_serialize_MEDIA_CONTENT_to_array_tps.png",
            "docs/benchmarks/java/serialization/bench_serialize_SAMPLE_to_array_tps.png",
        ],
    },
    {
        "alt": "Java Deserialization Throughput",
        "combined": "docs/benchmarks/java/java_repo_deserialization_throughput.png",
        "sources": [
            "docs/benchmarks/java/compatible/bench_deserialize_compatible_STRUCT_from_directBuffer_tps.png",
            "docs/benchmarks/java/compatible/bench_deserialize_compatible_MEDIA_CONTENT_from_array_tps.png",
            "docs/benchmarks/java/deserialization/bench_deserialize_MEDIA_CONTENT_from_array_tps.png",
            "docs/benchmarks/java/deserialization/bench_deserialize_SAMPLE_from_array_tps.png",
        ],
    },
]


def _to_markdown(df: pd.DataFrame):
    lines = list(df.values.tolist())
    width = len(df.columns)
    lines.insert(0, df.columns.values.tolist())
    lines.insert(1, ["-------"] * width)
    md_table = "\n".join(
        ["| " + " | ".join([str(item) for item in line]) + " |" for line in lines]
    )
    return md_table


def _format_tps(value):
    if pd.isna(value):
        return ""
    return f"{float(value):.6f}"


def _pivot_lib_columns(df: pd.DataFrame, index_columns):
    table_df = (
        df.pivot_table(
            index=index_columns,
            columns="Lib",
            values="Tps",
            aggfunc="first",
            sort=False,
        )
        .reset_index()
        .copy()
    )
    available_libs = table_df.columns.tolist()
    sorted_lib_columns = [name for name in lib_order if name in available_libs]
    extra_lib_columns = sorted(
        [
            name
            for name in available_libs
            if name not in index_columns + sorted_lib_columns
        ]
    )
    table_df = table_df[index_columns + sorted_lib_columns + extra_lib_columns]
    if "references" in table_df.columns:
        table_df["references"] = table_df["references"].astype(str).str.capitalize()
    for column in sorted_lib_columns + extra_lib_columns:
        table_df[column] = table_df[column].map(_format_tps)
    return table_df


def _replace_table_section(content: str, heading: str, table_markdown: str):
    lines = content.splitlines()
    start_index = None
    for index, line in enumerate(lines):
        if line.strip() == heading:
            start_index = index
            break
    if start_index is None:
        raise ValueError(f"Failed to find section {heading}")
    end_index = len(lines)
    for index in range(start_index + 1, len(lines)):
        if lines[index].startswith("### "):
            end_index = index
            break
    updated_lines = lines[: start_index + 1] + ["", table_markdown, ""]
    if end_index < len(lines):
        updated_lines.extend(lines[end_index:])
    return "\n".join(updated_lines).rstrip() + "\n"


def _parse_chart_spec(source_path: str):
    name = Path(source_path).name
    benchmark_match = re.match(
        r"bench_(serialize(?:_compatible)?|deserialize(?:_compatible)?)_([A-Z0-9_]+)_(to|from)_(array|directBuffer)_tps\.png",
        name,
    )
    if benchmark_match is not None:
        return {
            "kind": "benchmark",
            "benchmark": benchmark_match.group(1),
            "objectType": benchmark_match.group(2),
            "bufferType": benchmark_match.group(4),
        }
    zero_copy_match = re.match(
        r"zero_copy_bench_(serialize|deserialize)_([A-Z_]+)_(to|from)_(array|directBuffer)_tps\.png",
        name,
    )
    if zero_copy_match is not None:
        return {
            "kind": "zero_copy",
            "benchmark": zero_copy_match.group(1),
            "dataType": zero_copy_match.group(2),
            "bufferType": zero_copy_match.group(4),
        }
    raise ValueError(f"Unsupported chart source path: {source_path}")


def _prepare_benchmark_plot_data(bench_df: pd.DataFrame):
    data = bench_df.fillna("").copy()
    compatible = data[data["Benchmark"].str.contains("compatible")]
    if len(compatible) > 0:
        jdk = data[data["Lib"].str.contains("Jdk")].copy()
        jdk["Benchmark"] = jdk["Benchmark"] + "_compatible"
        data = pd.concat([data, jdk], ignore_index=True)
    data["Tps"] = (data["Tps"] / scaler).apply(format_scaler)
    return data


def _prepare_zero_copy_plot_data(zero_copy_df: pd.DataFrame):
    data = zero_copy_df.fillna("").copy()
    data["Tps"] = (data["Tps"] / scaler).apply(format_scaler)
    return data


def _build_single_plot_frame(spec, benchmark_data, zero_copy_data):
    if spec["kind"] == "benchmark":
        sub_df = benchmark_data[
            (benchmark_data["Benchmark"] == spec["benchmark"])
            & (benchmark_data["objectType"] == spec["objectType"])
            & (benchmark_data["bufferType"] == spec["bufferType"])
        ][["Lib", "references", "Tps"]]
        final_df = (
            sub_df.reset_index(drop=True)
            .set_index(["Lib", "references"])
            .unstack("Lib")
        )
        if spec["benchmark"].startswith("serialize"):
            title = f"{spec['benchmark']} {spec['objectType']} to {spec['bufferType']}"
        else:
            title = (
                f"{spec['benchmark']} {spec['objectType']} from {spec['bufferType']}"
            )
        xlabel = "enable_references"
        width = 0.7 * bar_width_scale
    else:
        sub_df = zero_copy_data[
            (zero_copy_data["Benchmark"] == spec["benchmark"])
            & (zero_copy_data["dataType"] == spec["dataType"])
            & (zero_copy_data["bufferType"] == spec["bufferType"])
        ][["Lib", "array_size", "Tps"]]
        final_df = (
            sub_df.reset_index(drop=True)
            .set_index(["Lib", "array_size"])
            .unstack("Lib")
        )
        if spec["benchmark"].startswith("serialize"):
            title = f"{spec['benchmark']} {spec['dataType']} to {spec['bufferType']}"
        else:
            title = f"{spec['benchmark']} {spec['dataType']} from {spec['bufferType']}"
        xlabel = "array_size"
        width = 0.8 * bar_width_scale
    return final_df, title, xlabel, width


def _plot_combined_group(group, benchmark_data, zero_copy_data, output_path: Path):
    fig, axes = plt.subplots(1, 4, figsize=(22, 6), gridspec_kw={"wspace": 0.15})
    for axis_index, source_path in enumerate(group["sources"]):
        axis = axes[axis_index]
        spec = _parse_chart_spec(source_path)
        final_df, title, xlabel, width = _build_single_plot_frame(
            spec, benchmark_data, zero_copy_data
        )
        libs = final_df.columns.to_frame()["Lib"]
        color = [color_map[lib] for lib in libs]
        final_df.plot.bar(title=title, color=color, ax=axis, width=width)
        for container in axis.containers:
            axis.bar_label(container, fontsize=8)
        axis.set_xlabel(xlabel)
        if axis_index == 0:
            axis.set_ylabel(f"Tps/{scaler}")
        else:
            axis.set_ylabel("")
        add_upper_right_legend(axis, libs)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(
        output_path, dpi=170, bbox_inches="tight", pad_inches=0.03, facecolor="white"
    )
    plt.close(fig)


def _generate_direct_combined_plots(benchmark_df, zero_copy_df, base_dir: Path, groups):
    benchmark_data = _prepare_benchmark_plot_data(benchmark_df)
    zero_copy_data = _prepare_zero_copy_plot_data(zero_copy_df)
    for group in groups:
        output_path = base_dir / group["combined"]
        _plot_combined_group(group, benchmark_data, zero_copy_data, output_path)


def _update_java_benchmark_readme(data_dir: Path, readme_path: Path):
    benchmark_dfs = []
    for file_name in java_serialization_files:
        _, bench_df = process_data(str(data_dir / file_name))
        benchmark_dfs.append(bench_df)
    benchmark_df = pd.concat(benchmark_dfs, ignore_index=True)
    benchmark_df = (
        benchmark_df.assign(
            _benchmark_order=benchmark_df["Benchmark"].map(
                {
                    "serialize": 0,
                    "serialize_compatible": 1,
                    "deserialize": 2,
                    "deserialize_compatible": 3,
                }
            ),
            _buffer_order=benchmark_df["bufferType"].map(
                {"array": 0, "directBuffer": 1}
            ),
            _object_order=benchmark_df["objectType"].map(
                {"STRUCT": 0, "STRUCT2": 1, "MEDIA_CONTENT": 2, "SAMPLE": 3}
            ),
        )
        .sort_values(
            ["_benchmark_order", "_object_order", "_buffer_order", "references"]
        )
        .drop(columns=["_benchmark_order", "_buffer_order", "_object_order"])
        .reset_index(drop=True)
    )
    benchmark_table = _pivot_lib_columns(
        benchmark_df, ["Benchmark", "objectType", "bufferType", "references"]
    )

    zero_copy_df, _ = process_data(str(data_dir / java_zero_copy_file))
    zero_copy_df = (
        zero_copy_df.assign(
            _benchmark_order=zero_copy_df["Benchmark"].map(
                {"serialize": 0, "deserialize": 1}
            ),
            _buffer_order=zero_copy_df["bufferType"].map(
                {"array": 0, "directBuffer": 1}
            ),
            _data_type_order=zero_copy_df["dataType"].map(
                {"BUFFER": 0, "PRIMITIVE_ARRAY": 1}
            ),
        )
        .sort_values(
            ["_benchmark_order", "array_size", "_buffer_order", "_data_type_order"]
        )
        .drop(columns=["_benchmark_order", "_buffer_order", "_data_type_order"])
        .reset_index(drop=True)
    )
    zero_copy_table = _pivot_lib_columns(
        zero_copy_df, ["Benchmark", "array_size", "bufferType", "dataType"]
    )

    readme_content = readme_path.read_text()
    readme_content = _replace_table_section(
        readme_content, "### Java Serialization", _to_markdown(benchmark_table)
    )
    readme_content = _replace_table_section(
        readme_content, "### Java Zero-copy", _to_markdown(zero_copy_table)
    )
    readme_path.write_text(readme_content)


def process_data(filepath: str):
    df = pd.read_csv(filepath)
    columns = list(df.columns.values)
    for column in columns:
        if "Score Error" in column:
            df.drop([column], axis=1, inplace=True)
        if column == "Score":
            df.rename({"Score": "Tps"}, axis=1, inplace=True)
        if "Param: " in column:
            df.rename({column: column.replace("Param: ", "")}, axis=1, inplace=True)

    def process_df(bench_df):
        if bench_df.shape[0] > 0:
            benchmark_name = bench_df["Benchmark"].str.rsplit(
                pat=".", n=1, expand=True
            )[1]
            bench_df[["Lib", "Benchmark"]] = benchmark_name.str.split(
                pat="_", n=1, expand=True
            )
            bench_df["Lib"] = bench_df["Lib"].str.capitalize()
            bench_df["Lib"] = bench_df["Lib"].replace(
                {"Forymetashared": "ForyMetaShared"}
            )
            bench_df.drop(["Threads"], axis=1, inplace=True)
        return bench_df

    zero_copy_bench = df[df["Benchmark"].str.contains("ZeroCopy")].copy()
    zero_copy_bench = process_df(zero_copy_bench)

    bench = df[~df["Benchmark"].str.contains("ZeroCopy")].copy()
    bench = process_df(bench)

    return zero_copy_bench, bench


color_map = {
    "Fory": "#FF6f01",  # Orange
    "ForyMetaShared": "#FFB266",  # Shallow orange
    # "Kryo": (1, 0.5, 1),
    # "Kryo": (1, 0.84, 0.25),
    "Kryo": "#55BCC2",
    "Kryo_deserialize": "#55BCC2",
    "Fst": (0.90, 0.43, 0.5),
    "Hession": (0.80, 0.5, 0.6),
    "Hession_deserialize": (0.80, 0.5, 0.6),
    "Protostuff": (1, 0.84, 0.66),
    "Jdk": (0.55, 0.40, 0.45),
    "Jsonb": (0.45, 0.40, 0.55),
}


scaler = 10000
bar_width_scale = 1.2


def format_scaler(x):
    if x > 100:
        return round(x)
    else:
        return round(x, 1)


def add_upper_right_legend(ax, labels):
    legend_labels = [
        str(label).replace("ForyMetaShared", "ForyMeta\nShared") for label in labels
    ]
    ax.legend(
        legend_labels,
        loc="upper right",
        bbox_to_anchor=(0.98, 0.98),
        borderaxespad=0.2,
        prop={"size": 10},
        frameon=True,
        framealpha=0.9,
    )


if __name__ == "__main__":
    benchmark_dfs = []
    for file_name in java_serialization_files:
        _, bench_df = process_data(str(java_benchmark_data_dir / file_name))
        benchmark_dfs.append(bench_df)
    benchmark_df = pd.concat(benchmark_dfs, ignore_index=True)
    zero_copy_df, _ = process_data(str(java_benchmark_data_dir / java_zero_copy_file))

    _update_java_benchmark_readme(java_benchmark_data_dir, java_benchmark_readme)
    _generate_direct_combined_plots(
        benchmark_df, zero_copy_df, java_benchmark_dir, java_plot_combined_groups
    )
    _generate_direct_combined_plots(
        benchmark_df, zero_copy_df, repo_root, repo_plot_combined_groups
    )
