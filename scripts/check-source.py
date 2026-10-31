#!/usr/bin/env python3
"""只读检查维护中的文本文件；格式和语法分别交给格式器与编译器。"""

from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[1]
SOURCE_DIRECTORIES = ("app/src", ".github", "docs", "scripts")
TEXT_SUFFIXES = {
    ".gradle", ".java", ".js", ".json", ".md", ".properties", ".py",
    ".sh", ".xml", ".yaml", ".yml", ".list", ".prop", ".txt",
}
ROOT_NAMES = {
    ".editorconfig", ".gitattributes", ".gitignore", ".prettierignore",
    "LICENSE",
}
CONFLICT_MARKER = re.compile(r"^(?:<{7}|>{7}|\|{7})(?:[ \t].*)?$")


def source_files():
    """只扫描项目维护的路径，不读取构建目录、设备采集或第三方依赖。"""
    files = {
        path for path in ROOT.iterdir()
        if path.is_file() and (path.name in ROOT_NAMES or path.suffix in TEXT_SUFFIXES)
    }
    for directory in SOURCE_DIRECTORIES:
        base = ROOT / directory
        if base.is_dir():
            files.update(path for path in base.rglob("*")
                         if path.is_file() and path.suffix in TEXT_SUFFIXES)
    wrapper = ROOT / "gradle/wrapper/gradle-wrapper.properties"
    if wrapper.is_file():
        files.add(wrapper)
    return sorted(path for path in files if not path.is_symlink())


def issues(path):
    raw = path.read_bytes()
    if raw.startswith(b"\xef\xbb\xbf"):
        yield 1, "UTF-8 不应包含 BOM"
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError:
        yield 1, "文件不是有效 UTF-8"
        return
    if b"\r" in raw:
        yield 1, "文本应使用 LF 换行"
    if raw and not raw.endswith(b"\n"):
        yield len(text.splitlines()) or 1, "缺少末尾换行"
    for number, line in enumerate(text.splitlines(), 1):
        if line.rstrip(" \t") != line:
            yield number, "存在行末空格或制表符"
        if CONFLICT_MARKER.fullmatch(line):
            yield number, "存在 Git 冲突起止标记"


def main():
    files = source_files()
    count = 0
    for path in files:
        for line, message in issues(path):
            print(f"{path.relative_to(ROOT)}:{line}: {message}", file=sys.stderr)
            count += 1
    if count:
        print(f"文本规范检查失败：{count} 项问题。", file=sys.stderr)
        return 1
    print(f"文本规范检查通过：{len(files)} 个文件。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
