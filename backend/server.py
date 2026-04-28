"""
dvspf backend — tiny Flask service that brokers party-finder listings between
guildmate Minecraft clients.

A "listing" is just an ad: who's leading, what they're doing, how many players
they want, and a free-text note. Joining is handled client-side by sending the
leader a /msg, so this service has no concept of party membership.

Endpoints
---------
GET    /api/listings              -> list all current listings, freshest first
POST   /api/listings              -> create one. body: {leader, category, size, note}
DELETE /api/listings/<id>         -> delete one
POST   /api/listings/<id>/full    -> toggle full flag. body: {"full": true|false}
GET    /api/health                -> {"status": "ok", "count": N}

Auth
----
Optional. If the env var DVSPF_API_KEY is set, all /api/* requests must send
matching `X-Guild-Key: <key>` header. Trivial to spoof if leaked, but keeps
random internet traffic out for a small guild.

Storage
-------
In-memory dict. Listings older than TTL_SECONDS (default 30 min) are reaped on
every request. Restart wipes everything — fine for an MVP, since these are
ephemeral "looking for group right now" ads.

Run locally
-----------
    pip install -r requirements.txt
    python server.py            # listens on :8000

Deploy
------
There's a Dockerfile next to this. Works on Fly.io, Render.com, Railway,
Coolify, or any plain VM.
"""
from __future__ import annotations

import os
import threading
import time
import uuid

from flask import Flask, abort, jsonify, request

app = Flask(__name__)

# --- config ----------------------------------------------------------------

API_KEY = os.environ.get("DVSPF_API_KEY", "").strip() or None
TTL_SECONDS = int(os.environ.get("DVSPF_TTL_SECONDS", "1800"))  # 30 min default
PORT = int(os.environ.get("PORT", "8000"))

# --- storage ---------------------------------------------------------------

_LOCK = threading.Lock()
_LISTINGS: dict[str, dict] = {}  # id -> listing dict


def _now_ms() -> int:
    return int(time.time() * 1000)


def _expire_old() -> None:
    """Drop listings older than TTL_SECONDS. Called on every request."""
    cutoff = _now_ms() - TTL_SECONDS * 1000
    with _LOCK:
        gone = [k for k, v in _LISTINGS.items() if v["createdAtMillis"] < cutoff]
        for k in gone:
            del _LISTINGS[k]


# --- middleware ------------------------------------------------------------


@app.before_request
def _gate():
    # Health check is unauthenticated so monitors don't need the key.
    if request.path == "/api/health":
        return
    if API_KEY and request.headers.get("X-Guild-Key", "") != API_KEY:
        abort(403, description="missing or wrong X-Guild-Key")
    _expire_old()


# --- routes ----------------------------------------------------------------


@app.get("/api/health")
def health():
    return jsonify({"status": "ok", "count": len(_LISTINGS)})


@app.get("/api/listings")
def list_all():
    with _LOCK:
        # newest first
        listings = sorted(_LISTINGS.values(), key=lambda v: -v["createdAtMillis"])
    return jsonify(listings)


@app.post("/api/listings")
def create():
    body = request.get_json(force=True, silent=True) or {}
    leader = (body.get("leader") or "").strip()
    category = (body.get("category") or "").strip()
    note = (body.get("note") or "").strip()
    try:
        size = int(body.get("size", 4))
    except (TypeError, ValueError):
        size = 4
    size = max(2, min(10, size))

    if not leader or not category:
        abort(400, description="leader and category are required")

    party = {
        "id": uuid.uuid4().hex[:6],
        "leader": leader,
        "category": category,
        "size": size,
        "note": note,
        "full": False,                 # leaders flip this when their party fills up
        "createdAtMillis": _now_ms(),
    }

    with _LOCK:
        # one listing per leader — replace the old one if present
        for existing_id, existing in list(_LISTINGS.items()):
            if existing["leader"].lower() == leader.lower():
                del _LISTINGS[existing_id]
                break
        _LISTINGS[party["id"]] = party

    return jsonify(party), 201


@app.delete("/api/listings/<pid>")
def delete(pid: str):
    with _LOCK:
        _LISTINGS.pop(pid, None)
    return ("", 204)


@app.post("/api/listings/<pid>/full")
def set_full(pid: str):
    """Toggle whether a listing is marked full. Body: {"full": true|false}."""
    body = request.get_json(force=True, silent=True) or {}
    full = bool(body.get("full", False))
    with _LOCK:
        party = _LISTINGS.get(pid)
        if party is None:
            abort(404, description="listing not found")
        party["full"] = full
        return jsonify(party)


# --- error handlers --------------------------------------------------------


@app.errorhandler(400)
def _bad_request(e):
    return jsonify({"error": "bad_request", "detail": str(e.description)}), 400


@app.errorhandler(403)
def _forbidden(e):
    return jsonify({"error": "forbidden", "detail": str(e.description)}), 403


@app.errorhandler(404)
def _not_found(e):
    return jsonify({"error": "not_found", "detail": str(e.description)}), 404


# --- entrypoint ------------------------------------------------------------


if __name__ == "__main__":
    # Production deploys (Fly/Render) usually run gunicorn — see Dockerfile.
    # This entrypoint is for local dev: `python server.py`.
    app.run(host="0.0.0.0", port=PORT, debug=False)
