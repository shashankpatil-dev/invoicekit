"""Typst renderer: turns an invoice payload into a PDF.

The Java render worker sends the saved invoice payload here, and this service
returns the finished PDF. It knows nothing about Kafka, the database or storage.

Endpoints:
    POST /render/<business_unit>   Body is the invoice payload as JSON. Returns the PDF.
    GET  /health                   Returns 200 when the service is up.

Error responses:
    400   The body is not valid JSON.
    404   There is no template for this business unit.
    422   Typst failed to compile the invoice, or raised a warning.
    503   Too many renders are waiting. Retry later.

Each worker renders one invoice at a time, and a small number of others may wait.
Anything beyond that is refused immediately, which always leaves a thread free
to answer /health, even when the worker is overloaded.
"""

import json
import os
import threading
import time

import typst

from settings import (
    ASSETS_DIR,
    MAX_WAITING_RENDERS,
    RENDER_SLOTS,
    RENDER_TIMEOUT_SECONDS,
    WATCHDOG_INTERVAL_SECONDS,
)

# Use only our bundled fonts, so an invoice looks the same on every machine.
FONTS = typst.Fonts(
    include_system_fonts=False,
    include_embedded_fonts=False,
    font_paths=[str(ASSETS_DIR / "fonts")],
)

# Counts renders that are running or waiting. Requests beyond this limit get a 503.
ADMITTED_RENDERS = threading.BoundedSemaphore(RENDER_SLOTS + MAX_WAITING_RENDERS)

# Lets one admitted render run. The others sleep here without using CPU.
RENDER_SLOT = threading.Semaphore(RENDER_SLOTS)

# When the current render began. Only the thread holding RENDER_SLOT changes it.
render_started_at = None


class RenderError(Exception):
    """Typst could not produce a valid invoice, and retrying won't help."""


def load_compilers():
    """Builds one compiler per BU template, using the BU folder name as the key.

    Compilers are kept and reused because that keeps fonts and parsed templates
    in memory, which is what makes each render fast. Setting root stops a
    template from reading files outside the assets folder.
    """
    return {
        template.parent.name: typst.Compiler(
            str(template),
            root=str(ASSETS_DIR),
            font_paths=FONTS,
            ignore_system_fonts=True,
        )
        for template in (ASSETS_DIR / "adaption").glob("*/invoice.typ")
    }


COMPILERS = load_compilers()


def render(business_unit, payload):
    """Compiles one invoice and returns the PDF bytes.

    The payload is passed in as plain data, so a customer name or any other
    value can never run as template code.

    Any warning fails the render, because Typst only warns about a missing
    font and then quietly uses a different one.
    """
    try:
        pdf, warnings = COMPILERS[business_unit].compile_with_warnings(sys_inputs={"payload": payload})
    except typst.TypstError as error:
        raise RenderError(error.diagnostic) from error
    if warnings:
        raise RenderError("\n".join(warning.diagnostic for warning in warnings))
    return pdf


def render_in_turn(business_unit, payload):
    """Waits for the render slot, then renders while the watchdog keeps time."""
    global render_started_at
    with RENDER_SLOT:
        render_started_at = time.monotonic()
        try:
            return render(business_unit, payload)
        finally:
            render_started_at = None


def watch_renders():
    """Replaces this worker if a render gets stuck.

    A Typst compile can't be cancelled from Python, and a stuck one would hold
    the render slot forever. Exiting makes gunicorn start a clean worker.
    """
    while True:
        time.sleep(WATCHDOG_INTERVAL_SECONDS)
        started_at = render_started_at
        if started_at is not None and time.monotonic() - started_at > RENDER_TIMEOUT_SECONDS:
            os._exit(1)


def start_watchdog():
    """Starts the watchdog. Gunicorn calls this once inside each worker."""
    threading.Thread(target=watch_renders, daemon=True).start()


def app(environ, start_response):
    """Routes each request. /health never touches the render queue, so it can't wait behind it."""
    method = environ["REQUEST_METHOD"]
    path = environ["PATH_INFO"]

    if method == "GET" and path == "/health":
        return respond(start_response, "200 OK", b"ok", "text/plain")

    if method != "POST" or not path.startswith("/render/"):
        return respond(start_response, "404 Not Found", b"Not found", "text/plain")

    # The business unit is only looked up in COMPILERS, so a request can't choose a file path.
    business_unit = path.removeprefix("/render/")
    if business_unit not in COMPILERS:
        return respond(start_response, "404 Not Found", f"Unknown template: {business_unit}".encode(), "text/plain")

    body = environ["wsgi.input"].read(int(environ.get("CONTENT_LENGTH") or 0))
    try:
        payload = body.decode("utf-8")
        json.loads(payload)
    except ValueError:
        return respond(start_response, "400 Bad Request", b"Invalid JSON", "text/plain")

    if not ADMITTED_RENDERS.acquire(blocking=False):
        return respond(start_response, "503 Service Unavailable", b"Too many renders waiting", "text/plain")

    try:
        return respond(start_response, "200 OK", render_in_turn(business_unit, payload), "application/pdf")
    except RenderError as error:
        return respond(start_response, "422 Unprocessable Entity", str(error).encode(), "text/plain")
    finally:
        ADMITTED_RENDERS.release()


def respond(start_response, status, body, content_type):
    """Sends an HTTP response with the given status and body."""
    start_response(status, [("Content-Type", content_type), ("Content-Length", str(len(body)))])
    return [body]
