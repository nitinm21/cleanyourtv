import contextlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch, Mock

SPEC = importlib.util.spec_from_file_location('release_updater', Path(__file__).resolve().parents[1] / 'tools/update-launcher.py')
updater = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(updater)


class ReleaseUpdaterTest(unittest.TestCase):
    def test_only_stable_plain_tags(self):
        self.assertEqual('v2.7.0', updater.release_tag('v2.7.0'))
        for tag in ['main', 'v2.7.0; echo nope', '../v2.7.0', 'v2.7.0-beta', '--help']:
            with self.assertRaises(ValueError): updater.release_tag(tag)

    def test_default_is_host_only_read_only(self):
        with patch.object(updater, 'latest', return_value='v2.7.0'), patch.object(updater, 'local_commit', return_value='abc'), patch.object(updater.subprocess, 'run') as run, contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, updater.main([]))
            run.assert_not_called()

    def test_newest_local_stable_tag_is_numeric(self):
        with patch.object(updater, 'run', return_value='v2.9.0\nv2.10.0\nv99.0.0-beta\nother'):
            self.assertEqual('v2.10.0', updater.latest(Path('.')))
        with patch.object(updater, 'run', return_value='main\nv3.0.0-rc1'):
            with self.assertRaisesRegex(ValueError, 'no stable release tags'):
                updater.latest(Path('.'))

    def test_export_uses_tag_not_dirty_working_tree(self):
        import subprocess
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp) / 'repo'
            repo.mkdir()
            def git(*args):
                return subprocess.check_output(['git', '-C', str(repo), *args], text=True).strip()
            git('init', '-q')
            (repo / 'tracked').write_text('release')
            git('add', '.')
            git('-c', 'user.name=Test', '-c', 'user.email=test@example.invalid', 'commit', '-qm', 'release')
            git('tag', 'v2.7.0')
            (repo / 'tracked').write_text('dirty')
            (repo / 'untracked').write_text('private')
            destination = Path(tmp) / 'export'
            updater.export_source(repo, updater.local_commit(repo, 'v2.7.0'), destination)
            self.assertEqual('release', (destination / 'tracked').read_text())
            self.assertFalse((destination / 'untracked').exists())
            self.assertEqual('dirty', (repo / 'tracked').read_text())
            with self.assertRaises(subprocess.CalledProcessError):
                updater.local_commit(repo, 'v9.9.9')

    def test_rejects_wrong_package_downgrade_sdk_and_key(self):
        good = (updater.PACKAGE, 8, 26)
        updater.validate_update(good, (updater.PACKAGE, 7, 26), 31, ['key'], ['key'])
        updater.validate_update(good, good, 31, ['key'], ['key'])
        for candidate, installed, sdk, a, b in [
            (('other',8,26),good,31,['key'],['key']),
            (good,('other',7,26),31,['key'],['key']),
            (good,(updater.PACKAGE,9,26),31,['key'],['key']),
            (good,good,25,['key'],['key']),
            (good,good,31,['new'],['old']),
        ]:
            with self.assertRaises(ValueError): updater.validate_update(candidate, installed, sdk, a, b)

    def test_split_or_shell_paths_are_refused(self):
        self.assertEqual('/data/app/~~key/pkg/base.apk', updater.remote_apk('package:/data/app/~~key/pkg/base.apk\n'))
        for text in ['', 'package:/data/app/pkg/base.apk\npackage:/data/app/pkg/split.apk', 'package:/data/app/pkg/../base.apk', 'package:/data/app/pkg/$(id).apk']:
            with self.assertRaises(ValueError): updater.remote_apk(text)

    def test_internet_permission_is_rejected(self):
        badging="package: name='com.gathin.tvhome' versionCode='8' versionName='2.7'\nsdkVersion:'26'"
        with patch.object(updater, 'run', side_effect=[badging,"uses-permission: name='android.permission.INTERNET'"]):
            with self.assertRaisesRegex(ValueError,'INTERNET'): updater.apk_info('aapt','app.apk')

    def test_install_refusal_does_not_reset_or_uninstall(self):
        dev=Mock()
        def shell(command, **kwargs):
            return {'pm path '+updater.PACKAGE:'package:/data/app/pkg/base.apk', 'getprop ro.build.version.sdk':'31', 'test -f /sdcard/Android/data/com.gathin.tvhome/files/config.json && echo yes':'', 'pm install -r /data/local/tmp/cleanyourtv-release-update.apk':'Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]'}.get(command,'')
        dev.shell.side_effect=shell
        with tempfile.TemporaryDirectory() as tmp, patch.object(updater,'apk_info',return_value=(updater.PACKAGE,7,26)), patch.object(updater,'certificates',return_value=['same']), patch.object(updater.subprocess,'run'):
            with self.assertRaisesRegex(RuntimeError,'install refused'): updater.install(dev,Path(tmp)/'new.apk',Path(tmp),(updater.PACKAGE,8,26),'signer','aapt')
        commands=[c.args[0] for c in dev.shell.call_args_list]
        self.assertFalse(any('uninstall' in c or 'pm clear' in c or 'set-home' in c or 'force-stop' in c for c in commands))
        self.assertIn('rm -f /data/local/tmp/cleanyourtv-release-update.apk',commands)
