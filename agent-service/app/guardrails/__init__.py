"""Guardrails: cheap, deterministic checks around the agent (no LLM calls).

- Input (`guard_input` node): length cap, prompt-injection patterns and a small
  list of abusive phrases, checked before the message reaches any model.
- Output (`guard_output` node): masks phone numbers and e-mail addresses in the
  reply and caps its length, so the customer never sees personal data.

Plain regexes and word lists are not perfect, but they are free, instant,
predictable and easy to test. A moderation API or an LLM judge can be added
later on top of them.
"""
