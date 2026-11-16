"""The owner's settings (T54) as a section of the answer prompts.

The assistant name, tone and extra instructions written in the control panel
go into a `<business_instructions>` section added *after* the prompt's rules.
The section's own text (`app/prompts/business_instructions.md`) says it can
never override those rules, and the eval suite has cases that try.
"""

import re

from app.gateways.business_gateway import AssistantSettings
from app.prompts import load_prompt

# An owner's text must not close the section early: these tags are removed from it.
SECTION_TAG = re.compile(r"</?\s*business_instructions\s*>", re.IGNORECASE)


def business_instructions(settings: AssistantSettings) -> str:
    """The owner's name, tone and extra instructions as the delimited section, or "" if unset.

    The texts were already cut to their maximum length by `AssistantSettings`.
    """
    lines = []
    if settings.name:
        lines.append(f"Assistant name: {settings.name}")
    if settings.tone:
        lines.append(f"Tone: {settings.tone}")
    if settings.extra_instructions:
        lines.append(f"Extra instructions:\n{settings.extra_instructions}")
    if not lines:
        return ""
    text = SECTION_TAG.sub("", "\n".join(lines))
    return load_prompt("business_instructions").replace("{settings}", text)


def answer_system_prompt(prompt_name: str, settings: AssistantSettings) -> str:
    """The answer prompt `prompt_name`, followed by the business instructions when set.

    The section comes after the rules on purpose: the model reads the rules
    first, and the section itself says it cannot override them.
    """
    section = business_instructions(settings)
    prompt = load_prompt(prompt_name)
    return f"{prompt}\n\n{section}" if section else prompt
