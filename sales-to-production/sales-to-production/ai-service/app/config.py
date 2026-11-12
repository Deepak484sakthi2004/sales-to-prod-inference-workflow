"""Settings, all from environment variables."""
from __future__ import annotations

import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    api_key: str | None = None
    model: str = "gemini-3.8-flash"
    thinking_level: str | None = "LOW"  # MINIMAL | LOW | MEDIUM | HIGH, or empty for models without levels
    timeout_ms: int = 30000
    max_catalogue: int = 60  # catalogue items sent with a mail; larger catalogues are shortlisted
    base_url: str | None = None  # only for tests or a proxy

    @staticmethod
    def from_env() -> "Settings":
        env = os.environ.get
        return Settings(
            api_key=env("GEMINI_API_KEY") or env("GOOGLE_API_KEY") or None,
            model=env("GEMINI_MODEL") or "gemini-3.8-flash",
            thinking_level=env("GEMINI_THINKING_LEVEL", "LOW") or None,
            timeout_ms=int(env("GEMINI_TIMEOUT_MS") or 30000),
            max_catalogue=int(env("AI_MAX_CATALOGUE") or 60),
            base_url=env("GEMINI_BASE_URL") or None,
        )
