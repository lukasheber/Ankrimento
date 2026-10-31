"""``QueryOp`` and ``CollectionOp`` in the shape of ``aqt.operations``.

Ops run on the task manager's single background thread, so collection work
stays serialized as on desktop. Success and failure callbacks run through
``mw.taskman.run_on_main``.
"""

from __future__ import annotations

from collections.abc import Callable
from concurrent.futures import Future
from typing import Any, Generic, TypeVar

T = TypeVar("T")


def on_op_finished(mw: Any, result: Any, initiator: object | None) -> None:
    """Tell the host which parts of the collection an op changed."""
    changes = getattr(result, "changes", result)
    if changes is None or not hasattr(changes, "SerializeToString"):
        return
    from aqt import gui_hooks

    mw.notify_host("operation_did_execute", changes=changes.SerializeToString())
    gui_hooks.operation_did_execute(changes, initiator)


class _Op(Generic[T]):
    def __init__(self) -> None:
        self._success: Callable[[T], Any] | None = None
        self._failure: Callable[[Exception], Any] | None = None

    def success(self, success: Callable[[T], Any] | None) -> _Op[T]:
        self._success = success
        return self

    def failure(self, failure: Callable[[Exception], Any] | None) -> _Op[T]:
        self._failure = failure
        return self

    def with_progress(self, label: str | None = None) -> _Op[T]:
        return self

    def without_collection(self) -> _Op[T]:
        return self

    def _run(self, op: Callable[[Any], T], on_success: Callable[[T], None]) -> None:
        from aqt import mw

        def task() -> T:
            return op(mw.col)

        def on_done(future: Future) -> None:
            try:
                result = future.result()
            except Exception as error:
                if self._failure is None:
                    raise
                self._failure(error)
                return
            on_success(result)

        mw.taskman.run_in_background(task, on_done)


class QueryOp(_Op[T]):
    def __init__(
        self,
        *,
        parent: Any,
        op: Callable[[Any], T],
        success: Callable[[T], Any],
    ) -> None:
        super().__init__()
        self._op = op
        self._success = success

    def run_in_background(self) -> None:
        def on_success(result: T) -> None:
            if self._success is not None:
                self._success(result)

        self._run(self._op, on_success)


class CollectionOp(_Op[T]):
    def __init__(self, parent: Any, op: Callable[[Any], T]) -> None:
        super().__init__()
        self._op = op

    def run_in_background(self, *, initiator: object | None = None) -> None:
        from aqt import mw

        def on_success(result: T) -> None:
            try:
                if self._success is not None:
                    self._success(result)
            finally:
                on_op_finished(mw, result, initiator)

        self._run(self._op, on_success)
