package app.fitlocal;

import app.fitlocal.cli.CliCommand;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Entry point. {@code --create-user} and {@code --create-sample-db} run as one-shot
 * commands with no web server; anything else starts the app on 127.0.0.1:8765 (spec §5.1).
 */
@SpringBootApplication
public class FitLocalApplication {

    static final Set<String> COMMANDS = Set.of("--create-user", "--create-sample-db");

    public static void main(String[] args) {
        if (args.length > 0 && COMMANDS.contains(args[0])) {
            System.exit(runCommand(args[0], List.of(Arrays.copyOfRange(args, 1, args.length))));
        }
        if (args.length > 0 && args[0].startsWith("--") && !args[0].contains("=")) {
            // A typo such as --create-users must not start the server.
            System.err.println("Unknown option: " + args[0]);
            System.err.println(usage());
            System.exit(1);
        }
        SpringApplication.run(FitLocalApplication.class, args);
    }

    private static int runCommand(String name, List<String> commandArgs) {
        SpringApplication app = new SpringApplication(FitLocalApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setDefaultProperties(Map.of(
                "spring.main.banner-mode", "off",
                "logging.level.root", "WARN"));
        try (ConfigurableApplicationContext context = app.run()) {
            CliCommand command = context.getBeansOfType(CliCommand.class).values().stream()
                    .filter(c -> c.name().equals(name))
                    .findFirst()
                    .orElseThrow();
            return command.run(commandArgs);
        } catch (RuntimeException e) {
            System.err.println("Failed: " + e.getMessage());
            return 1;
        }
    }

    static String usage() {
        return """
                Usage:
                  fitlocal.jar                            start the web app on 127.0.0.1:8765
                  fitlocal.jar --create-user <username>   create a user, or reset an existing user's password
                  fitlocal.jar --create-sample-db         regenerate data/fitlocal.db.example from schema.sql""";
    }
}
