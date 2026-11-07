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
        root.setOrientation(LinearLayout.VERTICAL);
        root.setMotionEventSplittingEnabled(true);
        root.setBackgroundColor(0xff0e1522);
        root.setPadding(dp(20), dp(10), dp(20), dp(10));
        LinearLayout header = new LinearLayout(this);
        TextView title = label("小车遥控", 20);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        status = label("请连接 Control-Car 热点", 14);
        header.addView(status);
        root.addView(header);

        LinearLayout controls = new LinearLayout(this);
        controls.setMotionEventSplittingEnabled(true);
        root.addView(controls, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout throttle = panel(controls, "行走", 1.2f);
        LinearLayout center = panel(controls, "油门上限", 1f);
        LinearLayout steering = panel(controls, "转向", 1.2f);

        LinearLayout arrows = new LinearLayout(this);
        arrows.setMotionEventSplittingEnabled(true);
        steering.addView(arrows, new LinearLayout.LayoutParams(-1, 0, 1));
        hold(arrows, "◀ 左转", 'l');
        hold(arrows, "右转 ▶", 'r');

        SeekBar slider = new SeekBar(this);
        slider.setMax(100);
        slider.setProgress(speed);
        center.addView(slider);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int value, boolean user) { speed = value; update(); }
            public void onStartTrackingTouch(SeekBar bar) {}
            public void onStopTrackingTouch(SeekBar bar) {}
        });
        LinearLayout tricks = new LinearLayout(this);
        center.addView(tricks, new LinearLayout.LayoutParams(-1, 0, 1));
        button(tricks, "左绕弯", () -> arc('q'));
        button(tricks, "右绕弯", () -> arc('e'));
        hold(center, "按住刹车", 'x');
        button(center, "停止", this::stop);

        hold(throttle, "▲ 前进", 'f');
        hold(throttle, "▼ 倒车", 'b');
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView label(String title, int size) {
        TextView label = new TextView(this);
        label.setText(title);
        label.setTextSize(size);
        label.setTextColor(0xffdce8f8);
        return label;
    }

    private LinearLayout panel(LinearLayout parent, String title, float weight) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setMotionEventSplittingEnabled(true);
        panel.setPadding(dp(6), dp(8), dp(6), 0);
        parent.addView(panel, new LinearLayout.LayoutParams(0, -1, weight));
        panel.addView(label(title, 14));
        return panel;
    }

    private android.graphics.drawable.GradientDrawable surface(int color) {
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(14));
        return shape;
    }

    private Button button(LinearLayout row, String title, Runnable action) {
        Button button = new Button(this);
        button.setText(title);
        button.setTextSize(16);
        button.setTextColor(0xfff4f8ff);
        button.setMinimumHeight(0);
        button.setMinHeight(0);
        button.setPadding(dp(4), 0, dp(4), 0);
        android.graphics.drawable.StateListDrawable background = new android.graphics.drawable.StateListDrawable();
        background.addState(new int[]{android.R.attr.state_pressed}, surface(0xff3977b8));
        background.addState(new int[]{}, surface("停止".equals(title) ? 0xffa33243 : 0xff233753));
        button.setBackground(background);
        boolean vertical = row.getOrientation() == LinearLayout.VERTICAL;
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(vertical ? -1 : 0, vertical ? 0 : -1, 1);
        params.setMargins(dp(3), dp(3), dp(3), dp(3));
        row.addView(button, params);
        if (action != null) button.setOnClickListener(v -> action.run());
        return button;
    }

    private void hold(LinearLayout row, String title, char direction) {
        button(row, title, null).setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.setPressed(true);
                    stunt = 0;
                    held.put(view, direction);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.setPressed(false);
                    held.remove(view);
                    break;
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
        for (View view : held.keySet()) view.setPressed(false);
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

