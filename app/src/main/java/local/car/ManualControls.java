package local.car;

import java.util.*;

/** 按键状态只归属于按下它的那根手指；触摸取消、越界或松手均只释放对应按键。 */
final class ManualControls<T> {
    private static final class Press {
        final char direction;
        final int pointer;
        Press(char direction, int pointer) { this.direction = direction; this.pointer = pointer; }
    }
    private final Map<T, Press> held = new LinkedHashMap<>();
    void press(T key, char direction, int pointer) { held.put(key, new Press(direction, pointer)); }
    int pointer(T key) { Press press = held.get(key); return press == null ? -1 : press.pointer; }
    boolean release(T key, int pointer) {
        if (pointer(key) != pointer || !held.containsKey(key)) return false;
        held.remove(key);
        return true;
    }
    boolean cancel(T key) { return held.remove(key) != null; }
    List<T> keys() { return new ArrayList<>(held.keySet()); }
    void clear() { held.clear(); }
    private boolean has(char direction) {
        for (Press press : held.values()) if (press.direction == direction) return true;
        return false;
    }
    String command(int speed, char stunt) {
        boolean f = has('f'), b = has('b'), l = has('l'), r = has('r');
        // 相反方向同时按住时相互抵消；松开其中一根手指后，剩下的方向仍然有效。
        char move = has('x') ? 'x' : stunt != 0 ? stunt :
            f != b ? (f ? (l == r ? 'f' : l ? 'q' : 'e') : (l == r ? 'b' : l ? 'z' : 'c')) :
            l != r ? (l ? 'l' : 'r') : 's';
        return move + "," + (stunt != 0 ? Math.min(speed, 40) : speed);
    }
}
