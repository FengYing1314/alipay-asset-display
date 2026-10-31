#!/usr/bin/env python3
"""使用锁定工具统一源码格式；检查模式不改源码。"""

import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
JAVA_FORMAT_VERSION = "1.37.0"
JAVA_FORMAT_SHA256 = "834b2a0c38cb774953322a84b5ca3f2f40dd3156650b3cd44d3b744345962f7a"
JAVA_FORMAT_NAME = f"google-java-format-{JAVA_FORMAT_VERSION}-all-deps.jar"
JAVA_FORMAT_URL = (
    "https://repo.maven.apache.org/maven2/com/google/googlejavaformat/"
    f"google-java-format/{JAVA_FORMAT_VERSION}/{JAVA_FORMAT_NAME}"
)
PRETTIER_TARGETS = [
    "package.json",
    "package-lock.json",
    ".prettierrc.json",
    "*.md",
    "docs/**/*.md",
    ".github/**/*.{yml,yaml,md,json}",
    "app/src/**/*.js",
]


def digest(data):
    return hashlib.sha256(data).hexdigest()


def java_formatter():
    """首次下载也校验固定摘要，不把网络返回直接当作可执行工具。"""
    target = ROOT / ".local" / "tools" / JAVA_FORMAT_NAME
    if target.exists():
        if digest(target.read_bytes()) != JAVA_FORMAT_SHA256:
            raise RuntimeError("Java 格式工具缓存校验失败，请检查 .local/tools 下的对应文件")
        return target

    with urllib.request.urlopen(JAVA_FORMAT_URL, timeout=30) as response:
        data = response.read(10 * 1024 * 1024 + 1)
    if digest(data) != JAVA_FORMAT_SHA256:
        raise RuntimeError("Java 格式工具下载校验失败，未执行下载内容")

    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=target.parent, delete=False) as stream:
            temporary = Path(stream.name)
            stream.write(data)
        temporary.replace(target)
    finally:
        if temporary is not None and temporary.exists():
            temporary.unlink()
    return target


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true", help="只检查，不改源码")
    mode.add_argument("--write", action="store_true", help="应用格式")
    args = parser.parse_args()

    prettier = ROOT / "node_modules" / "prettier" / "bin" / "prettier.cjs"
    node = shutil.which("node")
    java_home = os.environ.get("JAVA_HOME")
    java = str(Path(java_home) / "bin" / "java") if java_home else shutil.which("java")
    if not node or not prettier.is_file():
        raise RuntimeError("需要 Node.js 22+ 和项目本地 Prettier，请先运行 npm ci --ignore-scripts")
    if not java:
        raise RuntimeError("需要 JDK 21，请配置 JAVA_HOME 或 PATH")

    sources = sorted(str(path.relative_to(ROOT)) for path in (ROOT / "app/src").rglob("*.java"))
    java_mode = ["--dry-run", "--set-exit-if-changed"] if args.check else ["--replace"]
    commands = [
        [java, "-jar", str(java_formatter()), "--aosp", "--skip-reflowing-long-strings"]
        + java_mode
        + sources,
        [node, str(prettier), "--check" if args.check else "--write",
         "--no-error-on-unmatched-pattern"] + PRETTIER_TARGETS,
    ]
    failed = False
    for command in commands:
        if subprocess.run(command, cwd=ROOT, check=False).returncode:
            failed = True
    return 1 if failed else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, RuntimeError) as error:
        print(f"格式检查失败：{error}", file=sys.stderr)
        sys.exit(1)
