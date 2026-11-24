# Lean start: Post-build notes

| | |
|---|---|
| **Spec** | [spec.md](spec.md) (Approved) |
| **Plan** | [plan.md](plan.md), M1 |
| **Date** | 2026-11-24 |

## Differences from the spec

None in behaviour. Details the spec left open:

- **Usage message.** The error line now reads `Unknown or repeated option: <arg>` (was `Unknown option:`), since L2 also rejects a repeated flag.
- **`--coach` after a subcommand.** L2 says `--coach` with a subcommand is rejected. `start.bat` scans the arguments after the subcommand and refuses `--coach` anywhere in them, because `--create-sample-db` ignores extra arguments and would otherwise drop the flag silently. Other arguments still pass through to the jar unchanged.
- **LM Studio step messages.** The two hints inside step 5 that said "run start.bat again" now say "run start.bat --coach again", because plain `start.bat` no longer reaches that step. The port-in-use, Java and npm messages still say plain `start.bat`.

## Additions

- README: a new **Use the coach** row in Operations. The Windows Startup section now treats `--coach` in the shortcut as optional and drops the first-run step for plain startup.
- CLAUDE.md Commands: a `start.bat --coach` line.

## Verification

- `mvnw test`: 195 tests, 0 failures, 0 errors. No test pinned the full message text, so no test changed.
- Launcher, run from a scratch copy (`start.bat` and `backend/target/tally.jar` in the same layout), so all data went to a throwaway `data/`. `data/tally.db` was not opened.

| Command | Result |
|---|---|
| `start.bat --bogus` | `Unknown or repeated option: --bogus`, usage, exit 1 |
| `start.bat --coach --coach` / `--rebuild --rebuild` | repeated option rejected, exit 1 |
| `start.bat --coach --create-user x` | rejected at `--create-user`, exit 1 |
| `start.bat --create-user x --coach` / `--create-sample-db --COACH` | rejected at `--coach`, exit 1 |
| `start.bat --coach`, `--rebuild --coach`, `--Coach --REBUILD` | parsed; stopped at the port check because the real app was running at the time |
| `start.bat --create-sample-db` | created the example DB in the scratch `data/`, exit 0 |
| `start.bat --create-user` / `--create-user a b` | the jar's own usage message, exit 1 (passthrough intact) |
| `start.bat` (port 8765 free) | printed the hint line, started no `lms` or `LM Studio` process, served Tally on `127.0.0.1:8765` only (`/api/health` → 401 unauthenticated) |

- **Verified by the user:** a full `start.bat --coach` against LM Studio, `--rebuild --coach` / `--coach --rebuild`, and the Coach tab's offline message in the browser.

## Incidents

- A `sed` edit run from Git Bash rewrote `start.bat` with LF line endings and also changed two unrelated "run start.bat again" lines. Both were fixed before testing: CRLF restored (`.gitattributes` requires it for `.bat`) and those two lines reverted.
