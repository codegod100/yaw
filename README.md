# Yaw

Small Datastar + Clojure site scaffolded for `devenv`.

## Run

```bash
devenv shell -- clojure -M -m yaw.core
```

Then open `http://localhost:8080`.

## Notes

- Backend: `http-kit` + `reitit`
- HTML: `hiccup`
- Datastar SSE adapter: official `starfederation/datastar-clojure` repo, pinned to commit `aed8ce2` (`v1.0.0-RC8`)
- Visual direction: black on yellow, inspired by `tonsky.me`
