package local.car;

import android.app.Activity;
import android.os.*;
import android.net.ConnectivityManager;
import android.view.*;
import android.widget.*;
import java.util.HashMap;
import java.util.concurrent.*;

public final class RemoteActivity extends Activity {
    private final ScheduledExecutorService link = Executors.newSingleThreadScheduledExecutor();
    private final HashMap<View, Character> held = new HashMap<>();
    private volatile String command = "s,0";
    private volatile boolean active;
    private TextView status;
    private int speed = 40;
    private char stunt;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable endStunt = () -> { stunt = 0; update(); };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(1);
        root.setPadding(24, 12, 24, 12);
        status = new TextView(this);
        status.setText("请连接 Control-Car 热点");
        root.addView(status);
        SeekBar slider = new SeekBar(this);
        slider.setMax(100);
        slider.setProgress(speed);
        root.addView(slider);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int value, boolean user) { speed = value; update(); }
            public void onStartTrackingTouch(SeekBar bar) {}
            public void onStopTrackingTouch(SeekBar bar) {}
        });
        LinearLayout directions = row(root);
        hold(directions, "左转", 'l'); hold(directions, "前进", 'f');
        hold(directions, "后退", 'b'); hold(directions, "右转", 'r');
        LinearLayout actions = row(root);
        button(actions, "左绕弯", () -> arc('q'));
        button(actions, "停止", this::stop);
        hold(actions, "按住刹车", 'x');
        button(actions, "右绕弯", () -> arc('e'));
        setContentView(root);
        ConnectivityManager manager = getSystemService(ConnectivityManager.class);
        link.scheduleWithFixedDelay(() -> {
            if (!active) return;
            boolean ok = CarLink.send(manager, command);
            runOnUiThread(() -> {
                if (!active) return;
                if (!ok) stop();
                status.setText((ok ? "小车已连接" : "未连接小车，请检查 Wi-Fi") + "  ·  油门 " + speed + "%");
            });
        }, 0, 150, TimeUnit.MILLISECONDS);
    }

    private LinearLayout row(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        row.setMotionEventSplittingEnabled(true);
        parent.addView(row, new LinearLayout.LayoutParams(-1, 0, 1));
        return row;
    }
    private Button button(LinearLayout row, String title, Runnable action) {
        Button button = new Button(this);
        button.setText(title);
        row.addView(button, new LinearLayout.LayoutParams(0, -1, 1));
        if (action != null) button.setOnClickListener(v -> action.run());
        return button;
    }
    private void hold(LinearLayout row, String title, char direction) {
        button(row, title, null).setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: stunt = 0; held.put(view, direction); break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: held.remove(view); break;
            }
            update();
            return true;
        });
    }
    private void arc(char direction) {
        stop();
        stunt = direction;
        update();
        ui.postDelayed(endStunt, 500);
    }
    private void update() {
        boolean f = held.containsValue('f'), b = held.containsValue('b');
        boolean l = held.containsValue('l'), r = held.containsValue('r');
        char move = held.containsValue('x') ? 'x' :
            stunt != 0 ? stunt :
            f != b ? (f ? (l == r ? 'f' : l ? 'q' : 'e') : (l == r ? 'b' : l ? 'z' : 'c')) :
            l != r ? (l ? 'l' : 'r') : 's';
        command = move + "," + (stunt != 0 ? Math.min(speed, 40) : speed);
    }
    private void stop() {
        held.clear();
        ui.removeCallbacks(endStunt);
        stunt = 0;
        update();
    }
    @Override protected void onResume() { super.onResume(); active = true; }
    @Override protected void onPause() {
        active = false;
        stop();
        link.execute(() -> CarLink.send(getSystemService(ConnectivityManager.class), "s,0"));
        super.onPause();
    }
    @Override protected void onDestroy() { link.shutdown(); super.onDestroy(); }
}
