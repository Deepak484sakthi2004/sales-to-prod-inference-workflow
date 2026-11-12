"""A scripted model for tests: replies are a function of the request, and every request is recorded."""
from app.gemini import FunctionCall


class FakeModel:
    def __init__(self, reply):
        self.reply = reply
        self.calls: list[dict] = []

    def call_function(self, *, system, user, functions, allowed) -> FunctionCall:
        req = dict(system=system, user=user, functions=functions, allowed=allowed)
        self.calls.append(req)
        name, args = self.reply(req)
        return FunctionCall(name=name, args=args, model="fake-gemini")
