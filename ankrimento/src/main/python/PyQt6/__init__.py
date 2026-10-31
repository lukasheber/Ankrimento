"""Import-time stand-in for the parts of PyQt6 Incremento's backend names.

Only ``PyQt6.QtPdf`` exists; any other PyQt6 module fails to import, which
keeps desktop-only code paths off mobile.
"""
