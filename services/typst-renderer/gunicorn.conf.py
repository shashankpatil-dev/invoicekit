"""Gunicorn settings for the Typst renderer. Values come from config/renderer.properties."""

from settings import (
    BIND_ADDRESS,
    KEEPALIVE_SECONDS,
    MAX_REQUESTS,
    MAX_REQUESTS_JITTER,
    THREADS,
    WORKER_TIMEOUT_SECONDS,
)

bind = BIND_ADDRESS
timeout = WORKER_TIMEOUT_SECONDS
keepalive = KEEPALIVE_SECONDS
max_requests = MAX_REQUESTS
max_requests_jitter = MAX_REQUESTS_JITTER

# Threads let /health run while renders wait.
worker_class = "gthread"
threads = THREADS

# Load templates once before forking, so workers start quickly and share that memory.
preload_app = True

# This service has no use for gunicorn's admin socket, and its user has no home folder for it.
control_socket_disable = True


def post_worker_init(worker):
    """Starts the watchdog here because threads don't survive the fork into a worker."""
    import app

    app.start_watchdog()
