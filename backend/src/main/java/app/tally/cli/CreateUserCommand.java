package app.tally.cli;

import app.tally.auth.AuthService;
import app.tally.common.ValidationException;
import java.io.Console;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@code --create-user <username>}: creates a user, or offers a password reset when the name exists (spec
 * §5.2). The password is only read from the console, never echoed or accepted as an argument.
 */
@Component
public class CreateUserCommand implements CliCommand {

    private final AuthService authService;

    public CreateUserCommand(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public String name() {
        return "--create-user";
    }

    @Override
    public int run(List<String> args) {
        if (args.size() != 1) {
            System.err.println("Usage: start.bat --create-user <username>");
            return 1;
        }
        String username = args.getFirst();
        Console console = System.console();
        if (console == null) {
            System.err.println("No console available: run this from a terminal (Command Prompt or PowerShell).");
            return 1;
        }
        try {
            AuthService.validateUsername(username);
            boolean exists = authService.userExists(username);
            if (exists) {
                String answer = console.readLine("User %s exists. Reset password? [y/N] ", username);
                if (answer == null || !answer.strip().equalsIgnoreCase("y")) {
                    System.out.println("No changes made.");
                    return 0;
                }
            }
            String password = readPassword(console);
            if (exists) {
                authService.resetPassword(username, password);
                System.out.println("Password reset for " + username + ". Devices signed in as this user are signed out.");
            } else {
                authService.createUser(username, password);
                System.out.println("User " + username + " created.");
            }
            return 0;
        } catch (ValidationException e) {
            System.err.println(e.getMessage());
            return 1;
        }
    }

    private static String readPassword(Console console) {
        char[] first = console.readPassword("Password (min %d characters): ", AuthService.MIN_PASSWORD_LENGTH);
        char[] second = console.readPassword("Confirm password: ");
        try {
            if (first == null || second == null || !Arrays.equals(first, second)) {
                throw new ValidationException("Passwords do not match.");
            }
            String password = new String(first);
            AuthService.validatePassword(password);
            return password;
        } finally {
            if (first != null) {
                Arrays.fill(first, '\0');
            }
            if (second != null) {
                Arrays.fill(second, '\0');
            }
        }
    }
}
