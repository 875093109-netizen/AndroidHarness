"""Lightweight unit checks; run with: python3 -m unittest discover -s zhcn -p 'test_*.py'."""
import tempfile
import unittest
from pathlib import Path
from apply_zhcn import load_dictionary, translate_file

class LocalizationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.d, warnings = load_dictionary(Path(__file__).with_name('translations.tsv'))
        assert not warnings, warnings

    def test_translates_screen_labels(self):
        src = 'Text("Choose model")\nText(text = "Save")\nText("Search models or type an ID")'
        actual, hits = translate_file(src, self.d)
        self.assertIn('Text("选择模型")', actual)
        self.assertIn('Text(text = "保存")', actual)
        self.assertIn('Text("搜索模型或输入模型 ID")', actual)
        self.assertEqual(sum(hits.values()), 3)

    def test_keeps_model_ids_and_control_values(self):
        src = 'val model = "deepseek-flash"\nval label = "Save"\nText("Save")'
        actual, _ = translate_file(src, self.d)
        self.assertIn('"deepseek-flash"', actual)
        self.assertIn('val label = "Save"', actual)
        self.assertIn('Text("保存")', actual)

    def test_idempotent(self):
        src = 'Text("Choose model")\nText("Continue anyway")'
        a, _ = translate_file(src, self.d)
        b, hits = translate_file(a, self.d)
        self.assertEqual(a, b)
        self.assertEqual(sum(hits.values()), 0)

    def test_interpolation_is_preserved(self):
        src = 'Text("Add any model ID supported by $providerName.")'
        actual, _ = translate_file(src, self.d)
        self.assertIn('$providerName', actual)

if __name__ == '__main__':
    unittest.main()
