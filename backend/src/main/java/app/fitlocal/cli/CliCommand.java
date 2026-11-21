package app.fitlocal.cli;

import java.util.List;

/** A one-shot command run from {@code start.bat --<name> ...} with no web server (spec §5.1). */
public interface CliCommand {

    /** The flag, including the leading dashes, e.g. {@code --create-user}. */
    String name();

    /** Runs the command and returns the process exit code (0 success, 1 failure). */
    int run(List<String> args);
}
