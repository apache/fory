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
            bench_df.drop(["Threads"], axis=1, inplace=True)
        return bench_df

    zero_copy_bench = df[df["Benchmark"].str.contains("ZeroCopy")]
    zero_copy_bench = process_df(zero_copy_bench)

    bench = df[~df["Benchmark"].str.contains("ZeroCopy")]
    bench = process_df(bench)

    return zero_copy_bench, bench


color_map = {
    "Fory": "#7845FD",
    "Forymetashared": "#B237ED",  # (1, 0.65, 0.55)
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
        fory_metashared_color = plot_color_map["Forymetashared"]
        fory_color = plot_color_map["Fory"]
        plot_color_map["Fory"] = fory_metashared_color
        plot_color_map["Forymetashared"] = fory_color
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
        libs = libs.str.replace("metashared", "meta\nshared")
        ax.legend(libs, loc="upper right", prop={"size": 13})
        save_dir = get_plot_dir(file_dir)
        sub_plot.get_figure().savefig(save_dir + "/" + save_filename)


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
        ax.legend(libs, bbox_to_anchor=(0.23, 0.99), prop={"size": 13})
        save_dir = get_plot_dir(file_dir)
        sub_plot.get_figure().savefig(save_dir + "/" + save_filename)


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
        file_name = args[0]
    else:
        file_name = "jmh-jdk-11-deserialization.csv"
    file_dir = f"{dir_path}/../../docs/benchmarks/data"
    zero_copy_bench, bench = process_data(os.path.join(file_dir, file_name))
    if zero_copy_bench.shape[0] > 0:
        to_markdown(zero_copy_bench, str(Path(file_name).with_suffix(".zero_copy.md")))
        plot_zero_copy(zero_copy_bench, file_dir, "zero_copy_bench", column="Tps")
    if bench.shape[0] > 0:
        to_markdown(bench, str(Path(file_name).with_suffix(".bench.md")))
        plot(bench, file_dir, "bench", column="Tps")
