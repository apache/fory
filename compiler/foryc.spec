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
# Output: compiler/dist/foryc  (foryc.exe on Windows)
#
# UPX compression is intentionally NOT applied here.
# The CI workflow applies UPX manually per-platform for precise control
# (macOS aarch64 requires codesign after UPX; Windows needs a separate UPX path).

a = Analysis(
    ['fory_compiler/__main__.py'],
    pathex=['.'],
    binaries=[],
    datas=[],
    # fory_compiler has zero third-party pip dependencies.
    # All frontends use hand-written lexers/parsers (pure stdlib).
    # All generators are explicitly statically imported in generators/__init__.py.
    # PyInstaller's import tracer catches everything automatically.
    # This list is a complete belt-and-suspenders safety net — every module
    # in the fory_compiler package is enumerated here to prevent any future
    # refactoring from silently dropping a module from the binary.
    hiddenimports=[
        # Entry point chain
        'fory_compiler',
        'fory_compiler.cli',

        # IR layer — all 5 modules
        'fory_compiler.ir',
        'fory_compiler.ir.ast',
        'fory_compiler.ir.emitter',
        'fory_compiler.ir.validator',
        'fory_compiler.ir.type_id',
        'fory_compiler.ir.types',

        # Frontend base utilities
        'fory_compiler.frontend',
        'fory_compiler.frontend.base',
        'fory_compiler.frontend.utils',

        # FDL frontend — hand-written lexer/parser
        'fory_compiler.frontend.fdl',
        'fory_compiler.frontend.fdl.lexer',
        'fory_compiler.frontend.fdl.parser',

        # Proto frontend — hand-written lexer/parser/translator
        'fory_compiler.frontend.proto',
        'fory_compiler.frontend.proto.ast',
        'fory_compiler.frontend.proto.lexer',
        'fory_compiler.frontend.proto.parser',
        'fory_compiler.frontend.proto.translator',

        # FBS (FlatBuffers schema) frontend — all 4 submodules
        'fory_compiler.frontend.fbs',
        'fory_compiler.frontend.fbs.ast',
        'fory_compiler.frontend.fbs.lexer',
        'fory_compiler.frontend.fbs.parser',
        'fory_compiler.frontend.fbs.translator',

        # Generators — all 5 language backends
        'fory_compiler.generators',
        'fory_compiler.generators.base',
        'fory_compiler.generators.java',
        'fory_compiler.generators.python',
        'fory_compiler.generators.cpp',
        'fory_compiler.generators.rust',
        'fory_compiler.generators.go',
    ],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    # foryc uses: argparse, copy, os, sys, pathlib, typing, dataclasses,
    # enum, re, abc, io, collections, functools, itertools.
    # Everything below is confirmed unused — aggressively excluded to
    # reduce pre-UPX binary size.
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

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.zipfiles,
    a.datas,
    [],
    name='foryc',
    debug=False,
    bootloader_ignore_signals=False,
    strip=True,
    upx=False,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=True,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)
