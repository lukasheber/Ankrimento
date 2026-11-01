// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import org.json.JSONObject

/**
 * Page turning by touch for the readers' pages: pulling past the end of the
 * page (or its start) shows a hint and, when let go, goes to the next (or
 * previous) page or chapter. A sideways swipe, while the page fits the
 * width (a zoomed page pans instead), does what [Swipe] says.
 *
 * In an EPUB's paged mode (`window.__ankrimentoPaged`, set by [ReadingPreferences]),
 * a swipe or a tap on the outer quarter of the page turns a whole screen, starting
 * the next one on the line the screen's edge cut; past the chapter's end or start
 * it turns the chapter.
 *
 * Long presses (text selection) and touches with text selected never turn.
 * The script runs [next] or [previous], JavaScript the reader supplies.
 */
object PageGestures {
    enum class Swipe {
        /** Sideways swipes are left to the page. */
        NONE,

        /** A swipe turns to the next or previous page (PDF). */
        TURN,

        /** A swipe moves one screen on or back, keeping a couple of lines in view (EPUB). */
        SCREEN,
    }

    fun script(
        next: String,
        previous: String,
        swipe: Swipe,
        nextHint: String,
        previousHint: String,
    ): String {
        val options =
            JSONObject()
                .put("swipe", swipe.name)
                .put("nextHint", nextHint)
                .put("previousHint", previousHint)
        return "(function (o) {" +
            " if (window.__ankrimentoPageGestures) return; window.__ankrimentoPageGestures = true;" +
            " var next = function () { $next }; var previous = function () { $previous };" +
            " var PULL = 90, SWIPE = 80, LONG_PRESS = 450;" +
            " var hint = document.createElement('div');" +
            " hint.style.cssText = 'position:fixed;left:50%;transform:translateX(-50%);z-index:2147483647;" +
            "padding:8px 16px;border-radius:16px;background:rgba(32,33,36,.88);color:#fff;font:500 14px sans-serif;" +
            "pointer-events:none;opacity:0;transition:opacity .15s';" +
            " document.documentElement.appendChild(hint);" +
            " var start = null;" +
            " function selected() { var s = window.getSelection && window.getSelection(); return s && String(s).length > 0; }" +
            // Some books' pages scroll while document.scrollingElement.scrollTop stays 0 (upstream's
            // `overflow-x: hidden` body makes the body a scroll box): read the window's position.
            " function scrolled() { var d = document.scrollingElement || document.documentElement;" +
            " return Math.max(window.scrollY || 0, d.scrollTop || 0); }" +
            " function height() { var d = document.scrollingElement || document.documentElement;" +
            " return Math.max(d.scrollHeight, document.body ? document.body.scrollHeight : 0); }" +
            " function atBottom() { return scrolled() + window.innerHeight >= height() - 2; }" +
            " function atTop() { return scrolled() <= 1; }" +
            " function fitsWidth() {" +
            " return (document.scrollingElement || document.documentElement).scrollWidth <= window.innerWidth + 1; }" +
            // Paged mode: the line cut by the edge at [edgeY], if any (its top, in the viewport).
            " function cutLine(edgeY) { var cut = null; if (!document.caretRangeFromPoint) return null;" +
            " [0.25, 0.5, 0.75].forEach(function (f) {" +
            " var r = document.caretRangeFromPoint(window.innerWidth * f, edgeY); if (!r || r.startContainer.nodeType !== 3) return;" +
            " var n = r.startContainer, i = Math.min(r.startOffset, Math.max(0, n.length - 1)); if (!n.length) return;" +
            " var c = document.createRange(); c.setStart(n, i); c.setEnd(n, i + 1); var b = c.getBoundingClientRect();" +
            " if (b.height > 0 && b.top < edgeY && b.bottom > edgeY && (cut === null || b.top < cut)) cut = b.top; });" +
            " return cut; }" +
            " function scrollToY(y) { window.scrollTo(0, y);" +
            " if (Math.abs(scrolled() - y) > 2 && document.body) document.body.scrollTop = y; }" +
            " function turnPage(forward) {" +
            " if (forward) { if (atBottom()) { next(); return; }" +
            " var cut = cutLine(window.innerHeight - 1);" +
            " scrollToY(scrolled() + (cut !== null && cut > window.innerHeight / 4 ? cut : window.innerHeight)); }" +
            " else { if (atTop()) { previous(); return; }" +
            " scrollToY(Math.max(0, scrolled() - window.innerHeight)); var top = cutLine(1);" +
            " if (top !== null && top < 0) scrollToY(Math.max(0, scrolled() + top)); }" +
            " window.dispatchEvent(new Event('scroll')); }" +
            " window.addEventListener('click', function (e) {" +
            " if (!window.__ankrimentoPaged || selected() || !e.isTrusted) return;" +
            " var t = e.target; if (t && t.closest && t.closest('a[href],button,input,textarea,select," +
            "[class*=\"incremento-epub-\"]:not(.incremento-epub-scaled)')) return;" +
            " var f = e.clientX / window.innerWidth; if (f > 0.25 && f < 0.75) return;" +
            // Turned here: the reader's tap does not also show or hide its bars.
            " window.__ankrimentoPlainTap = false; turnPage(f >= 0.75); }, true);" +
            " function show(text, bottom) { hint.textContent = text; hint.style.top = bottom ? '' : '16px';" +
            " hint.style.bottom = bottom ? '24px' : ''; hint.style.opacity = '1'; }" +
            " function hide() { hint.style.opacity = '0'; }" +
            " window.addEventListener('touchstart', function (e) {" +
            " if (e.touches.length !== 1 || selected()) { start = null; return; }" +
            " start = { x: e.touches[0].clientX, y: e.touches[0].clientY, t: Date.now(), bottomY: null, topY: null, turn: null }; }," +
            " { passive: true });" +
            " window.addEventListener('touchmove', function (e) {" +
            " if (!start || e.touches.length !== 1) return;" +
            " var y = e.touches[0].clientY, dy = y - start.y, dx = e.touches[0].clientX - start.x;" +
            " if (Math.abs(dx) > Math.abs(dy)) { start.turn = null; hide(); return; }" +
            // The pull counts from where the drag reached the end, which may be partway through it.
            " if (start.bottomY === null && dy < 0 && atBottom()) start.bottomY = y;" +
            " if (start.topY === null && dy > 0 && atTop()) start.topY = y;" +
            " if (start.bottomY !== null && y - start.bottomY < -PULL) { start.turn = 'next'; show(o.nextHint, true); }" +
            " else if (start.topY !== null && y - start.topY > PULL) { start.turn = 'previous'; show(o.previousHint, false); }" +
            " else { start.turn = null; hide(); } }, { passive: true });" +
            " window.addEventListener('touchend', function (e) {" +
            " var s = start; start = null; hide(); if (!s || Date.now() - s.t > 2500 || selected()) return;" +
            " if (s.turn === 'next') { next(); return; } if (s.turn === 'previous') { previous(); return; }" +
            " if (o.swipe === 'NONE' || Date.now() - s.t < 60) return;" +
            " var t = e.changedTouches[0], dx = t.clientX - s.x, dy = t.clientY - s.y;" +
            " if (Date.now() - s.t > LONG_PRESS * 2 || Math.abs(dx) < SWIPE || Math.abs(dx) < 2 * Math.abs(dy) || !fitsWidth()) return;" +
            " if (o.swipe === 'SCREEN' && window.__ankrimentoPaged) { turnPage(dx < 0); return; }" +
            " if (o.swipe === 'SCREEN') { var step = Math.max(window.innerHeight - 48, window.innerHeight * 0.8);" +
            " window.scrollBy({ top: dx < 0 ? step : -step, behavior: 'smooth' }); return; }" +
            " if (dx < 0) next(); else previous(); }, { passive: true });" +
            " window.addEventListener('touchcancel', function () { start = null; hide(); }, { passive: true });" +
            " })($options);"
    }
}
