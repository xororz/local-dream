# Local Dream Image

A **standalone, image-only webapp** built from the `local-webdream` repository.
It is not PilliVesh and does not import or depend on it. It runs in the same
place as PilliVesh: a Debian/Termux guest on your Snapdragon 870 phone, served
on `127.0.0.1`.

## What it is

- A self-contained Python stdlib server (`server.py`) + static web UI (`web/`).
- Uses the **portable engine from the Local Dream family**: `stable-diffusion.cpp`
  (upstream of the `happyyzy/stable-diffusion.cpp` submodule this repo uses for
  its DiT pipelines). Its native `sdcpp` HTTP API is wrapped in `server.py`.
- Reuses Local Dream's design ideas: lazy model process on a fixed loopback
  port, curated model catalog, allow-listed downloads, progress + final image
  streaming, one resident model, RAM gate.
- The Android APK, QNN SDK and Bionic binary are **not** used — they cannot run
  in Debian. See [the analysis in PilliVesh's docs](../docs/local-dream-analysis.md)
  for the reverse-engineering details.

## Run on the phone

```sh
cd local-Webdream/standalone_image
chmod +x bin/*.sh
bin/install-engine.sh          # once: build sd-server, CPU only
python3 server.py
# open http://127.0.0.1:8090/
```

Ports: webapp `8090`, engine `8092` (no conflict with PilliVesh's 8080/8091).

1. Models → Download a curated SD1.5 GGUF → Load.
2. Create → prompt, negative prompt, size, steps, seed → Generate.
3. Gallery keeps the last 12 results in browser localStorage.

HF search in the Models box is read-only. To enable in-app download of another
repo, add an entry to `CATALOG` in `server.py` (host must be `huggingface.co`,
file must be `.gguf`).

## Limits

- CPU-only inference on Snapdragon 870 / Hexagon V68. Expect slow generation;
  start at 256×256 / 10 steps, enable 512 only with patience.
- No NPU: precompiled Qualcomm QNN assets target newer chipsets, and the
  Android-only Local Dream core cannot run in Debian.
- First run needs a build toolchain and ~1 GB of model storage.
