"""The JSON contract with the Java core (core/src/main/java/com/s2p/core/ai/AiDtos.java). Field names are snake_case."""
from __future__ import annotations

from typing import Any, Generic, Optional, TypeVar

from pydantic import BaseModel, ConfigDict, Field as PField, field_validator

T = TypeVar("T")


class Model(BaseModel):
    model_config = ConfigDict(extra="ignore")


# ------------------------------------------------------------------ reading mail

class Attachment(Model):
    name: str
    content_type: Optional[str] = None
    text: Optional[str] = None


class CatalogItem(Model):
    sku: str
    name: str
    family: Optional[str] = None
    size_mm: Optional[int] = None
    variant: Optional[str] = None


class ExtractRequest(Model):
    message_id: Optional[str] = None
    received_at: str
    today: str
    from_address: Optional[str] = None
    subject: Optional[str] = None
    body: Optional[str] = None
    attachments: list[Attachment] = PField(default_factory=list)
    catalogue: list[CatalogItem] = PField(default_factory=list)


class Field(Model, Generic[T]):
    """A value and the verbatim text it was read from. The Java core rejects any value whose text is not in the mail."""
    value: Optional[T] = None
    evidence: Optional[str] = None


class DateField(Model):
    phrase: Optional[str] = None
    iso: Optional[str] = None
    evidence: Optional[str] = None


class Line(Model):
    """sku only when the customer's words identify one catalogue product; otherwise candidate_skus."""
    product_text: Optional[str] = None
    sku: Optional[str] = None
    candidate_skus: list[str] = PField(default_factory=list)
    product_evidence: Optional[str] = None
    qty: Optional[Field[int]] = None
    size_mm: Optional[Field[int]] = None
    date: Optional[DateField] = None


class ExtractResponse(Model):
    intent: Optional[Field[str]] = None
    urgent: Optional[Field[bool]] = None
    quotation_ref: Optional[Field[str]] = None
    customer_po: Optional[Field[str]] = None
    order_ref: Optional[Field[str]] = None
    lines: list[Line] = PField(default_factory=list)
    notes: list[str] = PField(default_factory=list)
    model: Optional[str] = None


# ------------------------------------------------------------------ reading questions

class ToolSpec(Model):
    name: str
    description: str
    parameters: dict[str, Any]


class EntityRef(Model):
    id: str
    name: str


class AskPlanRequest(Model):
    question: str
    role: str
    today: str
    tools: list[ToolSpec]
    accounts: list[EntityRef] = PField(default_factory=list)
    products: list[EntityRef] = PField(default_factory=list)
    lines: list[str] = PField(default_factory=list)
    context: dict[str, str] = PField(default_factory=dict)

    @field_validator("tools")
    @classmethod
    def at_least_one_tool(cls, v: list[ToolSpec]) -> list[ToolSpec]:
        if not v:
            raise ValueError("at least one tool is required")
        return v


class AskPlan(Model):
    tool: Optional[str] = None
    args: dict[str, Any] = PField(default_factory=dict)
    reading: Optional[str] = None
    model: Optional[str] = None



# ------------------------------------------------------------------ reading CRM notes

class Note(Model):
    id: str
    text: str


class QuotationNotes(Model):
    quotation_id: str
    notes: list[Note]


class ActivitiesRequest(Model):
    quotations: list[QuotationNotes]


class ActivityReading(Model):
    quotation_id: str
    competitor_mentioned: Optional[Field[bool]] = None
    activity_id: Optional[str] = None


class ActivitiesResponse(Model):
    readings: list[ActivityReading] = PField(default_factory=list)
    model: Optional[str] = None
