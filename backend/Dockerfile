FROM python:3.12-slim

WORKDIR /app

# Install deps first so Docker layer cache hits when only server.py changes.
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY server.py .

ENV PORT=8000
EXPOSE 8000

# Single gunicorn worker — multiple workers each get their own in-memory
# _LISTINGS dict, which would split-brain the state. With one worker all
# reads/writes share the same process. Plenty for a guild-sized load.
CMD ["sh", "-c", "gunicorn -w 1 -b 0.0.0.0:${PORT} server:app"]
