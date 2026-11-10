package local.car;

import android.app.*;
import android.content.Intent;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicReference;

/** 真实页面触摸回归测试：测试开始前换成模拟传输，所有按钮事件都不会发送到小车。 */
public final class ControlStabilityInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args) { start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        Activity activity = null;
        try {
            activity = startActivitySync(new Intent(getTargetContext(), RemoteActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            final Activity target = activity;
            AtomicReference<Throwable> error = new AtomicReference<>();
            runOnMainSync(() -> {
                try {
                    // 页面初始只会请求停车；在触摸按钮前释放真实网络并换成纯内存传输。
                    ((CarWifi) field(target, "wifi")).stop();
                    ((MotionEngine) field(target, "engine")).enable(false);
                    MotionEngine fake = new MotionEngine(android.os.SystemClock::elapsedRealtime, (path, body) -> path.equals("/api/drive") ? MotionEngine.Reply.manual() : new MotionEngine.Reply(true, false, "idle", 0, "cancel", null), (event, frames) -> {});
                    fake.enable(true);
                    setField(target, "engine", fake);
                    ViewGroup root = (ViewGroup) ((ViewGroup) target.findViewById(android.R.id.content)).getChildAt(0);
                    int width = Math.round(800 * target.getResources().getDisplayMetrics().density);
                    int height = Math.round(320 * target.getResources().getDisplayMetrics().density);
                    layout(root, width, height);
                    Button forward = button(root, "▲ 前进"), left = button(root, "◀ 左转");
                    check(forward != null && left != null, "Control buttons exist");
                    @SuppressWarnings("unchecked") ManualControls<View> controls = (ManualControls<View>) field(target, "held");
                    touch(forward, MotionEvent.ACTION_DOWN, new int[]{3}, forward.getWidth()/2f, forward.getHeight()/2f);
                    touch(left, MotionEvent.ACTION_DOWN, new int[]{7}, left.getWidth()/2f, left.getHeight()/2f);
                    check(controls.command(40, (char) 0).equals("q,40"), "Actual two-finger touch composes forward-left");
                    // 第二根手指的抬起事件落在同一个 View 时，也不能误释放拥有此按钮的第一根手指。
                    touch(forward, MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), new int[]{3, 9}, 1, 1);
                    check(controls.command(40, (char) 0).equals("q,40"), "Unrelated pointer-up ignored");
                    touch(left, MotionEvent.ACTION_UP, new int[]{7}, 1, 1);
                    check(controls.command(40, (char) 0).equals("f,40"), "Turn release preserves throttle");
                    touch(forward, MotionEvent.ACTION_MOVE, new int[]{3}, -1000, -1000);
                    check(controls.command(40, (char) 0).equals("s,40"), "Moving outside button releases throttle");
                    touch(forward, MotionEvent.ACTION_UP, new int[]{3}, 1, 1);
                    int[] before = new int[2], after = new int[2];
                    forward.getLocationInWindow(before);
                    ((TextView) field(target, "status")).setText("连接状态变化：" + "很长的网络状态提示很长的网络状态提示很长的网络状态提示");
                    ((TextView) field(target, "mode")).setText("很长的录制状态很长的录制状态很长的录制状态很长的录制状态");
                    layout(root, width, height);
                    forward.getLocationInWindow(after);
                    check(before[0] == after[0] && before[1] == after[1], "Status text does not move held buttons");
                    check(forward.getWidth() > 0 && forward.getHeight() > 0, "Controls retain valid bounds");
                    fake.cancel();
                } catch (Throwable failure) { error.set(failure); }
            });
            if (error.get() != null) throw new AssertionError(error.get());
            result.putString("stream", "PASS: actual Android multi-touch, move-out release, stable button layout; motor transport mocked\n");
            finish(-1, result);
        } catch (Throwable failure) {
            result.putString("stream", "FAIL: " + failure + "\n");
            finish(0, result);
        }
    }
    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private static void setField(Object object, String name, Object value) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value);
    }
    private static void layout(View root, int width, int height) {
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
    }
    private static Button button(View view, String label) {
        if (view instanceof Button && ((Button) view).getText().toString().equals(label)) return (Button) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            Button found = button(((ViewGroup) view).getChildAt(i), label); if (found != null) return found;
        }
        return null;
    }
    private static void touch(View view, int action, int[] ids, float x, float y) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[ids.length];
        MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[ids.length];
        for (int i = 0; i < ids.length; i++) {
            properties[i] = new MotionEvent.PointerProperties(); properties[i].id = ids[i]; properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coordinates[i] = new MotionEvent.PointerCoords(); coordinates[i].x = x; coordinates[i].y = y; coordinates[i].pressure = 1; coordinates[i].size = 1;
        }
        long now = android.os.SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, ids.length, properties, coordinates, 0, 0, 1, 1, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0);
        try { view.dispatchTouchEvent(event); } finally { event.recycle(); }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
