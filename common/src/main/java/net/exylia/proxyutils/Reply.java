package net.exylia.proxyutils;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * What a module answers.
 *
 * @param status what happened
 * @param detail a reason, or what was found; empty is fine
 */
public record Reply(@NotNull Status status, @NotNull String detail) {

    /** How a request ended, with the byte that says so on the wire. */
    public enum Status {
        /** The module did what was asked. */
        OK(0),
        /** The module understood the request and refused it. */
        REJECTED(1),
        /** The module threw. */
        FAILED(2),
        /** No module by that name is registered here. */
        UNKNOWN_MODULE(3);

        private final int code;

        Status(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }
    }

    public static @NotNull Reply ok() {
        return new Reply(Status.OK, "");
    }

    public static @NotNull Reply ok(@NotNull String detail) {
        return new Reply(Status.OK, detail);
    }

    public static @NotNull Reply rejected(@NotNull String reason) {
        return new Reply(Status.REJECTED, reason);
    }

    public static @NotNull Reply failed(@Nullable Throwable error) {
        String message = error == null ? "unknown error" : String.valueOf(error.getMessage());
        return new Reply(Status.FAILED, message);
    }

    public static @NotNull Reply unknownModule(@NotNull String name) {
        return new Reply(Status.UNKNOWN_MODULE, "no module \"" + name + "\" on this proxy");
    }
}
