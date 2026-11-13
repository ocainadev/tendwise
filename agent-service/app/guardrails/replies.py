"""Fixed replies for blocked messages, in each supported language.

Like `app/agent/replies.py`, these are not prompts: they are the exact words
the customer reads. A refusal must never depend on an LLM, because the message
being refused may be an attack on that LLM.
"""

from typing import Literal

from app.agent.intent import Language

BlockKind = Literal["empty", "too_long", "injection", "abuse"]

REFUSALS: dict[BlockKind, dict[Language, str]] = {
    "empty": {
        "en": "It looks like your message was empty. How can I help you?",
        "es": "Parece que tu mensaje llegó vacío. ¿En qué te puedo ayudar?",
        "pt": "Parece que sua mensagem chegou vazia. Como posso ajudar?",
    },
    "too_long": {
        "en": "Your message is too long for me to read. Please send a shorter message "
        "(up to 1,000 characters).",
        "es": "Tu mensaje es demasiado largo. Envíame un mensaje más corto "
        "(hasta 1.000 caracteres), por favor.",
        "pt": "Sua mensagem é longa demais. Envie uma mensagem mais curta "
        "(até 1.000 caracteres), por favor.",
    },
    "injection": {
        "en": "Sorry, I can only help with questions about {business_name}, such as our "
        "products, opening hours or your order. How can I help?",
        "es": "Lo siento, solo puedo ayudarte con preguntas sobre {business_name}, como "
        "nuestros productos, horarios o tu pedido. ¿En qué te puedo ayudar?",
        "pt": "Desculpe, só posso ajudar com perguntas sobre {business_name}, como nossos "
        "produtos, horários ou o seu pedido. Como posso ajudar?",
    },
    "abuse": {
        "en": "I'm here to help, and I'd like to keep our conversation respectful. "
        "Tell me what you need and I'll do my best.",
        "es": "Estoy aquí para ayudarte y me gustaría mantener una conversación respetuosa. "
        "Cuéntame qué necesitas y haré lo posible.",
        "pt": "Estou aqui para ajudar e gostaria de manter uma conversa respeitosa. "
        "Me diga do que você precisa e farei o possível.",
    },
}


def refusal(kind: BlockKind, language: Language, business_name: str) -> str:
    """The fixed reply for a message blocked as `kind`, in `language`."""
    return REFUSALS[kind][language].format(business_name=business_name)
