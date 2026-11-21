package app.fitlocal.auth;

import app.fitlocal.common.ClockConfig;
import app.fitlocal.common.ValidationException;
import java.time.Clock;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Login, user creation and password reset. */
@Service
public class AuthService {

    public static final int MIN_PASSWORD_LENGTH = 8;
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthTokenService tokenService;
    private final Clock clock;
    /** Compared against when the username is unknown, so timing does not reveal which usernames exist. */
    private final String dummyHash;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, AuthTokenService tokenService,
            Clock clock) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode("not-a-real-password");
    }

    /** Returns a fresh token when the credentials are valid. */
    @Transactional(readOnly = true)
    public Optional<String> login(String username, String password) {
        String safePassword = password == null ? "" : password;
        Optional<User> user = username == null ? Optional.empty() : userRepository.findByUsername(username);
        if (user.isEmpty()) {
            passwordEncoder.matches(safePassword, dummyHash);
            return Optional.empty();
        }
        if (!passwordEncoder.matches(safePassword, user.get().getPasswordHash())) {
            return Optional.empty();
        }
        return Optional.of(tokenService.issue(user.get().getUsername(), user.get().getPasswordHash()));
    }

    @Transactional(readOnly = true)
    public boolean userExists(String username) {
        return userRepository.findByUsername(username).isPresent();
    }

    @Transactional
    public void createUser(String username, String password) {
        validateUsername(username);
        validatePassword(password);
        if (userRepository.findByUsername(username).isPresent()) {
            throw new ValidationException("User " + username + " already exists");
        }
        userRepository.save(new User(username, passwordEncoder.encode(password), ClockConfig.now(clock)));
    }

    /** Changing the hash also invalidates every token issued for this user (spec C16). */
    @Transactional
    public void resetPassword(String username, String password) {
        validatePassword(password);
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ValidationException("User " + username + " does not exist"));
        user.setPasswordHash(passwordEncoder.encode(password));
    }

    public static void validateUsername(String username) {
        if (username == null || !USERNAME.matcher(username).matches()) {
            throw new ValidationException(
                    "Username must be 1-64 characters: letters, digits, dot, underscore or hyphen");
        }
    }

    public static void validatePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new ValidationException("Password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
    }
}
