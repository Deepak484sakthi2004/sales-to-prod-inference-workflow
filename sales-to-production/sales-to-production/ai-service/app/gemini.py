"""Gemini through the Google Gen AI SDK.

Structured output comes from a forced function call (mode ANY with allowed_function_names): the model must reply by
calling one of the functions declared, never with free text. Parameters are plain JSON Schema. Automatic function
calling is off: this service never executes anything.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from google import genai
from google.genai import errors, types

from .config import Settings


@dataclass(frozen=True)
class FunctionSpec:
    name: str
    description: str
    parameters: dict[str, Any]  # JSON Schema


@dataclass(frozen=True)
class FunctionCall:
    name: str
    args: dict[str, Any]
    model: str


class ModelError(RuntimeError):
    """The model was reached but did not return something usable."""


class ModelUnavailable(RuntimeError):
    """The model cannot be used at all: no key, refused request, quota."""


class GeminiProvider:
    def __init__(self, settings: Settings, client: genai.Client | None = None):
        self.settings = settings
        if client is None:
            if not settings.api_key:
                raise ModelUnavailable("GEMINI_API_KEY is not set")
            client = genai.Client(api_key=settings.api_key, http_options=types.HttpOptions(
                base_url=settings.base_url, timeout=settings.timeout_ms))
        self.client = client

    def call_function(self, *, system: str, user: str, functions: list[FunctionSpec], allowed: list[str]) -> FunctionCall:
        model = self.settings.model
        config = types.GenerateContentConfig(
            system_instruction=system,
            tools=[types.Tool(function_declarations=[
                types.FunctionDeclaration(name=f.name, description=f.description, parameters_json_schema=f.parameters)
                for f in functions])],
            tool_config=types.ToolConfig(function_calling_config=types.FunctionCallingConfig(
                mode=types.FunctionCallingConfigMode.ANY, allowed_function_names=allowed)),
            automatic_function_calling=types.AutomaticFunctionCallingConfig(disable=True),
            thinking_config=types.ThinkingConfig(thinking_level=self.settings.thinking_level.upper())
            if self.settings.thinking_level else None,
        )
        try:
            response = self.client.models.generate_content(model=model, contents=user, config=config)
        except errors.ClientError as e:  # 4xx: bad key, unknown model, quota
            raise ModelUnavailable(f"Gemini refused the request: {e}") from e
        except errors.ServerError as e:  # 5xx
            raise ModelError(f"Gemini server error: {e}") from e
        calls = response.function_calls or []
        if not calls or calls[0].name not in allowed:
            raise ModelError(f"{model} did not call one of {allowed}")
        return FunctionCall(name=calls[0].name, args=dict(calls[0].args or {}), model=response.model_version or model)
