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
COPY services/typst-renderer/config config
COPY services/typst-renderer/app.py services/typst-renderer/settings.py services/typst-renderer/gunicorn.conf.py ./

# Run as a non-root user, because this service doesn't need any special access.
USER nobody
EXPOSE 3000

# Gunicorn runs the workers, using the settings in gunicorn.conf.py.
CMD ["gunicorn", "app:app"]
