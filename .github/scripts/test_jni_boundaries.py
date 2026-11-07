"""Regression tests for the JNI source structure check."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('boundaries', Path(__file__).with_name('check-jni-boundaries.py'))
boundaries = importlib.util.module_from_spec(spec)
spec.loader.exec_module(boundaries)


class BoundaryTests(unittest.TestCase):
    def protected(self, body):
        return boundaries.protected(boundaries.mask(body))

    def test_complete_try(self):
        self.assertTrue(self.protected('try { work(); } catch (...) { ThrowCxxJavaException(env); return 0; }'))

    def test_inner_try_is_insufficient(self):
        self.assertFalse(self.protected('lease(); try { work(); } catch (...) { ThrowCxxJavaException(env); }'))
        self.assertFalse(self.protected('try { work(); } catch (...) { ThrowCxxJavaException(env); } work();'))

    def test_catch_must_translate_all_exceptions(self):
        self.assertFalse(self.protected('try { work(); } catch (const std::exception & e) { ThrowCxxJavaException(env); }'))
        self.assertFalse(self.protected('try { work(); } catch (...) { log(); }'))

    def test_complete_lambda(self):
        self.assertTrue(self.protected('return jni::CallFromJava(env, [&]() -> jobject { return work(); });'))
        self.assertTrue(self.protected('jni::CallFromJava(env, [&]() { work(); });'))
        self.assertFalse(self.protected('jni::CallFromJava(env, [&]() { work(); }); lease();'))
        self.assertFalse(self.protected('lease(); jni::CallFromJava(env, [&]() { work(); });'))

    def test_comments_and_literals(self):
        source = '''// JNIEXPORT void JNICALL Java_fake(JNIEnv * env) { }
JNIEXPORT void JNICALL Java_real(JNIEnv * env, jobject caller) {
    // } catch (...) { ThrowCxxJavaException(env); }
    const char * text = R"tag( } { " )tag";
    lease();
}'''
        entries = list(boundaries.entries(source))
        self.assertEqual(1, len(entries))
        self.assertEqual('Java_real', entries[0][0][2])
        self.assertFalse(boundaries.protected(entries[0][3]))


if __name__ == '__main__':
    unittest.main()
