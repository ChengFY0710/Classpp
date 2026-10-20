"""思源宋体 Heavy 子集再生成脚本（OFL 授权，允许子集化分发）。

字表在 subset-chars.txt（24 节气 + 常见节日 + 数字 0-9）。新增用衬线展示的文案时，
先把用字补进字表再重跑本脚本，产物覆盖 res/font 里的 otf——子集缺字不报错，
运行时会静默回退系统无衬线，所以新字必须先进字表。

用法：
  python tools/fonts/subset-font.py <思源宋体Heavy原始OTF路径> [输出otf路径]

原始字体（约 11MB）不入仓库，缺失时先下载（Windows curl 须加 --ssl-no-revoke，
否则 schannel 证书吊销检查直接失败）：
  curl --ssl-no-revoke -L -o build/fonts/SourceHanSerifCN-Heavy.otf \
    https://raw.githubusercontent.com/adobe-fonts/source-han-serif/release/SubsetOTF/CN/SourceHanSerifCN-Heavy.otf

依赖：pip install fonttools
"""

import subprocess
import sys
from pathlib import Path

_REPO_ROOT = Path(__file__).resolve().parents[2]
_DEFAULT_OUTPUT = _REPO_ROOT / "app/src/main/res/font/source_han_serif_heavy_subset.otf"
_CHARS_FILE = Path(__file__).with_name("subset-chars.txt")


def main() -> None:
    if len(sys.argv) not in (2, 3):
        raise SystemExit(__doc__)
    source = Path(sys.argv[1])
    output = Path(sys.argv[2]) if len(sys.argv) == 3 else _DEFAULT_OUTPUT
    if not source.is_file():
        raise SystemExit(f"找不到原始字体：{source}（下载命令见文件头注释）")

    output.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        [
            sys.executable, "-m", "fontTools.subset",
            str(source),
            f"--text-file={_CHARS_FILE}",
            f"--output-file={output}",
        ],
        check=True,
    )
    print(f"{output}  {output.stat().st_size} bytes")


if __name__ == "__main__":
    main()
