#!/usr/bin/env python3
"""Проверки скриптов запуска и собранного installDist без открытия окна."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
VARIABLES = ("_JAVA_AWT_WM_NONREPARENTING", "MALLOC_ARENA_MAX", "__GL_YIELD")


@unittest.skipIf(os.name == "nt", "POSIX-скрипт запуска")
class StartScriptTests(unittest.TestCase):
    def launch(self, values):
        with tempfile.TemporaryDirectory(prefix="wex-launcher-") as tmp:
            directory = Path(tmp) / "папка с пробелами"
            (directory / "bin").mkdir(parents=True)
            shutil.copy2(ROOT / "app/packaging/start.sh", directory / "start.sh")
            (directory / "lib/runtime/bin").mkdir(parents=True)
            (directory / "lib/app").mkdir(parents=True)
            (directory / "lib/app/WEXdesktop.cfg").write_text(
                "[JavaOptions]\njava-options=-Dprobe=пара слов\njava-options=-Dsecond=2\n",
                encoding="utf-8",
            )
            stub = directory / "lib/runtime/bin/java"
            stub.write_text(
                "#!" + sys.executable + "\n"
                "import json, os, sys\n"
                "print(json.dumps({'args': sys.argv[1:], 'env': dict(os.environ)}))\n",
                encoding="utf-8",
            )
            stub.chmod(0o755)
            shutil.copy2(stub, directory / "bin/WEXdesktop")
            env = {k: v for k, v in os.environ.items()
                   if k not in VARIABLES and not k.startswith("WEX_ORIG_")}
            env.update(values)
            args = ["путь с пробелами/файл.apk", "--background"]
            result = subprocess.run(["sh", str(directory / "start.sh"), *args],
                                    env=env, capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0, result.stderr)
            data = json.loads(result.stdout)
            raw_args = data["args"]
            main = raw_args.index("wexdesktop") if "wexdesktop" in raw_args else -1
            self.assertEqual(raw_args[main + 1:] if main >= 0 else raw_args, args)
            self.jvm_args = raw_args[:main] if main >= 0 else []
            return data["env"]

    def test_jvm_options_are_read_without_splitting_spaces(self):
        self.launch({})
        self.assertIn("-Dprobe=пара слов", self.jvm_args)
        self.assertIn("-Dsecond=2", self.jvm_args)
        self.assertIn("-cp", self.jvm_args)
        self.assertLess(self.jvm_args.index("-Dprobe=пара слов"), self.jvm_args.index("-Dsecond=2"))

    def test_unset_values_are_saved_before_defaults(self):
        env = self.launch({})
        for name in VARIABLES:
            self.assertEqual(env.get("WEX_ORIG_" + name), "", name)
        self.assertEqual(env["MALLOC_ARENA_MAX"], "2")
        self.assertEqual(env["__GL_YIELD"], "USLEEP")
        self.assertEqual(env["_JAVA_AWT_WM_NONREPARENTING"], "1")

    def test_existing_values_are_saved_without_losing_spaces(self):
        values = {"MALLOC_ARENA_MAX": "4", "__GL_YIELD": "NOTHING",
                  "_JAVA_AWT_WM_NONREPARENTING": "original value"}
        env = self.launch(values)
        for name, value in values.items():
            self.assertEqual(env.get("WEX_ORIG_" + name), "=" + value, name)
        self.assertEqual(env["MALLOC_ARENA_MAX"], "4")
        self.assertEqual(env["__GL_YIELD"], "NOTHING")

    def test_empty_is_distinct_from_unset(self):
        env = self.launch({name: "" for name in VARIABLES})
        for name in VARIABLES:
            self.assertEqual(env.get("WEX_ORIG_" + name), "=", name)

    def test_outer_launcher_markers_are_not_overwritten(self):
        values = {"WEX_ORIG_" + name: "=outer value" for name in VARIABLES}
        env = self.launch(values)
        for name, value in values.items():
            self.assertEqual(env[name], value, name)


class DistributionTests(unittest.TestCase):
    def launcher(self, *args):
        source = (ROOT / "app/src/main/kotlin/WexLauncher.kt").read_text(encoding="utf-8")
        self.assertIn("--help", source, "CLI must return before starting the GUI")
        self.assertIn("--version", source, "CLI must return before starting the GUI")
        directory = ROOT / "app/build/install/app/bin"
        if os.name == "nt":
            command = [os.environ.get("COMSPEC", "cmd.exe"), "/d", "/c", str(directory / "app.bat")]
        else:
            command = [str(directory / "app")]
        self.assertTrue(Path(command[-1]).is_file(), "Сначала выполните :app:installDist")
        return subprocess.run([*command, *args], capture_output=True, text=True,
                              encoding="utf-8", timeout=30)

    def test_help_without_display(self):
        result = self.launcher("--help")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("файловый менеджер", result.stdout)
        self.assertIn("--background", result.stdout)
        self.assertIn("--version", result.stdout)

    def test_version_without_display(self):
        result = self.launcher("--version")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), "WEX Desktop 1.0.0")

    def test_windows_classpath_uses_the_jar_manifest(self):
        path = ROOT / "app/build/install/app/bin/app.bat"
        self.assertTrue(path.is_file(), "Сначала выполните :app:installDist")
        lines = path.read_text(encoding="utf-8").splitlines()
        classpath = next(line for line in lines if line.startswith("set CLASSPATH="))
        self.assertEqual(classpath, "set CLASSPATH=%APP_HOME%\\lib\\app.jar")

    def test_windows_script_has_no_linux_jvm_options(self):
        path = ROOT / "app/build/install/app/bin/app.bat"
        self.assertTrue(path.is_file(), "Сначала выполните :app:installDist")
        text = path.read_text(encoding="utf-8")
        self.assertFalse("TrimNativeHeapInterval" in text, "Linux-only heap option in Windows launcher")
        self.assertFalse("sun.awt.X11" in text, "X11 option in Windows launcher")


if __name__ == "__main__":
    unittest.main(verbosity=2)
