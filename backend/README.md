# dvspf backend

Tiny Flask service that brokers party-finder listings between guildmate
Minecraft clients. About 100 lines of Python.

A "listing" is just an ad: who's leading, what they're doing, party size, note.
Joining is handled client-side via `/msg` — this service has no concept of
party membership.

## Run locally

```bash
cd backend
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
python server.py
# listening on http://localhost:8000
```

Then in your Minecraft config (`~/.minecraft/config/dvspf.json` for vanilla
launcher, or your prism/multimc instance config dir):

```json
{
  "apiUrl": "http://localhost:8000",
  "apiKey": "",
  "pollIntervalSeconds": 15
}
```

Restart the game and `/dpf` lists from the live server.

## Endpoints

| Method | Path                     | Body / Notes                                |
|--------|--------------------------|---------------------------------------------|
| GET    | `/api/health`            | unauthenticated; `{status, count}`          |
| GET    | `/api/listings`          | newest first                                |
| POST   | `/api/listings`          | `{leader, category, size, note}` -> listing |
| DELETE | `/api/listings/<id>`     | 204                                         |

All `/api/*` (except `/api/health`) require `X-Guild-Key: <key>` if the
`DVSPF_API_KEY` env var is set on the server.

## Behavior

- Listings older than `DVSPF_TTL_SECONDS` (default 1800 = 30 min) are reaped
  on every request — abandoned ads don't pile up.
- One listing per leader: posting again replaces the old one. Matches the
  client's "one ad per player" expectation.
- Storage is in-memory. Restart wipes everything. For an MVP that's fine —
  these are ephemeral "looking for group right now" ads anyway. If you want
  persistence later, swap the `_LISTINGS` dict for a SQLite write-through.

## Deploy

### Fly.io (free tier)

```bash
cd backend
fly launch              # accept defaults; pick a region
fly secrets set DVSPF_API_KEY=$(openssl rand -hex 16)
fly deploy
```

The URL it gives you (`https://<app>.fly.dev`) goes into your client config's
`apiUrl`. Share the API key with your guild.

### Render.com (free tier)

1. Push this `backend/` folder to a GitHub repo.
2. Create a new Web Service on Render, point it at the repo.
3. Render auto-detects the Dockerfile.
4. Add env var `DVSPF_API_KEY` (any random string).
5. Use the resulting URL in `apiUrl`.

Render's free tier sleeps after ~15 min idle — first request after sleep
takes ~30s to wake. Fine for a party finder.

### Plain VM

```bash
docker build -t dvspf .
docker run -d --name dvspf --restart unless-stopped \
  -p 8000:8000 \
  -e DVSPF_API_KEY=$(openssl rand -hex 16) \
  dvspf
```

Then put it behind nginx with TLS (Caddy is even easier for this).

## Sanity-check it

```bash
# anonymous
curl localhost:8000/api/health

# authed (if you set DVSPF_API_KEY)
KEY=...
curl -H "X-Guild-Key: $KEY" localhost:8000/api/listings

# create
curl -H "X-Guild-Key: $KEY" -H "Content-Type: application/json" \
     -d '{"leader":"Tai","category":"NotG","size":4,"note":"speedrun"}' \
     localhost:8000/api/listings
```
