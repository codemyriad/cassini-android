"""Prepare identical inputs for the isolated CPU/GPU comparison, not a production frontend.
Uses the official Android sample's 512-point STFT / preemphasis / sample normalization.
This is not the repaired desktop Cassini feature extractor.
"""

import librosa, numpy as np, wave, pathlib, sys, hashlib, json

source = pathlib.Path(sys.argv[1])
destination = pathlib.Path(sys.argv[2])
destination.mkdir(parents=True, exist_ok=True)
with wave.open(str(source)) as f:
    assert (
        f.getframerate() == 16000
        and f.getnchannels() == 1
        and f.getsampwidth() == 2
        and f.getnframes() == 480000
    )
    pcm = (
        np.frombuffer(f.readframes(f.getnframes()), dtype="<i2").astype(np.float32)
        / 32768
    )
for index in range(6):
    x = pcm[index * 80000 : (index + 1) * 80000]
    x = np.concatenate((x[:1], x[1:] - 0.97 * x[:-1]))
    mel = librosa.feature.melspectrogram(
        y=x, sr=16000, n_fft=512, hop_length=160, n_mels=128, power=2
    )
    mel = np.log(mel + 2**-24)[:, :500]
    mel = (mel - mel.mean(axis=1, keepdims=True)) / (
        mel.std(axis=1, ddof=1, keepdims=True) + 1e-5
    )
    (destination / f"features-{index}.bin").write_bytes(mel.astype("<f4").tobytes())
    print(index, mel.shape)

(destination / "input.json").write_text(
    json.dumps(
        {
            "audioSha256": hashlib.sha256(source.read_bytes()).hexdigest(),
            "audioMs": 30000,
            "chunks": 6,
            "overlapMs": 0,
            "librosa": librosa.__version__,
            "numpy": np.__version__,
            "featuresOnHost": True,
        },
        indent=2,
    )
)
