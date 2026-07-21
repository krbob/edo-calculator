#!/usr/bin/env python3
"""Dependency-free documentation consistency checks for local development and CI."""

from __future__ import annotations

import re
import sys
from pathlib import Path
from urllib.parse import unquote


ROOT = Path(__file__).resolve().parents[1]
README = ROOT / "README.md"
REQUIRED_DOCUMENTS = (
    ROOT / "README.md",
    ROOT / "docker-compose.yml",
    ROOT / "docs" / "calculation-model.md",
    ROOT / "docs" / "operations.md",
    ROOT / "docs" / "development.md",
    ROOT / "openapi" / "edo-calculator-v1.yaml",
)
MARKDOWN_LINK = re.compile(r"!?(?:\[[^\]]*])\(([^)]+)\)")
ENDPOINT_REFERENCE_HEADING = re.compile(
    r"^###\s+(?:GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\b",
    re.MULTILINE,
)
MOVING_EDO_IMAGE = re.compile(r"ghcr\.io/krbob/edo-calculator:latest\b")
PRIVATE_DEPLOYMENT_DOMAIN = re.compile(r"\bbobinski\.net\b", re.IGNORECASE)
UNPINNED_GENERATOR = re.compile(
    r"(?:docker\s+run[^\n]*\s+)?openapitools/openapi-generator-cli(?!@sha256:)",
)


def fenced_code(markdown: str) -> str:
    blocks: list[str] = []
    current: list[str] = []
    fence: str | None = None

    for line in markdown.splitlines():
        marker = line.lstrip()
        if fence is None and marker.startswith("```"):
            fence = "```"
            current = []
        elif fence is not None and marker.startswith(fence):
            blocks.append("\n".join(current))
            fence = None
        elif fence is not None:
            current.append(line)

    return "\n".join(blocks)


def validate() -> list[str]:
    errors: list[str] = []

    for required in REQUIRED_DOCUMENTS:
        if not required.is_file():
            errors.append(f"Missing required documentation file: {required.relative_to(ROOT)}")

    if errors:
        return errors

    markdown_files = [README, *sorted((ROOT / "docs").glob("*.md"))]
    readme_text = README.read_text(encoding="utf-8")
    readme_lines = readme_text.splitlines()
    compose_text = (ROOT / "docker-compose.yml").read_text(encoding="utf-8")

    if len(readme_lines) > 160:
        errors.append(f"README must remain a landing page (160 lines maximum, got {len(readme_lines)}).")
    if ENDPOINT_REFERENCE_HEADING.search(readme_text):
        errors.append("README duplicates endpoint reference headings; keep them in OpenAPI.")

    required_readme_links = (
        "docker-compose.yml",
        "openapi/edo-calculator-v1.yaml",
        "docs/calculation-model.md",
        "docs/operations.md",
        "docs/development.md",
    )
    for target in required_readme_links:
        if f"]({target})" not in readme_text:
            errors.append(f"README must link to {target}.")

    required_quick_start_fragments = (
        "./gradlew jibDockerBuild --image=edo-calculator:local",
        "docker compose up --detach",
        "docker compose down",
        "http://127.0.0.1:8080/healthz",
        "http://127.0.0.1:8080/readyz",
    )
    for fragment in required_quick_start_fragments:
        if fragment not in readme_text:
            errors.append(f"README quick start is missing {fragment!r}.")

    if MOVING_EDO_IMAGE.search(compose_text):
        errors.append("docker-compose.yml must not default to the moving EDO image tag latest.")
    if "127.0.0.1:${EDO_CALCULATOR_PORT:-8080}:8080" not in compose_text:
        errors.append("docker-compose.yml must bind the quick start to loopback.")

    checked_links = 0
    for markdown_file in markdown_files:
        text = markdown_file.read_text(encoding="utf-8")
        code = fenced_code(text)
        relative_name = markdown_file.relative_to(ROOT)

        if PRIVATE_DEPLOYMENT_DOMAIN.search(text):
            errors.append(f"{relative_name} references a private deployment domain.")

        if MOVING_EDO_IMAGE.search(code):
            errors.append(f"{relative_name} recommends the moving EDO image tag latest in a code block.")
        if UNPINNED_GENERATOR.search(code):
            errors.append(f"{relative_name} invokes an unpinned OpenAPI Generator image.")

        for raw_target in MARKDOWN_LINK.findall(text):
            target = raw_target.strip().strip("<>")
            if target.startswith(("http://", "https://", "mailto:", "#")):
                continue

            local_part = unquote(target.split("#", 1)[0])
            if not local_part:
                continue
            checked_links += 1
            resolved = (markdown_file.parent / local_part).resolve()
            if not resolved.exists():
                errors.append(f"Broken local link in {relative_name}: {target}")

    if PRIVATE_DEPLOYMENT_DOMAIN.search(compose_text):
        errors.append("docker-compose.yml references a private deployment domain.")

    if not errors:
        print(
            f"Documentation validation passed: {len(markdown_files)} Markdown files, "
            f"{checked_links} local links."
        )
    return errors


def main() -> int:
    errors = validate()
    if not errors:
        return 0

    for error in errors:
        print(f"ERROR: {error}", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
