import unittest
from rsys_variants import manifest

NAMES=['katharsis_proxy-0.1.jar', 'katharsis_paper-0.1.jar', 'katharsis_paper-0.1-mc26.jar',
       'katharsis_neoforge-0.1.jar', 'katharsis_neoforge-0.1-mc1.21.11.jar', 'katharsis_neoforge-0.1-mc26.jar']


def release(names=NAMES):
    return dict(files=[dict(filename=name,status='READY') for name in names])


class ServerVariantsTest(unittest.TestCase):
    def test_six_targets_with_proxy_independent_of_minecraft(self):
        result=manifest(release())['variants']
        self.assertEqual(6,len(result))
        self.assertEqual({'platform':'velocity'},result[0]['selection'])
        self.assertEqual({'platform':'paper','minecraft_version':'1.21.x'},result[1]['selection'])
        self.assertEqual({'platform':'neoforge','minecraft_version':'1.21.1-1.21.10'},result[3]['selection'])
        self.assertEqual(NAMES,[v['defaultFile'] for v in result])
        self.assertTrue(all(v['files']==[v['defaultFile']] for v in result))

    def test_partial_unknown_duplicate_and_unfinished_fail(self):
        cases=[release(NAMES[:-1]), release(NAMES+['katharsis_paper-0.1-mc27.jar']), release(NAMES+[NAMES[0]])]
        unfinished=release();unfinished['files'][0]['status']='UPLOADING';cases.append(unfinished)
        for case in cases:
            with self.subTest(case=case),self.assertRaises(ValueError): manifest(case)


if __name__=='__main__': unittest.main()
