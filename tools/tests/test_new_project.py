"""使用已发布核心验证 Android 新建适配，不替身生成器。"""
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2]/'core/engineering/src/main/python'))
from new_project import generate
from mcpywrap_core.api import Core
from mcpywrap_core.host import HostServices
from mcpywrap_core.minecraft.template.generate_mod_files import generate_mod_framework


class NewProjectTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.core = Core(HostServices(allow_processes=False, python_executable=None))
        self.options = dict(title='手机作品', modName='CrystalMod', version='1.2.3',
            scriptDir='CrystalScripts', serverSystem='WorldServer', clientSystem='HudClient')

    def test_preview_matches_real_files_and_desktop_generator(self):
        preview = generate(self.root, self.options, self.core)
        self.assertFalse((self.root/'projects').exists())
        self.assertEqual(list((self.root/'build').iterdir()), [])
        created = generate(self.root, dict(self.options, previewSignature=preview['signature']), self.core, preview=False)
        self.assertEqual(preview['files'], created['files'])
        stage = Path(created['stage'])
        self.assertEqual(created['serverClass'], 'CrystalScripts.server.WorldServer.WorldServer')
        reference = self.root/'desktop'
        ok, error = generate_mod_framework(str(reference), 'CrystalMod', '1.2.3', 'WorldServer',
            created['serverClass'], 'HudClient', created['clientClass'], 'CrystalScripts')
        self.assertTrue(ok, error)
        for file in reference.rglob('*.py'):
            self.assertEqual(file.read_bytes(), (stage/'behavior_pack'/file.relative_to(reference)).read_bytes())
        self.assertTrue(Path(self.core.execute('build', stage)['output']).is_dir())
        self.assertTrue(Path(self.core.execute('package', stage)['artifact']).is_file())

    def test_changed_options_require_a_new_preview(self):
        preview = generate(self.root, self.options, self.core)
        with self.assertRaisesRegex(ValueError, '重新查看'):
            generate(self.root, dict(self.options, modName='OtherMod', previewSignature=preview['signature']), self.core, False)
        self.assertEqual(list((self.root/'build').iterdir()), [])

    def test_keywords_paths_and_injected_code_rejected_before_generation(self):
        for key,value in [('title','../escape'),('modName','class'),('modName','你好'),('scriptDir','../scripts'),
                          ('clientSystem','__init__'),('serverSystem','bad"\ncode'),('version','1"\ncode')]:
            with self.subTest(key=key,value=value),self.assertRaises(ValueError):
                generate(self.root, dict(self.options, **{key:value}), self.core)
        self.assertFalse((self.root/'build').exists())

    def test_existing_project_is_never_overwritten(self):
        target = self.root/'projects'/self.options['title'];target.mkdir(parents=True)
        file = target/'user.py';file.write_text('user source')
        with self.assertRaisesRegex(ValueError, '同名'):
            generate(self.root, self.options, self.core)
        self.assertEqual(file.read_text(), 'user source')

    def test_generation_failure_cleans_partial_stage(self):
        with patch('mcpywrap_core.minecraft.template.generate_mod_files.generate_mod_framework', return_value=(False,'disk full')):
            with self.assertRaisesRegex(ValueError,'disk full'):
                generate(self.root, self.options, self.core)
        self.assertEqual(list((self.root/'build').iterdir()), [])
