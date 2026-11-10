package local.car;

import org.json.*;
import java.util.*;

/** 验证 ESP 响应，保留板载微秒时间；不接受缺帧、非法指令或超长技能。 */
final class BoardProtocol {
    static MotionEngine.Reply parse(String text) throws JSONException {
        JSONObject value = new JSONObject(text);
        if (value.getInt("protocol") != 2) throw new JSONException("Unsupported motion protocol");
        String state = value.getString("state");
        if (!Arrays.asList("idle", "recording", "ready", "playing", "export").contains(state)) throw new JSONException("Invalid state");
        long session = value.getLong("session");
        if (session < 0 || session > Integer.MAX_VALUE) throw new JSONException("Invalid session");
        List<MotionEngine.Frame> frames = null;
        if (value.has("frames")) {
            JSONArray data = value.getJSONArray("frames");
            if (data.length() > MotionEngine.MAX_FRAMES || data.length() != value.getInt("count")) throw new JSONException("Incomplete frames");
            frames = new ArrayList<>(); long total = 0;
            for (int i = 0; i < data.length(); i++) {
                JSONObject frame = data.getJSONObject(i);
                try { frames.add(MotionEngine.Frame.fromMicros(frame.getString("command"), frame.getLong("us"))); }
                catch (IllegalArgumentException error) { throw new JSONException("Invalid frame"); }
                total += frames.get(i).micros;
            }
            if (total > MotionEngine.MAX_MS * 1000) throw new JSONException("Recording too long");
        }
        return new MotionEngine.Reply(true, false, state, (int)session, value.getString("reason"), frames);
    }
}
