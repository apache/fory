# Adapted from cython/Tools/rules.bzl
# Uses official Cython rules pattern from BCR
"""Custom rules for building Cython extensions"""

def pyx_library(name, deps = [], cc_kwargs = {}, py_deps = [], srcs = [], **kwargs):
    """Compiles a group of .pyx / .pxd / .py files.

    First runs Cython to create .cpp files for each input .pyx or .py + .pxd
    pair. Then builds a shared object for each, passing "deps" and `**cc_kwargs`
    to each cc_binary rule (includes Python headers by default). Finally, creates
    a py_library rule with the shared objects and any pure Python "srcs", with py_deps
    as its dependencies; the shared objects can be imported like normal Python files.

    Args:
        name: Name for the rule.
        deps: C/C++ dependencies of the Cython (e.g. Numpy headers).
        cc_kwargs: cc_binary extra arguments such as copts, linkstatic, linkopts, features
        py_deps: Pure Python dependencies of the final library.
        srcs: .py, .pyx, or .pxd files to either compile or pass through.
        **kwargs: Extra keyword arguments passed to the py_library.
    """

    # First filter out files that should be run compiled vs. passed through.
    py_srcs = []
    pyx_srcs = []
    pxd_srcs = []
    for src in srcs:
        if src.endswith(".pyx") or (src.endswith(".py") and
                                    src[:-3] + ".pxd" in srcs):
            pyx_srcs.append(src)
        elif src.endswith(".py"):
            py_srcs.append(src)
        else:
            pxd_srcs.append(src)
        if src.endswith("__init__.py"):
            pxd_srcs.append(src)

    # Invoke cython to produce the shared object libraries.
    for filename in pyx_srcs:
        native.genrule(
            name = filename + "_cython_translation",
            srcs = [filename],
            outs = [filename.split(".")[0] + ".cpp"],
            cmd = "PYTHONHASHSEED=0 $(execpath @cython//:cython_binary) --cplus $(SRCS) --output-file $(OUTS)",
            tools = ["@cython//:cython_binary"] + pxd_srcs,
        )

    shared_objects = []
    for src in pyx_srcs:
        stem = src.split(".")[0]
        shared_object_name = stem + ".so"

        # Get linkopts from cc_kwargs or use empty list
        linkopts = cc_kwargs.pop("linkopts", [])

        native.cc_binary(
            name = cc_kwargs.pop("name", shared_object_name),
            srcs = [stem + ".cpp"] + cc_kwargs.pop("srcs", []),
            deps = deps + ["@rules_python//python/cc:current_py_cc_headers"] + cc_kwargs.pop("deps", []),
            linkshared = cc_kwargs.pop("linkshared", 1),
            # On macOS, use -undefined dynamic_lookup to allow Python symbols
            # to be resolved at runtime when the extension is imported.
            linkopts = linkopts + select({
                "@platforms//os:macos": ["-undefined", "dynamic_lookup"],
                "//conditions:default": [],
            }),
            **cc_kwargs
        )
        shared_objects.append(shared_object_name)

    data = shared_objects[:]
    data += kwargs.pop("data", [])

    # Now create a py_library with these shared objects as data.
    native.py_library(
        name = name,
        srcs = py_srcs,
        deps = py_deps,
        srcs_version = "PY3",
        data = data,
        **kwargs
    )
