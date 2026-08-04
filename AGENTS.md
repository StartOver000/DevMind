# DevMind Agent Execution Rules

## Core rule

Do not stop after describing a plan. When a task requires code, documentation, testing, or diagnosis, perform the next concrete tool action in the same turn.

## Required loop

For every development request:

1. Read the smallest relevant file or symbol.
2. State one local hypothesis and one cheap check.
3. Make the smallest useful edit or run the check immediately.
4. Run a focused validation after each substantive edit.
5. Continue until the requested stage is complete or a real external blocker is confirmed.
6. Update the progress document only after the work and validation are complete.

## No plan-only responses

A response is incomplete if it only contains phrases such as "I will start", "next I will", "待我处理", or a proposed command without executing it. If execution is possible, use the terminal or file tools first.

## Handling failures

- Do not silently stop after a failed command.
- Classify the failure as code, environment, network, permission, or user decision.
- For code or configuration failures, repair the same slice and rerun the focused check.
- For network failures, retry a bounded number of times, then use a documented local fallback when available.
- For permission or authentication failures, report the exact user action required and preserve the current progress.

## Scope and progress

- Work on one implementation stage at a time.
- Do not start the next stage before the current stage has a validation result.
- Keep changes reversible and avoid unrelated refactors.
- Never claim a task is complete without an executable validation result.
- If the session is interrupted, resume from the latest actual file state, not from the previous plan message.

## DevMind deployment fallback

When Docker Hub is unavailable, use the project fallback: build with the local JDK/Maven, start PostgreSQL with Docker, and run the DevMind JAR locally. Do not repeatedly retry Docker indefinitely.
