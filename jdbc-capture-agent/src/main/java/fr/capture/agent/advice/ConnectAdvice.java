package fr.capture.agent.advice;

import fr.capture.agent.runtime.Hooks;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

import java.sql.Connection;

/**
 * Byte Buddy advice inlined at the RETURN of {@code java.sql.Driver#connect} and
 * {@code javax.sql.DataSource#getConnection} on every (non-JDK) implementation. The body is a
 * single guarded call that swaps the returned {@link Connection} for our proxy.
 *
 * <p>{@code suppress = Throwable.class} makes Byte Buddy wrap the advice in a try/catch that
 * discards any error — a second safety net on top of {@link Hooks} being fully guarded.</p>
 */
public final class ConnectAdvice {

    private ConnectAdvice() {}

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.Origin("#m") String method,
                              @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Connection returned) {
        returned = Hooks.onConnection(returned, method);
    }
}
