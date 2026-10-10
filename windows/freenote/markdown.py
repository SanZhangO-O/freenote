"""Markdown 渲染（预览用）。优先使用 markdown-it-py，缺失时退化为纯文本。

`[[便签标题]]` 双向链接：渲染为 freenote-note: 伪协议链接，预览点击后
由编辑页跳转到同名便签（见 ui/editor_page.py 的 anchorClicked 处理）。
"""

from __future__ import annotations

import html
from urllib.parse import quote

try:  # pragma: no cover - 取决于运行环境
    from markdown_it import MarkdownIt

    _MD = MarkdownIt("commonmark", {"breaks": True})
    for _rule in ("table", "strikethrough"):
        try:
            _MD.enable(_rule)
        except Exception:  # noqa: BLE001
            pass

    def _wiki_link_rule(state, silent):
        """行内规则：[[标题]] → <a href="freenote-note:标题">。"""
        if not state.src.startswith("[[", state.pos):
            return False
        end = state.src.find("]]", state.pos + 2)
        if end == -1:
            return False
        title = state.src[state.pos + 2:end].strip()
        if not title or "\n" in title:
            return False
        if not silent:
            open_token = state.push("link_open", "a", 1)
            open_token.attrs = {"href": "freenote-note:" + quote(title), "class": "wikilink"}
            text_token = state.push("text", "", 0)
            text_token.content = title
            state.push("link_close", "a", -1)
        state.pos = end + 2
        return True

    try:
        _MD.inline.ruler.before("link", "freenote_wiki", _wiki_link_rule)
    except Exception:  # noqa: BLE001 - 版本差异时退化为普通文本
        pass
except Exception:  # noqa: BLE001
    _MD = None


def render_body(text: str) -> str:
    if not text:
        return ""
    if _MD is None:
        return f"<pre>{html.escape(text)}</pre>"
    return _MD.render(text)


def render_document(text: str, theme: str) -> str:
    """带主题样式的完整 HTML，用于 QTextBrowser。"""
    if theme == "dark":
        text_color, code_bg, border, link = "#e7e0e8", "#2b2930", "#3d3a44", "#cfbcff"
    else:
        text_color, code_bg, border, link = "#1d1b20", "#f0eef4", "#e2e0e6", "#6750a4"

    body = render_body(text) if text.strip() else "<p style='opacity:.5'>（无内容）</p>"
    return f"""
    <html><head><style>
      body {{ color: {text_color}; font-family: "Microsoft YaHei UI", "Segoe UI", sans-serif;
             font-size: 14px; line-height: 1.65; }}
      h1, h2, h3 {{ line-height: 1.3; }}
      h1 {{ font-size: 22px; }} h2 {{ font-size: 19px; }} h3 {{ font-size: 16px; }}
      code {{ background: {code_bg}; border-radius: 4px; padding: 1px 4px;
              font-family: "Cascadia Mono", Consolas, monospace; }}
      pre {{ background: {code_bg}; border: 1px solid {border}; border-radius: 8px; padding: 10px; }}
      pre code {{ background: transparent; padding: 0; }}
      blockquote {{ border-left: 3px solid {border}; margin-left: 0; padding-left: 12px; opacity: .85; }}
      a {{ color: {link}; }}
      a.wikilink {{ color: {link}; text-decoration: underline; text-decoration-style: dotted; }}
      table {{ border-collapse: collapse; }}
      th, td {{ border: 1px solid {border}; padding: 4px 8px; }}
      hr {{ border: none; border-top: 1px solid {border}; }}
      img {{ max-width: 100%; }}
    </style></head><body>{body}</body></html>
    """
