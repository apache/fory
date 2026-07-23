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

# -*- mode: python ; coding: utf-8 -*-
#
# PyInstaller spec for building the standalone foryc binary.
#
# Run from the compiler/ directory:
#   cd compiler && pyinstaller foryc.spec
#
# Output: compiler/dist/foryc/foryc  (foryc.exe on Windows)
#
# --onedir mode is used instead of --onefile.
# --onefile extracts Python DLLs to %TEMP% at runtime, which Windows Defender
# intercepts at the memory-mapping level (PYI-xxxx: LoadLibrary: Invalid access
# to memory location / ERROR_NOACCESS). This cannot be suppressed on GitHub's
# hardened Windows Server 2022 runners even with DisableRealtimeMonitoring.
# --onedir pre-extracts everything during the build step; no runtime extraction
# occurs, so Defender is never triggered.
#
# UPX compression is intentionally NOT applied here.
# The CI workflow applies UPX manually per-platform for precise control.

# ── Auto-discover all fory_compiler submodules ──────────────────────────────
# pkgutil.walk_packages walks the installed package tree at spec-evaluation
# time, so this list stays correct automatically when new submodules are added.
# This eliminates the dual-maintenance problem between foryc.spec and the
# CI verify step — both derive from the same source of truth: the installed
# fory_compiler package tree. Adding a new generator (e.g. generators/kotlin.py)
# is automatically picked up by both the spec and the CI verify step.
#
# onerror CONTRACT (critical):
#   walk_packages calls onerror(pkg_name) when it cannot import a package in
#   order to recurse into it. Using `onerror=lambda name: None` silently drops
#   the entire subtree — the binary builds successfully but crashes at runtime
#   when any generator in that subtree is invoked.
#   _walk_onerror raises immediately so the BUILD fails loudly, not silently.
import pkgutil
import fory_compiler as _fc


def _walk_onerror(pkg_name: str) -> None:
    raise ImportError(
        f"pkgutil.walk_packages failed to recurse into {pkg_name!r}. "
        f"This package cannot be imported at spec-evaluation time. "
        f"Fix the import error in {pkg_name!r} before building the binary. "
        f"Hint: run `python -c \"import {pkg_name}\"` in the compiler/ venv "
        f"to reproduce the error."
    )


hiddenimports = ["fory_compiler"] + [
    m.name
    for m in pkgutil.walk_packages(
        path=_fc.__path__,
        prefix=_fc.__name__ + ".",
        onerror=_walk_onerror,  # fail loudly — never silently drop a subtree
    )
]

a = Analysis(
    ['fory_compiler/__main__.py'],
    pathex=['.'],
    binaries=[],
    datas=[],
    hiddenimports=hiddenimports,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    # foryc uses: argparse, copy, os, sys, pathlib, typing, dataclasses,
    # enum, re, abc, io, collections, functools, itertools.
    # Everything below is confirmed unused — aggressively excluded to
    # reduce binary size.
    excludes=[
        'unittest', 'doctest', 'pdb', 'pydoc', 'py_compile', 'profile',
        'distutils', 'setuptools', 'pkg_resources',
        'email', 'html', 'http', 'xmlrpc',
        'xml.etree', 'xml.dom', 'xml.sax',
        'tkinter', '_tkinter',
        'curses', '_curses', 'readline',
        'dbm', 'sqlite3', '_sqlite3',
        'asyncio', 'concurrent', 'multiprocessing',
        'test', '_test', 'lib2to3',
    ],
    noarchive=False,
)

pyz = PYZ(a.pure, a.zipped_data)

# --onedir mode: exclude_binaries=True keeps EXE as a stub only.
# COLLECT below pulls exe + all DLLs + stdlib into dist/foryc/ directory.
exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name='foryc',
    debug=False,
    bootloader_ignore_signals=False,
    strip=True,
    upx=False,
    console=True,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)

# COLLECT bundles everything into dist/foryc/
# strip=False here — stripping shared libraries/DLLs breaks them.
# Only the executable stub (above) is stripped.
coll = COLLECT(
    exe,
    a.binaries,
    a.zipfiles,
    a.datas,
    strip=False,
    upx=False,
    upx_exclude=[],
    name='foryc',
)
