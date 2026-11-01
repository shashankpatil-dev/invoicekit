# Image for the Typst renderer service.
# Build it from the repo root, because it also needs the BU templates in src/main/resources.
FROM python:3.13.7-slim-trixie

WORKDIR /app

# Install Python dependencies first, so Docker can reuse this layer when only templates change.
COPY services/typst-renderer/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Bake fonts and BU templates into the image, so the service needs no network or shared files.
COPY services/typst-renderer/fonts assets/fonts
COPY src/main/resources/adaption assets/adaption
COPY services/typst-renderer/app.py .

# Run as a non-root user, because this service doesn't need any special access.
USER nobody
EXPOSE 3000

# Gunicorn runs the workers. Set WEB_CONCURRENCY to the CPU limit, because rendering is CPU-heavy.
# Each render gets 30 seconds, and workers restart after about 5,000 requests to keep memory steady.
CMD ["gunicorn", "--bind=0.0.0.0:3000", "--preload", "--timeout=30", "--max-requests=5000", "--max-requests-jitter=500", "app:app"]
