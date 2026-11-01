"""Loads the renderer's settings from config/renderer.properties."""

import os
from pathlib import Path

PROPERTIES_FILE = Path(__file__).parent / "config" / "renderer.properties"


def read_properties(path):
    """Reads key=value lines, skipping blanks and comments."""
    lines = (line.strip() for line in path.read_text(encoding="utf-8").splitlines())
    pairs = (line.split("=", 1) for line in lines if line and not line.startswith("#"))
    return {key.strip(): value.strip() for key, value in pairs}


def environment_name(key):
    """Turns render.max-waiting into RENDER_MAX_WAITING."""
    return key.replace(".", "_").replace("-", "_").upper()


def load_settings():
    """Returns the settings, letting environment variables override the file."""
    properties = read_properties(PROPERTIES_FILE)
    return {key: os.environ.get(environment_name(key), value) for key, value in properties.items()}


SETTINGS = load_settings()

ASSETS_DIR = Path(SETTINGS["assets.dir"])
RENDER_SLOTS = int(SETTINGS["render.slots"])
MAX_WAITING_RENDERS = int(SETTINGS["render.max-waiting"])
RENDER_TIMEOUT_SECONDS = float(SETTINGS["render.timeout-seconds"])
WATCHDOG_INTERVAL_SECONDS = float(SETTINGS["render.watchdog-interval-seconds"])

BIND_ADDRESS = SETTINGS["server.bind"]
THREADS = int(SETTINGS["server.threads"])
WORKER_TIMEOUT_SECONDS = int(SETTINGS["server.worker-timeout-seconds"])
KEEPALIVE_SECONDS = int(SETTINGS["server.keepalive-seconds"])
MAX_REQUESTS = int(SETTINGS["server.max-requests"])
MAX_REQUESTS_JITTER = int(SETTINGS["server.max-requests-jitter"])

if THREADS <= RENDER_SLOTS + MAX_WAITING_RENDERS:
    raise ValueError("server.threads must exceed render.slots + render.max-waiting, or /health can run out of threads")
