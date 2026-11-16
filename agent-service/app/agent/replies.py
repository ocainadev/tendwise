"""Fixed replies the agent sends without calling the LLM, in each supported language.

These are not prompts (nothing here is sent to a model): they are the exact
words the customer reads when the next step is obvious, such as asking for a
missing order code. A fixed text is free, instant and always correct, which is
better than paying an LLM to say the same sentence.
"""

from datetime import datetime

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
    # Handoff outside opening hours (T54). `{reopening}` is filled by reopening_text().
    "handoff_closed": {
        "en": "I have passed your conversation to our team. We are closed right now "
        "and will be back {reopening}.",
        "es": "He pasado tu conversación a nuestro equipo. Ahora estamos cerrados "
        "y volvemos {reopening}.",
        "pt": "Passei sua conversa para a nossa equipe. No momento estamos fechados "
        "e voltamos {reopening}.",
    },
    "handoff_closed_no_time": {
        "en": "I have passed your conversation to our team. We are closed right now, "
        "and a person will reply here as soon as the team is back.",
        "es": "He pasado tu conversación a nuestro equipo. Ahora estamos cerrados "
        "y una persona te responderá aquí en cuanto el equipo vuelva.",
        "pt": "Passei sua conversa para a nossa equipe. No momento estamos fechados "
        "e uma pessoa vai responder aqui assim que a equipe voltar.",
    },
    # Sent after handoff_closed when the business has no out-of-hours message.
    "leave_contact": {
        "en": "If you like, leave a phone number or e-mail here and we will get back to you.",
        "es": "Si quieres, deja aquí un teléfono o un correo y te contactaremos.",
        "pt": "Se quiser, deixe aqui um telefone ou e-mail e entraremos em contato.",
    },
}

# Day names for reopening_text(), Monday first (like datetime.weekday()).
# Portuguese and Spanish include the preposition: "na terça-feira", "el martes".
WEEKDAYS: dict[str, list[str]] = {
    "en": [
        "on Monday",
        "on Tuesday",
        "on Wednesday",
        "on Thursday",
        "on Friday",
        "on Saturday",
        "on Sunday",
    ],
    "es": [
        "el lunes",
        "el martes",
        "el miércoles",
        "el jueves",
        "el viernes",
        "el sábado",
        "el domingo",
    ],
    "pt": [
        "na segunda-feira",
        "na terça-feira",
        "na quarta-feira",
        "na quinta-feira",
        "na sexta-feira",
        "no sábado",
        "no domingo",
    ],
}
TODAY = {"en": "today", "es": "hoy", "pt": "hoje"}
TOMORROW = {"en": "tomorrow", "es": "mañana", "pt": "amanhã"}


def fixed_reply(name: str, language: Language, **values: str) -> str:
    """The fixed reply `name` in `language` (a typo in `name` raises KeyError at once).

    `values` fill the reply's placeholders, e.g. `reopening="tomorrow at 11:00 am"`.
    """
    text = FIXED_REPLIES[name][language]
    return text.format(**values) if values else text


def clock_time(moment: datetime, language: Language) -> str:
    """The time of day as people write it: "11:00 am" in English, "11:00" in pt and es."""
    if language == "en":
        hour = moment.hour % 12 or 12
        return f"{hour}:{moment:%M} {'am' if moment.hour < 12 else 'pm'}"
    return f"{moment:%H:%M}"


def reopening_text(reopens_at: datetime, now: datetime, language: Language) -> str:
    """When the team is back, e.g. "amanhã às 09:00" or "on Tuesday at 11:00 am".

    Both datetimes must be in the business's time zone, so "today" and
    "tomorrow" are the business's days, not the server's.
    """
    days = (reopens_at.date() - now.date()).days
    if days == 0:
        day = TODAY[language]
    elif days == 1:
        day = TOMORROW[language]
    else:
        day = WEEKDAYS[language][reopens_at.weekday()]

    time_text = clock_time(reopens_at, language)
    singular = reopens_at.hour == 1  # "a la 01:00" / "à 01:00", but "a las 11:00" / "às 11:00"
    if language == "pt":
        return f"{day} {'à' if singular else 'às'} {time_text}"
    if language == "es":
        return f"{day} {'a la' if singular else 'a las'} {time_text}"
    return f"{day} at {time_text}"
