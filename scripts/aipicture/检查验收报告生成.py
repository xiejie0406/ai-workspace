"""定向检查验收报告中的链接不会截断说明正文；只读、不访问网络。"""

from html.parser import HTMLParser
import importlib.util
from pathlib import Path
import sys
import unittest


GENERATOR = Path(__file__).with_name("生成迁移验收报告.py")
SPEC = importlib.util.spec_from_file_location("aipicture_verification_report", GENERATOR)
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class VisibleText(HTMLParser):
    def __init__(self):
        super().__init__()
        self.parts = []

    def handle_data(self, data):
        self.parts.append(data)


class ReportLinkTextTests(unittest.TestCase):
    def render(self, value):
        record = MODULE.DEFAULT_INPUT
        renderer = MODULE.Renderer(record, record.with_name("verification-report.html"))
        markup = renderer.inline(value)
        text = VisibleText()
        text.feed(markup)
        self.assertEqual(renderer.missing_links, set())
        return markup, "".join(text.parts)

    def test_multiple_local_links_keep_before_between_and_after_text(self):
        _, visible = self.render(
            "前置独立预期：[唯一记录](业务验收记录.md)；中间原失败仍保留，"
            "`loaded=false`；[旧日志](本地评测与OCR输入定向核查/评测心跳-修复前六失败.log)"
            "之后健康/失租/取消必须分别判定，**结尾说明不能遗漏**。"
        )
        self.assertEqual(visible, "前置独立预期：唯一记录；中间原失败仍保留，"
                         "loaded=false；旧日志之后健康/失租/取消必须分别判定，结尾说明不能遗漏。")

    def test_link_target_and_untrusted_text_remain_escaped_without_losing_tail(self):
        markup, visible = self.render(
            '说明 <script>不可执行</script>；[外部说明](https://example.com/?a=1&b=2)'
            ' 后续仍未通过 & "待确认"；[本地记录](业务验收记录.md) 结束。'
        )
        self.assertEqual(visible, '说明 <script>不可执行</script>；外部说明'
                         ' 后续仍未通过 & "待确认"；本地记录 结束。')
        self.assertNotIn("<script>", markup)
        self.assertIn("&lt;script&gt;", markup)
        self.assertIn("?a=1&amp;b=2", markup)


if __name__ == "__main__":
    unittest.main()
