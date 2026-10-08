package course.m07security;

/** Идентификатор, колонка или аргумент вне allowlist. */
public class NotAllowedException extends Exception {
    public NotAllowedException(String message) {
        super("not in allowlist: " + message);
    }
}
