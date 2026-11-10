package local.car;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 手机只提交操作与保活；录制时间轴、返程倒序和逐段执行全部由 ESP 完成。 */
final class MotionEngine {
    interface Clock { long now(); }
    interface Transport { Reply exchange(String path, String body); }
    interface Listener { void event(String event, List<Frame> frames); }
    enum State { IDLE, STARTING, RECORDING, FINISHING, READY, UPLOADING, PLAYING, SAVING }
    static final long MAX_MS = 60000;
    static final int MAX_FRAMES = 2048;
    // 保留 ms 兼容旧技能与显示，新增 micros 无损保存 ESP 录制的微秒时长。
    static final class Frame {
        final String command;
        final long ms, micros;
        Frame(String command, long ms) { this(command, checkedMicros(ms), true); }
        private Frame(String command, long micros, boolean precise) {
            if (!valid(command) || micros <= 0 || micros > MAX_MS * 1000) throw new IllegalArgumentException("Invalid frame");
            this.command = command; this.micros = micros; this.ms = Math.max(1, (micros + 500) / 1000);
        }
        private static long checkedMicros(long ms) {
            if (ms <= 0 || ms > MAX_MS) throw new IllegalArgumentException("Invalid duration");
            return ms * 1000;
        }
        static Frame fromMicros(String command, long us) { return new Frame(command, us, true); }
    }
    // 网络层先校验 JSON 和协议版本，再把结构化结果交给此类；便于脱离 Android 做协议回归。
    static final class Reply {
        final boolean ok, unsupported;
        final String state, reason;
        final int session;
        final List<Frame> frames;
        Reply(boolean ok, boolean unsupported, String state, int session, String reason, List<Frame> frames) {
            this.ok = ok; this.unsupported = unsupported; this.state = state; this.session = session; this.reason = reason; this.frames = frames;
        }
        static Reply failed() { return new Reply(false, false, null, 0, "", null); }
        static Reply manual() { return new Reply(true, false, null, 0, "", null); }
    }
    private static final AtomicInteger TOKENS = new AtomicInteger((int)(System.nanoTime() & 0x7fffffff));
    private static int nextToken() { int token = TOKENS.updateAndGet(value -> value == Integer.MAX_VALUE ? 1 : value + 1); return token == 0 ? TOKENS.incrementAndGet() : token; }
    private static final class Operation {
        final String path, body, kind;
        Operation(String path, String body, String kind) { this.path = path; this.body = body; this.kind = kind; }
    }
    private final Clock clock;
    private final Transport transport;
    private final Listener listener;
    private State state = State.IDLE;
    private boolean enabled, connected, compatible, oldFirmware, skill, delivered;
    private String desired = "s,0", lastSent;
    private long epoch, revision, lastPoll = -1000, lastDrive = -1000, lastSuccess = -1, retryAt;
    private int failures, session;
    private Operation pending;
    MotionEngine(Clock clock, Transport transport, Listener listener) { this.clock = clock; this.transport = transport; this.listener = listener; }
    synchronized State state() { return state; }
    synchronized boolean isSkill() { return skill; }
    synchronized boolean connected() { return connected; }
    synchronized boolean compatible() { return compatible; }
    synchronized boolean oldFirmware() { return oldFirmware; }
    synchronized boolean recovering() { return connected && failures > 0; }
    synchronized long revision() { return revision; }
    synchronized void enable(boolean value) {
        cancel(); enabled = value; connected = false; failures = 0; lastSuccess = -1;
        oldFirmware = false; compatible = false;
    }
    // 所有取消都会先发板端 cancel；即使旧的开始请求正在路上，也不能留下“幽灵录制”。
    synchronized void cancel() {
        epoch++; revision++; retryAt = 0; state = State.IDLE;
        desired = "s,0"; lastSent = null; delivered = false;
        pending = new Operation("/api/motion", "cancel", "cancel");
    }
    synchronized void manual(String command) {
        if (!valid(command)) throw new IllegalArgumentException("Invalid command");
        if (state != State.IDLE && state != State.RECORDING && state != State.STARTING) cancel();
        if (!command.equals(desired)) { revision++; retryAt = 0; }
        desired = command;
    }
    synchronized boolean record(boolean saveSkill) {
        if (!enabled || !connected || !compatible || failures > 0) return false;
        cancel(); session = nextToken(); skill = saveSkill; state = State.STARTING;
        pending = new Operation("/api/motion", "start," + session + "," + (skill ? "k" : "r"), "start");
        return true;
    }
    synchronized void finish() {
        if (state != State.RECORDING) return;
        state = State.FINISHING; desired = "s,0";
        pending = new Operation("/api/motion", "end," + session, "end");
    }
    synchronized boolean playReturn(int forward, int backward) {
        if (state != State.READY || !connected || !compatible || forward < 50 || forward > 200 || backward < 50 || backward > 200) return false;
        revision++; state = State.PLAYING;
        pending = new Operation("/api/motion", "return," + session + "," + forward + "," + backward, "return");
        return true;
    }
    synchronized boolean play(List<Frame> frames) {
        if (!enabled || !connected || !compatible || failures > 0 || frames.isEmpty() || frames.size() > MAX_FRAMES) return false;
        long total = 0; for (Frame frame : frames) total += frame.micros;
        if (total > MAX_MS * 1000) return false;
        cancel(); session = nextToken(); skill = true; state = State.UPLOADING;
        StringBuilder body = new StringBuilder().append(session).append('\n');
        for (Frame frame : frames) body.append(frame.command).append(',').append(frame.micros).append('\n');
        pending = new Operation("/api/sequence", body.toString(), "upload");
        return true;
    }
    // 只有手机技能库确认写盘成功之后才发 ack，板子随即清除技能缓存；重试 ack 不重复执行。
    synchronized void confirmSaved() {
        if (state != State.SAVING) return;
        state = State.FINISHING;
        pending = new Operation("/api/motion", "ack," + session, "ack");
    }
    void tick() {
        Operation sending; long version;
        synchronized (this) {
            long now = clock.now();
            if (connected && failures > 0 && now - lastSuccess >= 600) {
                connected = false; cancel(); listener.event("disconnected", null);
            }
            if (now < retryAt) return;
            if (pending != null) sending = pending;
            else if (!enabled) return;
            else if ((state == State.IDLE || state == State.RECORDING) &&
                    (!desired.equals(lastSent) || (oldFirmware && now - lastDrive >= 150))) {
                sending = new Operation("/api/drive", desired, "drive");
            } else if (!oldFirmware && now - lastPoll >= 150) {
                // 自动执行期间只保活/查询，绝不能不断发送 s,0，否则会取消板端动作。
                sending = new Operation("/api/motion", "ping", "ping");
            } else return;
            version = epoch;
        }
        Reply reply = transport.exchange(sending.path, sending.body);
        synchronized (this) {
            if (version != epoch) return;
            long now = clock.now();
            if (reply.unsupported) {
                oldFirmware = true; compatible = false; connected = false;
                if (pending == sending) pending = null;
                state = State.IDLE; desired = "s,0"; lastSent = null;
                listener.event("firmware", null);
                return;
            }
            if (!reply.ok) {
                failures++;
                retryAt = now + (enabled ? 40 : 150);
                // 短抖动不取消 ESP 的记录；开始/结束/执行操作带同一 session 重试，板端保证幂等。
                if (lastSuccess >= 0 && now - lastSuccess >= 600 && connected) {
                    connected = false; cancel(); listener.event("disconnected", null);
                }
                return;
            }
            connected = enabled; lastSuccess = now; failures = 0; retryAt = 0;
            if (pending == sending) pending = null;
            if (sending.kind.equals("drive")) { lastSent = sending.body; lastDrive = now; return; }
            compatible = true; oldFirmware = false; lastPoll = now;
            if (sending.kind.equals("cancel")) { lastSent = null; return; }
            if (sending.kind.equals("ack")) { state = State.IDLE; lastSent = null; return; }
            if (state == State.IDLE) return;
            if (reply.session != session) { abort("failed"); return; }
            if ("recording".equals(reply.state)) { if (state == State.STARTING) state = State.RECORDING; }
            else if ("ready".equals(reply.state)) {
                if (state == State.FINISHING) { state = State.READY; listener.event("ready", null); }
            } else if ("playing".equals(reply.state)) state = State.PLAYING;
            else if ("export".equals(reply.state)) {
                if (!delivered && reply.frames != null && !reply.frames.isEmpty()) {
                    delivered = true; state = State.SAVING; listener.event("save", new ArrayList<>(reply.frames));
                }
            } else if ("idle".equals(reply.state)) {
                String event = "done".equals(reply.reason) ? "done" : "empty".equals(reply.reason) ? "empty" : "limit".equals(reply.reason) ? "limit" : "failed";
                abort(event);
            }
        }
    }
    // 板子已经结束或拒绝任务时清掉本地临时状态；不会用手机列表继续补播动作。
    private void abort(String event) { state = State.IDLE; desired = "s,0"; lastSent = null; revision++; listener.event(event, null); }
    static boolean valid(String command) {
        return command != null && command.matches("[fblrqezcsx],[0-9]{1,3}") && Integer.parseInt(command.substring(2)) <= 100;
    }
}
