# On-device transcription

`whisper.cpp/` is [whisper.cpp](https://github.com/ggml-org/whisper.cpp) 1.8.5
(MIT, see `whisper.cpp/LICENSE`), trimmed to what an Android CPU build needs:
the library sources and ggml's CPU backend. Examples, tests, bindings, models
and the GPU backends were left out.

`scribe_jni.cpp` is the only code of ours here: load a model, transcribe one
piece of 16 kHz mono audio, free the model. `LocalEngine.kt` does the rest.

Models are not bundled; the app downloads one from
`huggingface.co/ggerganov/whisper.cpp` when asked to in Settings.
