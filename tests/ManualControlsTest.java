package local.car;

/** 按键状态测试：多指组合、独立松手、迟到松手、触摸取消和相反方向互相抵消。 */
public final class ManualControlsTest {
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) {
        ManualControls<String> keys = new ManualControls<>();
        keys.press("throttle", 'f', 3);
        keys.press("steering", 'l', 7);
        check(keys.command(40, (char) 0).equals("q,40"), "Two-finger forward + left");
        check(!keys.release("throttle", 7), "Other finger cannot release throttle");
        check(keys.release("steering", 7), "Release steering owner");
        check(keys.command(40, (char) 0).equals("f,40"), "Releasing turn preserves forward");
        keys.press("steering", 'r', 8);
        check(!keys.release("steering", 7), "Late old pointer-up cannot release new press");
        check(keys.command(70, (char) 0).equals("e,70"), "New right turn and updated speed retained");
        keys.cancel("throttle");
        check(keys.command(70, (char) 0).equals("r,70"), "Cancelling throttle preserves held steering");
        keys.clear();
        check(!keys.release("steering", 8), "Release after automation cleared buttons ignored");
        check(keys.command(40, (char) 0).equals("s,40"), "All released sends stop");
        keys.press("f", 'f', 1); keys.press("b", 'b', 2);
        check(keys.command(40, (char) 0).equals("s,40"), "Opposite throttle cancels");
        keys.release("b", 2);
        check(keys.command(40, (char) 0).equals("f,40"), "Remaining finger still active");
        keys.press("brake", 'x', 4);
        check(keys.command(40, 'q').equals("x,40"), "Brake takes precedence");
        keys.clear();
        check(keys.command(80, 'e').equals("e,40"), "Short arc remains speed-limited");
        System.out.println("PASS: multi-touch ownership and button combinations");
    }
}
