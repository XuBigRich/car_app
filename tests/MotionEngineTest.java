package local.car;

import java.util.*;

/** 协议协调层测试：手机不再记录或回放分段时间，只与模拟 ESP 交换整包操作。 */
public final class MotionEngineTest {
    static final class Rig {
        long now;
        String board = "idle", reason = "cancel";
        int token, begins, launches;
        boolean skill, failNext, unsupported;
        Runnable during;
        final List<String> requests = new ArrayList<>(), events = new ArrayList<>();
        List<MotionEngine.Frame> saved;
        MotionEngine engine = new MotionEngine(() -> now, this::exchange, (event, frames) -> {
            events.add(event); if (frames != null) saved = frames;
        });
        Rig() { engine.enable(true); engine.tick(); }
        MotionEngine.Reply exchange(String path, String body) {
            requests.add(path + " " + body);
            if (unsupported && !path.equals("/api/drive")) return new MotionEngine.Reply(false, true, null, 0, "", null);
            String[] parts = body.split(",");
            if (path.equals("/api/drive")) {
                if (!board.equals("recording")) board = "idle";
                if (during != null) { Runnable action = during; during = null; action.run(); }
                if (failNext) { failNext = false; return MotionEngine.Reply.failed(); }
                return MotionEngine.Reply.manual();
            }
            if (path.equals("/api/sequence")) {
                int requested = Integer.parseInt(body.substring(0, body.indexOf('\n')));
                if (requested != token) { token = requested; launches++; board = "playing"; }
            } else if (parts[0].equals("cancel")) { board = "idle"; reason = "cancel"; }
            else if (parts[0].equals("start")) {
                int requested = Integer.parseInt(parts[1]);
                if (requested != token) { token = requested; begins++; board = "recording"; skill = parts[2].equals("k"); }
            } else if (parts[0].equals("end")) board = skill ? "export" : "ready";
            else if (parts[0].equals("return")) { board = "playing"; launches++; }
            else if (parts[0].equals("ack")) { board = "idle"; reason = "exported"; }
            if (during != null) { Runnable action = during; during = null; action.run(); }
            if (failNext) { failNext = false; return MotionEngine.Reply.failed(); }
            List<MotionEngine.Frame> frames = board.equals("export") && parts[0].equals("end") ?
                Arrays.asList(MotionEngine.Frame.fromMicros("q,40", 123456), MotionEngine.Frame.fromMicros("f,65", 234567)) : null;
            return new MotionEngine.Reply(true, false, board, token, reason, frames);
        }
        void after(long ms) { now += ms; engine.tick(); }
        void record(boolean skill) { check(engine.record(skill), "Can request recording"); engine.tick(); check(engine.state() == MotionEngine.State.RECORDING, "ESP confirmed recording"); }
        void ready() { record(false); engine.finish(); engine.tick(); check(engine.state() == MotionEngine.State.READY, "Return ready on board"); }
    }
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static String last(Rig r) { return r.requests.get(r.requests.size() - 1); }
    public static void main(String[] args) {
        Rig r = new Rig(); r.record(false); r.engine.manual("q,70"); r.engine.tick();
        check(last(r).equals("/api/drive q,70"), "Manual direction and speed sent unchanged");
        r.engine.finish(); r.engine.tick();
        check(last(r).startsWith("/api/motion end,"), "ESP closes timing, no phone duration sent");
        check(r.engine.state() == MotionEngine.State.READY && r.saved == null, "Temporary route is not downloaded");
        check(r.engine.playReturn(120, 80), "Request board return"); r.engine.tick();
        check(last(r).endsWith(",120,80"), "Board receives calibration, not reversed phone frames");
        int start = r.requests.size();
        for (int i = 0; i < 10; i++) r.after(200);
        for (String request : r.requests.subList(start, r.requests.size())) check(request.equals("/api/motion ping"), "Playing uses only board heartbeat");
        r.reason = "done"; r.board = "idle"; r.after(200);
        check(r.engine.state() == MotionEngine.State.IDLE && r.events.contains("done"), "Board completion determines UI completion");

        Rig skill = new Rig(); skill.record(true); skill.engine.finish(); skill.engine.tick();
        check(skill.engine.state() == MotionEngine.State.SAVING && skill.saved.get(0).micros == 123456, "Board export retains microsecond precision");
        check(skill.requests.stream().noneMatch(value -> value.contains(" ack,")), "No premature board delete before durable save");
        skill.after(200); check(skill.board.equals("export"), "Heartbeat preserves pending skill transfer");
        skill.engine.confirmSaved(); skill.engine.tick();
        check(last(skill).startsWith("/api/motion ack,") && skill.board.equals("idle"), "Clear board only after app confirms saved");
        check(skill.engine.play(skill.saved), "Saved skill can be uploaded again"); skill.engine.tick();
        check(last(skill).startsWith("/api/sequence ") && last(skill).contains("q,40,123456\n"), "One complete skill packet, exact board timing");

        Rig retry = new Rig(); retry.failNext = true; check(retry.engine.record(false), "Queue start"); retry.engine.tick();
        String same = last(retry); retry.after(50);
        check(last(retry).equals(same) && retry.begins == 1, "Retry uses same session and cannot reset recording");

        Rig override = new Rig(); override.ready(); override.engine.playReturn(100, 100);
        override.engine.manual("e,35"); override.engine.tick(); override.engine.tick();
        check(last(override).equals("/api/drive e,35"), "Manual input cancels pending automatic operation");
        check(override.requests.stream().noneMatch(value -> value.contains(" return,")), "Cancelled return never starts");

        Rig inFlight = new Rig(); inFlight.engine.record(false);
        inFlight.during = () -> inFlight.engine.manual("q,40");
        inFlight.engine.tick(); inFlight.engine.tick();
        check(last(inFlight).equals("/api/drive q,40"), "Fast press during start is recorded after board confirms start");
        Rig cancelStart = new Rig(); cancelStart.engine.record(false);
        cancelStart.during = () -> cancelStart.engine.cancel();
        cancelStart.engine.tick(); cancelStart.engine.tick();
        check(cancelStart.board.equals("idle") && cancelStart.engine.state() == MotionEngine.State.IDLE, "Late start ack cannot leave ghost recording");

        Rig timeout = new Rig(); timeout.engine.manual("f,40"); timeout.failNext = true; timeout.engine.tick();
        timeout.engine.manual("q,40"); timeout.engine.tick();
        check(last(timeout).equals("/api/drive q,40"), "Old request failure cannot erase latest button");
        timeout.failNext = true; timeout.after(700);
        timeout.engine.tick();
        check(last(timeout).equals("/api/motion cancel"), "Sustained loss recovers via cancel, not resumed motion");

        Rig old = new Rig(); old.engine.tick(); old.unsupported = true; old.after(200);
        check(old.engine.oldFirmware() && !old.engine.record(false), "Old firmware cannot silently use phone timing");
        check(!MotionEngine.valid("f,101") && !MotionEngine.valid("u,40"), "Protocol validation");
        System.out.println("PASS: ESP recording protocol, board-only playback, precise export, save-before-delete, upload, retries, fast input and cancellation");
    }
}
