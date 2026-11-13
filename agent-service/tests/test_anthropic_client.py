from unittest.mock import MagicMock

from anthropic.types import Message as SdkMessage
from anthropic.types import TextBlock, ThinkingBlock, Usage

from app.llm.anthropic_client import AnthropicClient
from app.llm.client import LLMResponse, Message


def fake_sdk_response() -> SdkMessage:
    """A reply shaped exactly like the SDK's, built locally (no network)."""
    return SdkMessage(
        id="msg_test",
        type="message",
        role="assistant",
        model="claude-haiku-5-5",
        content=[
            ThinkingBlock(type="thinking", thinking="", signature="sig"),
            TextBlock(type="text", text="We open at 11am.", citations=None),
        ],
        stop_reason="end_turn",
        stop_sequence=None,
        usage=Usage(input_tokens=42, output_tokens=7),
    )


def make_client(sdk: MagicMock) -> AnthropicClient:
    return AnthropicClient(
        api_key=None, model="claude-haiku-5-5", default_max_tokens=512, sdk_client=sdk
    )


def test_complete_maps_sdk_response_to_llm_response() -> None:
    sdk = MagicMock()
    sdk.messages.create.return_value = fake_sdk_response()

    result = make_client(sdk).complete(
        system="You are a helpful clerk.",
        messages=[Message(role="user", content="When do you open?")],
    )

    assert result == LLMResponse(
        text="We open at 11am.",
        model="claude-haiku-5-5",
        prompt_tokens=42,
        completion_tokens=7,
    )


def test_complete_sends_model_system_messages_and_default_max_tokens() -> None:
    sdk = MagicMock()
    sdk.messages.create.return_value = fake_sdk_response()

    make_client(sdk).complete(
        system="You are a helpful clerk.",
        messages=[
            Message(role="user", content="Hi"),
            Message(role="assistant", content="Hello!"),
            Message(role="user", content="When do you open?"),
        ],
    )

    sdk.messages.create.assert_called_once_with(
        model="claude-haiku-5-5",
        max_tokens=512,
        system="You are a helpful clerk.",
        messages=[
            {"role": "user", "content": "Hi"},
            {"role": "assistant", "content": "Hello!"},
            {"role": "user", "content": "When do you open?"},
        ],
    )


def test_complete_uses_max_tokens_override() -> None:
    sdk = MagicMock()
    sdk.messages.create.return_value = fake_sdk_response()

    make_client(sdk).complete(system="s", messages=[], max_tokens=64)

    assert sdk.messages.create.call_args.kwargs["max_tokens"] == 64
