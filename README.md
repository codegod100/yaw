# Yaw

Small Datastar + Clojure site scaffolded for `devenv`.

## Run

```bash
devenv shell -- clojure -M -m yaw.core
```

Then open `http://localhost:8080`.

## Tailnet Access

Use `tailscale serve` to expose the local app on your Tailnet:

```bash
tailscale up
devenv shell -- serve
```

That helper configures `tailscale serve --http=80 http://127.0.0.1:8080` and then starts the app.

Useful commands:

```bash
tailscale serve status
tailscale serve reset
```

## Notes

- Backend: `http-kit` + `reitit`
- HTML: `hiccup`
- Datastar SSE adapter: official `starfederation/datastar-clojure` repo, pinned to commit `aed8ce2` (`v1.0.0-RC8`)
- Visual direction: black on yellow, inspired by `tonsky.me`
