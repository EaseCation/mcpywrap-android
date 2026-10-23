"""The app must not activate a core which cannot execute its shared run operation."""
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'core/engineering/src/main/python'))
import android_core
from mcpywrap_core.api import Core
from mcpywrap_core.host import HostServices


class CoreCompatibilityTests(unittest.TestCase):
    def test_old_core_is_rejected_before_creating_health_check_files(self):
        class OldCore:
            def describe(self):return {'operations':['resolve','build','package','sync']}
        with tempfile.TemporaryDirectory() as directory,patch.object(android_core,'WORKSPACE',Path(directory)):
            with self.assertRaisesRegex(ValueError,'prepare_run'):android_core._health(OldCore())
            self.assertEqual(list(Path(directory).iterdir()),[])

    def test_published_core_passes_real_health_check(self):
        with tempfile.TemporaryDirectory() as directory,patch.object(android_core,'WORKSPACE',Path(directory)):
            core=Core(HostServices(allow_processes=False,python_executable=None))
            android_core._health(core)
            self.assertFalse(list(Path(directory).glob('build/core-check-*')))
