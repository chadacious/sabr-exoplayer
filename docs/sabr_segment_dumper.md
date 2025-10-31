# SABR Request/Response Dumper

This companion note documents a minimal Python endpoint you can use to capture
the JSON requests and UMP responses that the SABR client uploads via
`SabrSegmentDumper`. The script accepts the mirrored payloads and stores them on
disk so you can compare them with other clients (for example, the TypeScript
reference) while debugging.

## Quick Start

1. Create and activate a Python 3.9+ virtual environment.
2. Install the two required packages:
   ```bash
   pip install flask werkzeug
   ```
3. Save the script below as `sabr_dump_server.py`.
4. Run the server:
   ```bash
   python sabr_dump_server.py --output /path/to/dumps --port 5000
   ```
5. Configure your SABR integration to use this endpoint, e.g.:
   ```kotlin
   val dumper = SabrSegmentDumper(
       baseUrl = HttpUrl.get("http://<your-host>:5000/dump"),
       callFactory = okHttpClient,
       logger = logger,
   )
   ```

Each mirrored request or response will be written to the output directory using
the same naming scheme as the Android dump client (e.g.
`itag_137/rn_28_pos_1723862.json`).

## Sample Flask Server

```python
#!/usr/bin/env python3
"""
Minimal SABR dump endpoint.

Accepts POSTs from SabrSegmentDumper and stores them on disk so you can inspect
payloads offline. The server does no authentication—only use it on trusted
networks or behind a firewall.
"""

import argparse
import json
import os
import pathlib
import time
from typing import Any, Dict

from flask import Flask, jsonify, request
from werkzeug.serving import WSGIRequestHandler


def create_app(output_dir: pathlib.Path) -> Flask:
    app = Flask(__name__)

    @app.post("/dump")
    def dump_payload():
        """Write the mirrored SABR payload to disk."""
        payload: Dict[str, Any] = request.get_json(force=True, silent=False)

        # Expect the Android client to send meta fields describing the file path.
        rel_path = payload.get("relativePath")
        contents = payload.get("contents")

        if not rel_path or contents is None:
            return jsonify({"error": "missing relativePath or contents"}), 400

        output_path = output_dir / rel_path
        output_path.parent.mkdir(parents=True, exist_ok=True)

        if isinstance(contents, str):
            data = contents
        else:
            data = json.dumps(contents, indent=2, sort_keys=True)

        # Prefix with a timestamp to make debugging chronological order easier.
        timestamp = time.strftime("%Y%m%d-%H%M%S")
        output_file = output_path.with_suffix(
            output_path.suffix or ".json"
        )
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_file = output_path.parent / f"{timestamp}_{output_file.name}"

        with open(output_file, "w", encoding="utf-8") as fh:
            fh.write(data)

        app.logger.info("Wrote %s", output_file)
        return jsonify({"status": "ok", "path": str(output_file)})

    return app


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--output",
        type=pathlib.Path,
        required=True,
        help="Directory where mirrored payloads will be stored.",
    )
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=5000)
    args = parser.parse_args()

    args.output.mkdir(parents=True, exist_ok=True)

    # Ensure we log the client IP in Werkzeug logs.
    WSGIRequestHandler.protocol_version = "HTTP/1.1"

    app = create_app(args.output)
    app.run(host=args.host, port=args.port, debug=False)


if __name__ == "__main__":
    main()
```

## Wiring Notes

- The Android dumper sends two fields: `relativePath` (e.g.
  `itag_135/rn_51_pos_1723862.json`) and `contents` (either JSON or raw text).
  Adjust the script if you change the payload contract.
- For remote debugging you can deploy the server behind an authenticated tunnel.
- Remember to disable `segmentDumper` in production builds to avoid transmitting
  playback data.
