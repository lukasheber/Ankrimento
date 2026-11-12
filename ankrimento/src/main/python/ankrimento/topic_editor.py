"""Editing a topic where it is read.

The phone edits the topic's text as formatted text (the page itself made editable), never as HTML. The
one part that must not be touched is the link back to its source: a ``pycmd`` blob in an ``<a>``. In the
editor it is a chip showing only its caption; on saving, each chip becomes the original link again, and a
chip that was deleted comes back at the end, so the way back to the source cannot be lost by a slip.
"""

from __future__ import annotations

import re

from ankrimento import runtime

_LINK = re.compile(r"<a\b[^>]*incremento_open_[^>]*>.*?</a>", re.S | re.I)
_CHIP = re.compile(r"<span\b[^>]*\bdata-source-link=[\"'](\d+)[\"'][^>]*>.*?</span>", re.S | re.I)
_EDITING_ATTRS = re.compile(r"\s+(?:contenteditable|spellcheck)=(?:\"[^\"]*\"|'[^']*')", re.I)


def _caption(link: str) -> str:
    text = re.sub(r"<[^>]+>", "", link).strip()
    return text or "↗"


def _front(card_id: int):
    note = runtime.current().col.get_card(int(card_id)).note()
    return note, note.fields[0]


def editable(card_id: int) -> dict[str, str]:
    """The topic's first field for the editor, its source links as chips."""
    note, front = _front(card_id)
    links = _LINK.findall(front)
    html = front
    for index, link in enumerate(links):
        chip = f'<span data-source-link="{index}" contenteditable="false" class="ankrimento-source">{_caption(link)}</span>'
        html = html.replace(link, chip, 1)
    return {"html": html, "field": note.keys()[0]}


def save(card_id: int, html: str) -> None:
    """Writes the edited text back, the source links as they were."""
    from aqt import mw
    from aqt.operations import on_op_finished

    note, front = _front(card_id)
    links = _LINK.findall(front)
    seen: set[int] = set()

    def restore(match: re.Match) -> str:
        index = int(match.group(1))
        if index >= len(links) or index in seen:
            return ""
        seen.add(index)
        return links[index]

    text = _CHIP.sub(restore, str(html))
    text = _EDITING_ATTRS.sub("", text)
    for index, link in enumerate(links):
        if index not in seen:
            text += "<br>" + link
    if text == front:
        return
    note.fields[0] = text
    changes = runtime.current().col.update_note(note)
    on_op_finished(mw, changes, None)
