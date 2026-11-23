#!/bin/sh
# Downloads the models the audio host test runs against (about 2 GB) into $1.
set -e
DIR=$1
HF=https://huggingface.co

fetch() {
    [ -s "$DIR/$1" ] && return 0
    mkdir -p "$(dirname "$DIR/$1")"
    curl -fL --retry 3 -C - -o "$DIR/$1.part" "$HF/$2"
    mv "$DIR/$1.part" "$DIR/$1"
}

fetch ggml-base-q5_1.bin ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin

for f in duration_predictor.onnx text_encoder.onnx vector_estimator.onnx vocoder.onnx tts.json unicode_indexer.json; do
    fetch "supertonic/$f" "Supertone/supertonic-3/resolve/main/onnx/$f"
done
for v in F1 M1; do
    fetch "supertonic/$v.json" "Supertone/supertonic-3/resolve/main/voice_styles/$v.json"
done

CB=onnx-community/chatterbox-multilingual-ONNX/resolve/main
fetch chatterbox/tokenizer.json "$CB/tokenizer.json"
fetch chatterbox/default_voice.wav "$CB/default_voice.wav"
for m in embed_tokens language_model_q4 conditional_decoder speech_encoder; do
    fetch "chatterbox/$m.onnx" "$CB/onnx/$m.onnx"
    fetch "chatterbox/$m.onnx_data" "$CB/onnx/$m.onnx_data"
done
