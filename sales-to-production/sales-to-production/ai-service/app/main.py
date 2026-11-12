"""AI service for the sales-to-production core: Gemini reads customer mail, plain-language questions and CRM notes.

It has no access to any business system and executes nothing. It returns readings; the Java core checks them against
the mail, the catalogue and current data before anything is used, and falls back to its own rules when this service
is down. Run: uvicorn app.main:app --port 8090
"""
from __future__ import annotations

import logging
from functools import lru_cache

from fastapi import Depends, FastAPI, HTTPException

from .activities import read as read_notes
from .ask import plan
from .config import Settings
from .extract import extract
from .gemini import GeminiProvider, ModelError, ModelUnavailable
from .schemas import (ActivitiesRequest, ActivitiesResponse, AskPlan, AskPlanRequest, ExtractRequest,
                      ExtractResponse)

log = logging.getLogger("ai-service")
app = FastAPI(title="s2p AI service", version="1.0.0")


@lru_cache
def get_settings() -> Settings:
    return Settings.from_env()


@lru_cache
def _gemini(settings: Settings) -> GeminiProvider:
    return GeminiProvider(settings)


def get_model(settings: Settings = Depends(get_settings)) -> GeminiProvider:
    try:
        return _gemini(settings)
    except ModelUnavailable as e:
        raise HTTPException(503, str(e)) from e


def _run(fn, *args):
    """503: the model cannot be used; 502: it failed this time. Either way the core reads with its rules."""
    try:
        return fn(*args)
    except ModelUnavailable as e:
        log.warning("model unavailable: %s", e)
        raise HTTPException(503, str(e)) from e
    except Exception as e:
        log.warning("model call failed: %s", e)
        raise HTTPException(502, f"{type(e).__name__}: {e}") from e


@app.get("/health")
def health(settings: Settings = Depends(get_settings)) -> dict:
    return {"status": "ok", "api_key_set": bool(settings.api_key), "model": settings.model,
            "thinking_level": settings.thinking_level}


@app.post("/v1/extract", response_model=ExtractResponse, response_model_exclude_none=True)
def read_mail(req: ExtractRequest, model: GeminiProvider = Depends(get_model),
              settings: Settings = Depends(get_settings)) -> ExtractResponse:
    return _run(extract, req, model, settings)


@app.post("/v1/ask/plan", response_model=AskPlan, response_model_exclude_none=True)
def read_question(req: AskPlanRequest, model: GeminiProvider = Depends(get_model)) -> AskPlan:
    return _run(plan, req, model)


@app.post("/v1/activities", response_model=ActivitiesResponse, response_model_exclude_none=True)
def read_crm_notes(req: ActivitiesRequest, model: GeminiProvider = Depends(get_model)) -> ActivitiesResponse:
    return _run(read_notes, req, model)
