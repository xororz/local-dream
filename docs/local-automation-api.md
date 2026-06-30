# Local Automation API Proposal

This document proposes a supported local API for controlling Local Dream from
Android terminal environments, automation apps, and companion apps without any
cloud dependency.

## Motivation

Local Dream is especially useful on phones because the model and accelerator are
already on the device. Advanced users often want to connect it to local tools
such as Termux, proot containers, Shizuku/rish scripts, Tasker/MacroDroid, chat
assistants, or personal gallery workflows.

The current native backend already exposes useful local endpoints such as
`/health`, `/generate`, and `/upscale` on port `8081`, but that surface is not a
stable app-level contract. External clients still need to know how to start the
right backend, wait for true readiness, recover from failures, cancel work, and
keep Android from throttling the process while a request is running.

A documented, supported, pure-local API would make these integrations reliable
while preserving Local Dream's offline-first privacy model.

## Goals

- Provide a stable localhost API that can be called from Android terminal
  environments and other local apps.
- Keep all image generation and upscaling on-device. The API should not require
  any cloud service.
- Expose clear backend readiness, generation progress, completion, cancellation,
  and error states.
- Make background operation reliable by owning the required Android foreground
  service and wake/keepalive behavior inside Local Dream.
- Keep the API secure by default.

## Non-goals

- Exposing a public network server by default.
- Replacing the existing in-app UI.
- Allowing arbitrary remote clients to control the device without explicit user
  opt-in.

## Suggested API Surface

The exact paths are only a proposal. The important part is the contract.

### Backend lifecycle

`GET /api/v1/health`

Returns app-level readiness, not only whether a socket is listening.

```json
{
  "ok": true,
  "backend": "running",
  "modelId": "example_model",
  "backendType": "sdxl",
  "width": 1024,
  "height": 1024
}
```

`POST /api/v1/backend/start`

Starts or switches the backend using an explicit model configuration.

```json
{
  "modelId": "example_model",
  "backendType": "sdxl",
  "width": 1024,
  "height": 1024
}
```

`POST /api/v1/backend/stop`

Stops the backend when no generation is in progress.

### Model discovery

`GET /api/v1/models`

Returns installed models, supported backend type, default resolution, and
whether the model is ready for generation.

### Generation

`POST /api/v1/generate`

Accepts the same core fields used by the native `/generate` endpoint:

```json
{
  "prompt": "cinematic portrait",
  "negative_prompt": "low quality, text, watermark",
  "steps": 6,
  "cfg": 1.0,
  "scheduler": "lcm",
  "width": 1024,
  "height": 1024,
  "denoise_strength": 0.6,
  "image": "base64-encoded input image for img2img, optional",
  "show_diffusion_process": false
}
```

The response can stay SSE-compatible:

```text
event: progress
data: {"type":"progress","step":1,"total_steps":6}

event: complete
data: {"type":"complete","image":"...","format":"raw","width":1024,"height":1024}
```

Errors should also be returned as structured events:

```text
event: error
data: {"type":"error","code":"MODEL_NOT_READY","message":"Model is not loaded"}
```

`POST /api/v1/generate/cancel`

Cancels the current generation and releases the pipeline cleanly.

### Upscale

`POST /api/v1/upscale`

Supports the existing local upscaling behavior with a stable app-level contract.
The body can either continue to be raw RGB bytes with width/height headers, or
use a JSON/base64 form for easier terminal and companion-app integration.

## Android Integration Options

Two implementation paths seem reasonable:

1. A Local Dream-owned foreground service that exposes a loopback HTTP API.
2. A bound service or AIDL/Messenger interface for Android apps, with the HTTP
   API kept for terminal clients.

In both cases, Local Dream should own model startup, foreground notification,
keepalive behavior, request serialization, cancellation, and process recovery.
External clients should not need to start internal services directly.

## Security

Recommended defaults:

- Bind to `127.0.0.1` only.
- Keep any `0.0.0.0` / LAN listening option disabled by default.
- Require an explicit in-app toggle before accepting local automation requests.
- Optionally generate a local token for clients that need long-lived access.
- If an Android service is exported, protect it with a custom permission and
  document the trust model.
- Never send prompts, images, or model data to a remote server as part of this
  API.

## Terminal Example

With the API enabled, a terminal client could generate an image with:

```sh
curl --no-buffer \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  --data '{"prompt":"cinematic portrait","scheduler":"lcm","steps":6,"cfg":1}' \
  http://127.0.0.1:8081/api/v1/generate
```

## Acceptance Criteria

- A local terminal client can start a model, wait for readiness, generate an
  image, receive progress, receive the final image, and cancel a request.
- Requests either complete or return a structured error. They should not hang
  indefinitely after returning HTTP headers.
- Generation remains local-only and works without internet access after models
  are installed.
- The API behavior is documented and versioned enough for third-party tools to
  rely on it.
