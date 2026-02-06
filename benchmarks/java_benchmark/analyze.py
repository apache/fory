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

import datetime
import matplotlib.pyplot as plt
import os
import pandas as pd
from pathlib import Path
import re
import sys

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


def to_markdown(df: pd.DataFrame, filepath: str):
    columns = df.columns.tolist()
    for col in list(columns):
        if len(df[col].value_counts()) == 1:
            columns.remove(col)
    if "Lib" in columns:
        columns.remove("Lib")
        columns.insert(0, "Lib")
    if "Tps" in columns:
        columns.remove("Tps")
        columns.append("Tps")
    df = df[columns]
    with open(filepath, "w") as f:
        f.write(_to_markdown(df))


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
            index=index_columns, columns="Lib", values="Tps", aggfunc="first", sort=False
        )
        .reset_index()
        .copy()
    )
    available_libs = table_df.columns.tolist()
    sorted_lib_columns = [name for name in lib_order if name in available_libs]
    extra_lib_columns = sorted(
        [name for name in available_libs if name not in index_columns + sorted_lib_columns]
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
            _buffer_order=benchmark_df["bufferType"].map({"array": 0, "directBuffer": 1}),
            _object_order=benchmark_df["objectType"].map(
                {"STRUCT": 0, "STRUCT2": 1, "MEDIA_CONTENT": 2, "SAMPLE": 3}
            ),
        )
        .sort_values(["_benchmark_order", "_object_order", "_buffer_order", "references"])
        .drop(columns=["_benchmark_order", "_buffer_order", "_object_order"])
        .reset_index(drop=True)
    )
    benchmark_table = _pivot_lib_columns(
        benchmark_df, ["Benchmark", "objectType", "bufferType", "references"]
    )

    zero_copy_df, _ = process_data(str(data_dir / java_zero_copy_file))
    zero_copy_df = (
        zero_copy_df.assign(
            _benchmark_order=zero_copy_df["Benchmark"].map({"serialize": 0, "deserialize": 1}),
            _buffer_order=zero_copy_df["bufferType"].map({"array": 0, "directBuffer": 1}),
            _data_type_order=zero_copy_df["dataType"].map({"BUFFER": 0, "PRIMITIVE_ARRAY": 1}),
        )
        .sort_values(["_benchmark_order", "array_size", "_buffer_order", "_data_type_order"])
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


def format_scaler(x):
    if x > 100:
        return round(x)
    else:
        return round(x, 1)


def add_upper_right_legend(ax, labels):
    legend_labels = [str(label).replace("ForyMetaShared", "ForyMeta\nShared") for label in labels]
    ax.legend(
        legend_labels,
        loc="upper right",
        bbox_to_anchor=(0.98, 0.98),
        borderaxespad=0.2,
        prop={"size": 10},
        frameon=True,
        framealpha=0.9,
    )


def plot(df: pd.DataFrame, file_dir, filename, column="Tps"):
    df["ns"] = (1 / df["Tps"] * 10**9).astype(int)
    data = df.fillna("")
    data.to_csv(f"{file_dir}/pd_{filename}")
    if "objectType" in data.columns:
        group_cols = ["Benchmark", "objectType", "bufferType"]
    else:
        group_cols = ["Benchmark", "bufferType"]
    compatible = data[data["Benchmark"].str.contains("compatible")]
    plot_color_map = dict(color_map)
    if len(compatible) > 0:
        jdk = data[data["Lib"].str.contains("Jdk")].copy()
        jdk["Benchmark"] = jdk["Benchmark"] + "_compatible"
        data = pd.concat([data, jdk])
    ylabel = column
    if column == "Tps":
        ylabel = f"Tps/{scaler}"
        data[column] = (data[column] / scaler).apply(format_scaler)
    grouped = data.groupby(group_cols)
    files_dict = {}
    count = 0
    for keys, sub_df in grouped:
        count = count + 1
        sub_df = sub_df[["Lib", "references", column]]
        if keys[0].startswith("serialize"):
            title = " ".join(keys[:-1]) + " to " + keys[-1]
        else:
            title = " ".join(keys[:-1]) + " from " + keys[-1]
        kind = "Time" if column == "ns" else "Tps"
        save_filename = f"""{filename}_{title.replace(" ", "_")}_{kind.lower()}"""
        cnt = files_dict.get(save_filename, 0)
        if cnt > 0:
            files_dict[save_filename] = cnt = cnt + 1
            save_filename += "_" + cnt
        title = f"{title} ({kind})"
        fig, ax = plt.subplots()
        final_df = (
            sub_df.reset_index(drop=True)
            .set_index(["Lib", "references"])
            .unstack("Lib")
        )
        print(final_df)
        libs = final_df.columns.to_frame()["Lib"]
        color = [plot_color_map[lib] for lib in libs]
        sub_plot = final_df.plot.bar(
            title=title, color=color, ax=ax, figsize=(7, 7), width=0.7
        )
        for container in ax.containers:
            ax.bar_label(container)
        ax.set_xlabel("enable_references")
        ax.set_ylabel(ylabel)
        add_upper_right_legend(ax, libs)
        save_dir = get_plot_dir(file_dir)
        sub_plot.get_figure().savefig(save_dir + "/" + save_filename)
        plt.close(fig)


def plot_zero_copy(df: pd.DataFrame, file_dir, filename, column="Tps"):
    df["ns"] = (1 / df["Tps"] * 10**9).astype(int)
    data = df.fillna("")
    data.to_csv(f"{file_dir}/pd_{filename}")
    if "dataType" in data.columns:
        group_cols = ["Benchmark", "dataType", "bufferType"]
    else:
        group_cols = ["Benchmark", "bufferType"]
    ylabel = column
    if column == "Tps":
        ylabel = f"Tps/{scaler}"
        data[column] = (data[column] / scaler).apply(format_scaler)
    grouped = data.groupby(group_cols)
    files_dict = {}
    count = 0
    for keys, sub_df in grouped:
        count = count + 1
        sub_df = sub_df[["Lib", "array_size", column]]
        if keys[0].startswith("serialize"):
            title = " ".join(keys[:-1]) + " to " + keys[-1]
        else:
            title = " ".join(keys[:-1]) + " from " + keys[-1]
        kind = "Time" if column == "ns" else "Tps"
        save_filename = f"""{filename}_{title.replace(" ", "_")}_{kind.lower()}"""
        cnt = files_dict.get(save_filename, 0)
        if cnt > 0:
            files_dict[save_filename] = cnt = cnt + 1
            save_filename += "_" + cnt
        title = f"{title} ({kind})"
        fig, ax = plt.subplots()
        final_df = (
            sub_df.reset_index(drop=True)
            .set_index(["Lib", "array_size"])
            .unstack("Lib")
        )
        print(final_df)
        libs = final_df.columns.to_frame()["Lib"]
        color = [color_map[lib] for lib in libs]
        sub_plot = final_df.plot.bar(title=title, color=color, ax=ax, figsize=(7, 7))
        for container in ax.containers:
            ax.bar_label(container)
        ax.set_xlabel("array_size")
        ax.set_ylabel(ylabel)
        add_upper_right_legend(ax, libs)
        save_dir = get_plot_dir(file_dir)
        sub_plot.get_figure().savefig(save_dir + "/" + save_filename)
        plt.close(fig)


time_str = datetime.datetime.now().strftime("%m%d_%H%M_%S")


def get_plot_dir(_file_dir):
    plot_dir = _file_dir + "/" + time_str
    if not os.path.exists(plot_dir):
        os.makedirs(plot_dir)
    return plot_dir


def camel_to_snake(name):
    name = re.sub("(.)([A-Z][a-z]+)", r"\1_\2", name)
    return re.sub("([a-z\\d])([A-Z])", r"\1_\2", name).lower()


def get_datasize_markdown(size_log):
    lines = [line.rsplit("===>", 1)[-1] for line in size_log.split("\n")]
    lines = [
        [item.strip() for item in line.split("|")][:-1] for line in lines if "|" in line
    ]
    columns = "Lib,objectType,references,bufferType,size".split(",")
    df = pd.DataFrame(lines, columns=columns)
    df["size"] = df["size"].astype(int)
    df = df["objectType,references,bufferType,size".split(",") + ["Lib"]]
    grouped_df = df.sort_values("objectType,references,bufferType,size".split(","))
    grouped_df = grouped_df[~grouped_df["bufferType"].str.contains("directBuffer")]
    grouped_df = grouped_df["objectType,references,Lib,size".split(",")]
    return _to_markdown(grouped_df)


if __name__ == "__main__":
    # size_markdown = get_datasize_markdown("""
    # """)
    # print(size_markdown)
    args = sys.argv[1:]
    if args:
        file_path = Path(args[0])
    else:
        file_path = Path("jmh-jdk-11-deserialization.csv")
    if not file_path.is_file():
        file_path = java_benchmark_data_dir / file_path
    file_name = file_path.name
    plot_output_dir = str(java_benchmark_dir)
    zero_copy_bench, bench = process_data(str(file_path))
    if zero_copy_bench.shape[0] > 0:
        to_markdown(zero_copy_bench, str(Path(file_name).with_suffix(".zero_copy.md")))
        plot_zero_copy(zero_copy_bench, plot_output_dir, "zero_copy_bench", column="Tps")
    if bench.shape[0] > 0:
        to_markdown(bench, str(Path(file_name).with_suffix(".bench.md")))
        plot(bench, plot_output_dir, "bench", column="Tps")
    _update_java_benchmark_readme(java_benchmark_data_dir, java_benchmark_readme)
