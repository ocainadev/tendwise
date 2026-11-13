from unittest.mock import MagicMock

import openai
import pytest
from openai.types.responses import Response
from pydantic import SecretStr

from app.llm.client import LLMResponse, Message
from app.llm.openai_client import OpenAIClient


def fake_sdk_response() -> Response:
    """A reply shaped exactly like the SDK's, built locally (no network).

    It starts with a reasoning item, which must not end up in the text.
    """
    return Response.model_validate(
        {
            "id": "resp_test",
            "object": "response",
            "created_at": 0,
            "model": "gpt-6-luna",
            "status": "completed",
            "output": [
                {"type": "reasoning", "id": "rs_test", "summary": []},
                {
                    "type": "message",
                    "id": "msg_test",
                    "role": "assistant",
                    "status": "completed",
                    "content": [
                        {"type": "output_text", "text": "We open at 11am.", "annotations": []}
                    ],
                },
            ],
            "parallel_tool_calls": True,
            "tool_choice": "auto",
            "tools": [],
            "usage": {
                "input_tokens": 42,
                "input_tokens_details": {"cached_tokens": 0, "cache_write_tokens": 0},
                "output_tokens": 7,
                "output_tokens_details": {"reasoning_tokens": 0},
                "total_tokens": 49,
            },
        }
    )


def make_client(sdk: MagicMock) -> OpenAIClient:
    return OpenAIClient(api_key=None, model="gpt-6-luna", default_max_tokens=512, sdk_client=sdk)


def test_complete_maps_sdk_response_to_llm_response() -> None:
    sdk = MagicMock()
    sdk.responses.create.return_value = fake_sdk_response()

    result = make_client(sdk).complete(
        system="You are a helpful clerk.",
        messages=[Message(role="user", content="When do you open?")],
    )

    assert result == LLMResponse(
        text="We open at 11am.",
        model="gpt-6-luna",
        prompt_tokens=42,
        completion_tokens=7,
    )


def test_complete_sends_instructions_input_and_default_max_tokens() -> None:
    sdk = MagicMock()
    sdk.responses.create.return_value = fake_sdk_response()

    make_client(sdk).complete(
        system="You are a helpful clerk.",
        messages=[
            Message(role="user", content="Hi"),
            Message(role="assistant", content="Hello!"),
            Message(role="user", content="When do you open?"),
        ],
    )

    sdk.responses.create.assert_called_once_with(
        model="gpt-6-luna",
        instructions="You are a helpful clerk.",
        input=[
            {"role": "user", "content": "Hi"},
            {"role": "assistant", "content": "Hello!"},
            {"role": "user", "content": "When do you open?"},
        ],
        max_output_tokens=512,
        reasoning={"effort": "none"},
        store=False,
    )


def test_complete_uses_max_tokens_override() -> None:
    sdk = MagicMock()
    sdk.responses.create.return_value = fake_sdk_response()

    make_client(sdk).complete(system="s", messages=[], max_tokens=64)

    assert sdk.responses.create.call_args.kwargs["max_output_tokens"] == 64


def test_missing_usage_counts_zero_tokens() -> None:
    sdk = MagicMock()
    sdk.responses.create.return_value = fake_sdk_response().model_copy(update={"usage": None})

    result = make_client(sdk).complete(system="s", messages=[])

    assert (result.prompt_tokens, result.completion_tokens) == (0, 0)


def test_adapter_is_created_without_a_key_and_only_the_call_fails(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv("OPENAI_API_KEY", raising=False)
    client = OpenAIClient(api_key=None, model="gpt-6-luna", default_max_tokens=512)

    with pytest.raises(openai.OpenAIError):
        client.complete(system="s", messages=[Message(role="user", content="Hi")])


def test_sdk_client_is_created_with_the_configured_key() -> None:
    client = OpenAIClient(
        api_key=SecretStr("sk-test-key"), model="gpt-6-luna", default_max_tokens=512
    )

    sdk = client._get_sdk()

    assert isinstance(sdk, openai.OpenAI)
    assert sdk.api_key == "sk-test-key"
    assert client._get_sdk() is sdk  # created once, then reused
