"""Typst renderer: turns an invoice payload into a PDF.

The Java render worker sends the saved invoice payload here, and this service
returns the finished PDF. It knows nothing about Kafka, the database or storage.

Endpoints:
    POST /render/<bu>   Body is the invoice payload as JSON. Returns the PDF.
    GET  /health        Returns 200 when the service is up.

Error responses:
    400   The body is not valid JSON.
    404   There is no template for this BU.
    422   Typst failed to compile the invoice, or raised a warning.
"""

import json
import os
from pathlib import Path

import typst

# Fonts and BU templates are copied here when the Docker image is built.
ASSETS = Path(os.environ.get("ASSETS_DIR", "/app/assets"))

# Use only our bundled fonts, so an invoice looks the same on every machine.
FONTS = typst.Fonts(
    include_system_fonts=False,
    include_embedded_fonts=False,
    font_paths=[str(ASSETS / "fonts")],
)


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
            root=str(ASSETS),
            font_paths=FONTS,
            ignore_system_fonts=True,
        )
        for template in (ASSETS / "adaption").glob("*/invoice.typ")
    }


COMPILERS = load_compilers()


def render(bu, payload):
    """Compiles one invoice and returns the PDF bytes.

    The payload is passed in as plain data, so a customer name or any other
    value can never run as template code.

    Any warning fails the render, because Typst only warns about a missing
    font and then quietly uses a different one.
    """
    try:
        pdf, warnings = COMPILERS[bu].compile_with_warnings(sys_inputs={"payload": payload})
    except typst.TypstError as error:
        raise RenderError(error.diagnostic) from error
    if warnings:
        raise RenderError("\n".join(warning.diagnostic for warning in warnings))
    return pdf


def app(environ, start_response):
    """Handles each HTTP request. Gunicorn calls this function directly."""
    method = environ["REQUEST_METHOD"]
    path = environ["PATH_INFO"]

    if method == "GET" and path == "/health":
        return respond(start_response, "200 OK", b"ok", "text/plain")

    if method != "POST" or not path.startswith("/render/"):
        return respond(start_response, "404 Not Found", b"Not found", "text/plain")

    # The BU is only looked up in COMPILERS, so a request can't choose a file path.
    bu = path.removeprefix("/render/")
    if bu not in COMPILERS:
        return respond(start_response, "404 Not Found", f"Unknown template: {bu}".encode(), "text/plain")

    body = environ["wsgi.input"].read(int(environ.get("CONTENT_LENGTH") or 0))
    try:
        payload = body.decode("utf-8")
        json.loads(payload)
    except ValueError:
        return respond(start_response, "400 Bad Request", b"Invalid JSON", "text/plain")

    try:
        return respond(start_response, "200 OK", render(bu, payload), "application/pdf")
    except RenderError as error:
        return respond(start_response, "422 Unprocessable Entity", str(error).encode(), "text/plain")


def respond(start_response, status, body, content_type):
    """Sends an HTTP response with the given status and body."""
    start_response(status, [("Content-Type", content_type), ("Content-Length", str(len(body)))])
    return [body]
