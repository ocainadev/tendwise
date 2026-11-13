"""Fixed replies the agent sends without calling the LLM, in each supported language.

These are not prompts (nothing here is sent to a model): they are the exact
words the customer reads when the next step is obvious, such as asking for a
missing order code. A fixed text is free, instant and always correct, which is
better than paying an LLM to say the same sentence.
"""

from app.agent.intent import Language

FIXED_REPLIES: dict[str, dict[str, str]] = {
    "ask_order_code_and_phone": {
        "en": "I can check that for you. Please send me your order code (for example JP-1042) "
        "and the last 4 digits of the phone number on the order.",
        "es": "Puedo revisarlo. Envíame el código de tu pedido (por ejemplo JP-1042) "
        "y los últimos 4 dígitos del teléfono del pedido.",
        "pt": "Posso verificar para você. Envie o código do pedido (por exemplo JP-1042) "
        "e os 4 últimos dígitos do telefone do pedido.",
    },
    "ask_order_code": {
        "en": "Please send me your order code (for example JP-1042).",
        "es": "Envíame el código de tu pedido (por ejemplo JP-1042).",
        "pt": "Envie o código do seu pedido (por exemplo JP-1042).",
    },
    "ask_phone_last4": {
        "en": "Thanks! To confirm it is your order, please send the last 4 digits "
        "of the phone number on the order.",
        "es": "¡Gracias! Para confirmar que es tu pedido, envíame los últimos 4 dígitos "
        "del teléfono del pedido.",
        "pt": "Obrigado! Para confirmar que o pedido é seu, envie os 4 últimos dígitos "
        "do telefone do pedido.",
    },
    "order_not_found": {
        "en": "Sorry, I could not find an order with that code and phone number. "
        "Please check both and send them again.",
        "es": "Lo siento, no encontré un pedido con ese código y ese teléfono. "
        "Revisa los dos datos y envíalos de nuevo.",
        "pt": "Desculpe, não encontrei um pedido com esse código e esse telefone. "
        "Confira os dois dados e envie de novo.",
    },
    "no_context": {
        "en": "Sorry, I don't have that information. Would you like me to pass you "
        "to a person from our team?",
        "es": "Lo siento, no tengo esa información. ¿Quieres que te pase con una persona "
        "de nuestro equipo?",
        "pt": "Desculpe, não tenho essa informação. Quer que eu passe você para uma pessoa "
        "da nossa equipe?",
    },
    "handoff": {
        "en": "I have passed your conversation to our team. A person will continue "
        "with you here shortly.",
        "es": "He pasado tu conversación a nuestro equipo. Una persona seguirá contigo "
        "aquí en breve.",
        "pt": "Passei sua conversa para a nossa equipe. Uma pessoa vai continuar o "
        "atendimento aqui em breve.",
    },
}


def fixed_reply(name: str, language: Language) -> str:
    """The fixed reply `name` in `language` (a typo in `name` raises KeyError at once)."""
    return FIXED_REPLIES[name][language]
