import sys

from pyfory.util import is_little_endian, is_big_endian


def test_endian_flags():
    # 验证 is_little_endian 与 is_big_endian 与系统字节序一致
    assert is_little_endian == (sys.byteorder == "little")
    assert is_big_endian == (sys.byteorder == "big")
    # 两者应当互斥
    assert is_little_endian != is_big_endian

