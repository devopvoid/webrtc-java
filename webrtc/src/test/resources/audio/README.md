# VAD fixture

`vad-speech-16k.pcm` is one second of mono signed 16-bit little-endian PCM at
16000 Hz. It is synthetic speech from the phrase "The quick brown fox jumps
over the lazy dog.", generated with eSpeak NG 1.51, voice `en-us`, default
settings. The first 200 ms were skipped; the next second was retained after
resampling from 22050 Hz to 16000 Hz with linear interpolation in Python.

This generated audio fixture is provided under the repository's Apache-2.0
license. No speech engine or external download is needed to run the tests.
