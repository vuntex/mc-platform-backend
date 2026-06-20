# CLAUDE.md
- Lies immer zuerst PROGRESS.md für den aktuellen Stand und die Architektur.
- Hexagonal/DDD: core-domain hat KEINE Framework-Imports (kein Spring, kein jOOQ).
- Economy ist event-sourced. Niemals balance direkt mutieren — immer über ein Event.
- Geld ist BIGINT, nie Float.
- Jede Code-Änderung, die den Stand verschiebt, wird in PROGRESS.md nachgezogen.
- Erst Tests/lauffähig, dann in die Breite.
