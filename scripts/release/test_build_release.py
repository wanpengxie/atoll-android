import unittest
import build_release


class ReleaseMetadataTest(unittest.TestCase):
    def test_reads_actual_manifest_values(self):
        for field in ['minSdkVersion', 'sdkVersion']:
            self.assertEqual({'application_id': 'dev.atoll.app', 'version_code': 7,
                              'version_name': '0.2.1-beta', 'min_sdk': 26}, build_release.manifest_metadata(
                "package: name='dev.atoll.app' versionCode='7' versionName='0.2.1-beta' platformBuildVersionName='15'\n" + field + ":'26'\n"))

    def test_rejects_other_apps_debuggable_and_unsafe_filenames(self):
        base = "package: name='dev.atoll.app' versionCode='1' versionName='0.1.0'\nsdkVersion:'26'\n"
        for text in [base.replace('dev.atoll.app', 'other.app'), base + 'application-debuggable\n',
                     base.replace('0.1.0', '../bad')]:
            with self.assertRaises(ValueError): build_release.manifest_metadata(text)

    def test_requires_single_verified_sha256_signer(self):
        value = 'ABCDEF01' * 8
        report = 'Signer #1 certificate SHA-256 digest: ' + value + '\n'
        self.assertEqual(value.lower(), build_release.certificate_digest(report))
        for text in ['', report + report.replace('#1', '#2')]:
            with self.assertRaises(ValueError): build_release.certificate_digest(text)


if __name__ == '__main__':
    unittest.main()
