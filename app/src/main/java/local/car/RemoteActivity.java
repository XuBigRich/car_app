package local.car;

import android.app.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import android.content.SharedPreferences;
import java.util.*;
import java.util.concurrent.*;

// 遥控器主页面：组装界面、收集触摸操作、显示状态，并把动作提交给 MotionEngine。
// 职责划分：本类负责交互，CarWifi 负责选网络，CarLink 负责 HTTP，SkillStore 负责技能存储。
public final class RemoteActivity extends Activity {
    // 唯一的动作工作线程；串行发送可以避免网络请求乱序，定时任务结束后再间隔10毫秒推进。
    private final ScheduledExecutorService link = Executors.newSingleThreadScheduledExecutor();
    // 保存每个仍按住的按钮及方向，支持一个手指按前进、另一个手指按转向。
    private final ManualControls<View> held = new ManualControls<>();
    // 主线程调度器，用于界面刷新、短绕弯结束和从后台切回界面。
    private final Handler ui = new Handler(Looper.getMainLooper());
    // 动作引擎管理录制、返回和技能执行，界面不直接推进自动动作的帧序号。
    private MotionEngine engine;
    // 负责自动连接指定的小车热点，并向发送线程提供小车网络。
    private CarWifi wifi;
    // 已保存的技能集合，App 重启时从本地恢复。
    private SkillStore skills;
    // 本地配置与技能的存储入口；返程前进/倒车校准比例也放在这里。
    private SharedPreferences prefs;
    // status 显示网络与油门；mode 显示录制、等待、执行等当前流程。
    private TextView status, mode;
    // 根据动作引擎状态切换文字的两个入口按钮。
    private Button recordButton, skillButton;
    // 页面处于前台时为 true；防止后台返回的事件再次弹窗或刷新页面。
    private boolean active;
    // 当前手动油门上限，范围0～100，表示 PWM 占空比，不是测得的车速。
    private int speed = 40;
    // 非0时表示短绕弯指令 q/e；结束后清零，恢复按键控制。
    private char stunt;
    // 缓存连接管理器的提示；连接热点成功与小车 HTTP 通信成功是两种不同状态。
    private String wifiMessage = "正在连接小车";
    // 短绕弯的结束任务；被人工接管或切换后台时要移除，避免旧任务改写新操作。
    private final Runnable endStunt = () -> { stunt = 0; update(); };
    // 这里只每150毫秒刷新显示，不负责自动动作计时，避免界面延迟改变回放时长。
    private final Runnable refresh = new Runnable() {
        public void run() {
            if (!active) return;
            render();
            ui.postDelayed(this, 150);
        }
    };

    // 页面创建：恢复技能、初始化连接和引擎，再构建横屏布局与按钮事件。
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        // 遥控页面可见时保持亮屏，避免执行技能时因自动息屏进入后台并中断控制。
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("car", MODE_PRIVATE);
        skills = new SkillStore(prefs);
        // 网络真正丢失时取消所有临时动作，重连成功后也不自动续播。
        wifi = new CarWifi(this, (message, lost) -> {
            wifiMessage = message;
            if (lost) stop();
            render();
        });
        // 注入单调时钟与 HTTP 发送函数；引擎事件通过主线程 Handler 回到页面处理。
        engine = new MotionEngine(SystemClock::elapsedRealtime,
            (path, body) -> CarLink.exchange(wifi.network(), path, body),
            (event, frames) -> {
                // 网络线程发出提示到主线程真正执行之间，用户可能又按了新方向。
                // 过期的失败提示不能清理新按键，必须核对发出事件时的意图版本。
                long revision = engine.revision();
                ui.post(() -> { if (engine.revision() == revision) onEngineEvent(event, frames); });
            });

        // 根布局纵向排列标题、流程提示、遥控区和底部自动功能区。
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        // 允许多指事件分配给不同子控件，支持油门与转向同时按住。
        root.setMotionEventSplittingEnabled(true);
        root.setBackgroundColor(0xff0e1522);
        root.setPadding(dp(12), dp(6), dp(12), dp(6));
        LinearLayout header = new LinearLayout(this);
        header.addView(label("小车遥控", 20), new LinearLayout.LayoutParams(dp(120), -2));
        status = label(wifiMessage, 12);
        // 通信提示的长短不能改变控件位置，否则手指没动，按钮却可能因换行而移动。
        status.setSingleLine(true);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status.setGravity(Gravity.RIGHT);
        header.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(header);
        mode = label("手动控制", 13);
        mode.setSingleLine(true);
        mode.setEllipsize(android.text.TextUtils.TruncateAt.END);
        root.addView(mode);
        // 主操作区用剩余高度，左右为行走/转向，中间为油门、绕弯和刹车。
        LinearLayout controls = new LinearLayout(this);
        controls.setMotionEventSplittingEnabled(true);
        root.addView(controls, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout throttle = panel(controls, "行走", 1.1f);
        LinearLayout center = panel(controls, "油门上限", 1.4f);
        LinearLayout steering = panel(controls, "转向", 1.1f);
        hold(throttle, "▲ 前进", 'f');
        hold(throttle, "▼ 倒车", 'b');
        LinearLayout arrows = new LinearLayout(this);
        arrows.setMotionEventSplittingEnabled(true);
        steering.addView(arrows, new LinearLayout.LayoutParams(-1, 0, 1));
        hold(arrows, "◀ 左转", 'l');
        hold(arrows, "右转 ▶", 'r');
        // 油门滑块只改变行走电机占空比；人为触摸会先取消等待返回或正在执行的任务。
        SeekBar slider = new SeekBar(this);
        slider.setMax(100); slider.setProgress(speed);
        center.addView(slider);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int value, boolean user) {
                // 忽略程序设置进度产生的回调，只有用户拖动才接管控制。
                if (!user) return;
                otherControlPressed(); speed = value; update();
            }
            public void onStartTrackingTouch(SeekBar bar) { otherControlPressed(); update(); }
            public void onStopTrackingTouch(SeekBar bar) {}
        });
        LinearLayout tricks = new LinearLayout(this);
        center.addView(tricks, new LinearLayout.LayoutParams(-1, 0, 1));
        button(tricks, "左绕弯", () -> arc('q'));
        button(tricks, "右绕弯", () -> arc('e'));
        LinearLayout braking = new LinearLayout(this);
        center.addView(braking, new LinearLayout.LayoutParams(-1, 0, 1));
        hold(braking, "按住刹车", 'x');
        // 录制期间的停止也属于可记录动作，不结束录制；其他状态的停止会清除自动流程。
        button(braking, "停止", () -> {
            if (engine.state() == MotionEngine.State.RECORDING) { clearControls(); update(); }
            else stop();
        });
        // 底部入口固定高度，提供轨迹、技能、技能库、返程校准和重新连接功能。
        LinearLayout automation = new LinearLayout(this);
        root.addView(automation, new LinearLayout.LayoutParams(-1, dp(48)));
        recordButton = button(automation, "轨迹录制", () -> recordAction(false));
        skillButton = button(automation, "技能录制", () -> recordAction(true));
        button(automation, "技能库", this::library);
        button(automation, "返程校准", this::calibration);
        // HTTP 暂时无响应时，点击重试不应主动拆掉仍正常连接的小车 Wi-Fi。
        button(automation, "连接小车", () -> { stop(); wifi.reconnect(); });
        setContentView(root);
        // 启动后台串行推进；fixedDelay 表示每次请求结束后再等10毫秒，不会并行堆积网络请求。
        link.scheduleWithFixedDelay(() -> engine.tick(), 0, 10, TimeUnit.MILLISECONDS);
    }
    // 把布局中的 dp 换算成当前屏幕像素，让不同密度手机上的按钮尺寸接近。
    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // 创建统一字号与颜色的文字控件，供标题和状态提示复用。
    private TextView label(String title, int size) {
        TextView label = new TextView(this);
        label.setText(title);
        label.setTextSize(size);
        label.setTextColor(0xffdce8f8);
        return label;
    }

    // 创建纵向分区；宽度0配合 weight，按比例分配父容器的可用宽度。
    private LinearLayout panel(LinearLayout parent, String title, float weight) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setMotionEventSplittingEnabled(true);
        panel.setPadding(dp(6), dp(8), dp(6), 0);
        parent.addView(panel, new LinearLayout.LayoutParams(0, -1, weight));
        panel.addView(label(title, 14));
        return panel;
    }

    // 创建带圆角的纯色背景，供按钮普通/按下状态复用。
    private android.graphics.drawable.GradientDrawable surface(int color) {
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(14));
        return shape;
    }

    // 创建普通按钮；横向容器按宽度均分，纵向容器按高度均分。
    // action=null 的按钮不绑定点击，留给 hold() 接管按下/松开事件。
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
        // 普通按钮在按下时就检查人工接管，而不是等手指抬起产生 click 才取消自动动作。
        if (action != null) {
            button.setOnTouchListener((view, event) -> {
                // 轨迹按钮例外：READY 时点它就是“开始执行”，不能提前把待执行记录清掉。
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN && view != recordButton) otherControlPressed();
                return false;
            });
            button.setOnClickListener(v -> action.run());
        }
        return button;
    }

    // 绑定需要持续按住的控制按钮：按下加入 held，松开或触摸取消时移除。
    private void hold(LinearLayout row, String title, char direction) {
        button(row, title, null).setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            switch (action) {
                // 按下先接管自动流程，再加入本次按键；组合指令由 update() 统一计算。
                case MotionEvent.ACTION_DOWN:
                    otherControlPressed();
                    ui.removeCallbacks(endStunt); stunt = 0;
                    view.setPressed(true);
                    held.press(view, direction, event.getPointerId(event.getActionIndex()));
                    view.getParent().requestDisallowInterceptTouchEvent(true);
                    update();
                    break;
                case MotionEvent.ACTION_MOVE:
                    // 只跟踪拥有此按钮的 pointerId，第二根手指的移动不能把第一根手指的按键松开。
                    int pointer = event.findPointerIndex(held.pointer(view));
                    int slop = ViewConfiguration.get(this).getScaledTouchSlop();
                    boolean inside = pointer >= 0 && event.getX(pointer) >= -slop && event.getY(pointer) >= -slop &&
                        event.getX(pointer) <= view.getWidth() + slop && event.getY(pointer) <= view.getHeight() + slop;
                    if (!inside && held.cancel(view)) { view.setPressed(false); update(); }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP:
                    if (held.release(view, event.getPointerId(event.getActionIndex()))) {
                        view.setPressed(false);
                        update();
                    }
                    break;
                case MotionEvent.ACTION_CANCEL:
                    view.setPressed(false);
                    // 自动动作开始时已清过旧按键；忽略那些旧触摸随后到来的松手事件，避免意外取消新动作。
                    if (held.cancel(view)) update();
                    break;
            }
            return true;
        });
    }
    // 清除按下的视觉状态、按键集合和延迟绕弯；本方法本身不发送停车。
    private void clearControls() {
        for (View view : held.keys()) view.setPressed(false);
        held.clear();
        ui.removeCallbacks(endStunt);
        stunt = 0;
    }
    // 等待返回、自动执行和结束确认期间，其他控制会作废本次任务；录制期间允许正常驾驶。
    private void otherControlPressed() {
        MotionEngine.State state = engine.state();
        if (state == MotionEngine.State.READY || state == MotionEngine.State.PLAYING || state == MotionEngine.State.FINISHING || state == MotionEngine.State.UPLOADING || state == MotionEngine.State.SAVING) stop();
    }
    // 原有的左右短绕弯：以不超过40%的油门持续500毫秒，可在录制中作为普通动作记录。
    private void arc(char direction) {
        otherControlPressed(); clearControls();
        stunt = direction; update();
        ui.postDelayed(endStunt, 500);
    }
    // 把所有按键合成为一条协议指令；相反方向同时按下时相互抵消。
    private void update() {
        // 优先级：刹车 → 短绕弯 → 行走与转向组合 → 单独转向 → 停止。
        // q/e 表示前进左/右转，z/c 表示倒车左/右转；左右相等时不请求转向。
        // 仅提交最新目标，由引擎后台线程发送；手动油门变化也会包含在录制指令中。
        engine.manual(held.command(speed, stunt));
        render();
    }
    // 统一的作废停车入口：清理界面按键与临时任务，设置停车目标，再更新显示。
    private void stop() { clearControls(); engine.cancel(); render(); }
    // 两个录制按钮的共同逻辑；saveSkill=false 表示临时轨迹，true 表示可持久保存的技能。
    private void recordAction(boolean saveSkill) {
        MotionEngine.State state = engine.state();
        if (state == MotionEngine.State.PLAYING || state == MotionEngine.State.FINISHING || state == MotionEngine.State.STARTING || state == MotionEngine.State.UPLOADING || state == MotionEngine.State.SAVING) { stop(); return; }
        // 正在录制时只允许结束当前类型，避免把轨迹误存为技能或混淆两个录制流程。
        if (state == MotionEngine.State.RECORDING) {
            if (engine.isSkill() != saveSkill) { toast("请先结束当前录制"); return; }
            clearControls(); engine.finish();
        // 临时轨迹结束后，此按钮变成开始执行；读取两个方向的校准比例再生成返程。
        } else if (state == MotionEngine.State.READY && !saveSkill) {
            clearControls();
            if (!engine.playReturn(prefs.getInt("return_forward", 100), prefs.getInt("return_reverse", 100))) toast("请先连接小车");
        } else {
            clearControls();
            if (!engine.record(saveSkill)) toast(engine.oldFirmware() ? "请先升级ESP板载录制固件" : "小车通信成功后才能开始录制");
        }
        render();
    }
    // 接收后台引擎的结果；离开页面后不再弹保存框，也不恢复自动动作。
    private void onEngineEvent(String event, List<MotionEngine.Frame> frames) {
        if (!active) return;
        if ("save".equals(event)) saveSkill(frames);
        else if ("empty".equals(event)) toast("没有录到行驶动作，请重新录制");
        else if ("limit".equals(event)) toast("录制达到时长或动作数量上限，已作废并停车");
        else if ("failed".equals(event)) { clearControls(); toast("通信失败，本次动作已取消"); }
        else if ("firmware".equals(event)) toast("当前ESP固件不支持板载录制，请升级固件");
        else if ("disconnected".equals(event)) { clearControls(); toast("小车持续未响应，已停车，请重新按键操作"); }
        else if ("done".equals(event)) toast("执行完成，已停车");
        render();
    }
    // 弹出命名保存框；先确保停止，命名为空、重复或写盘失败时保留窗口供用户修改。
    private void saveSkill(List<MotionEngine.Frame> frames) {
        // 板子已经停止并发回完整记录，命名期间仅保活；确认写盘成功后再清空板端缓存。
        clearControls();
        EditText name = new EditText(this);
        name.setSingleLine(true); name.setHint("技能名称（最多24字）");
        name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(24)});
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("保存技能")
            .setView(name).setNegativeButton("放弃", (d, w) -> stop()).setPositiveButton("保存", null).create();
        // 手动绑定保存按钮，避免系统默认点击后直接关闭弹窗，让输入校验失败时还能继续修改。
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (skills.add(name.getText().toString(), frames)) { engine.confirmSaved(); toast("技能已保存，可在技能库重复使用"); dialog.dismiss(); }
            else toast("保存失败：名称不能为空或重复，最多保存32个技能");
        }));
        dialog.setOnCancelListener(d -> stop());
        dialog.show();
    }
    // 展示已保存技能，选择后可以执行或删除；进入技能库会取消当前临时流程。
    private void library() {
        stop();
        List<SkillStore.Skill> all = skills.all();
        if (all.isEmpty()) { toast("还没有技能，请先录制并保存"); return; }
        String[] names = new String[all.size()];
        for (int i = 0; i < all.size(); i++) {
            SkillStore.Skill skill = all.get(i);
            // 累加帧时长作为列表提示，仅用于显示；真正执行使用每帧保存的时间。
            long us = 0; for (MotionEngine.Frame frame : skill.frames) us += frame.micros;
            names[i] = skill.name + "  ·  " + String.format(java.util.Locale.CHINA, "%.1f秒", us / 1000000.0);
        }
        new AlertDialog.Builder(this).setTitle("技能库（按录制顺序执行）").setItems(names, (dialog, index) -> {
            SkillStore.Skill skill = all.get(index);
            new AlertDialog.Builder(this).setTitle(skill.name)
                .setMessage("按保存的行走、转向和油门执行。操作任意控制按钮可中断。")
                .setPositiveButton("执行", (d, w) -> {
                    stop();
                    // 技能按保存顺序播放，使用原油门；播放使用副本，取消执行不会删除技能。
                    if (!engine.play(skill.frames)) toast(engine.oldFirmware() ? "请先升级ESP固件" : "技能过长或通信未就绪，请检查后重试");
                    render();
                }).setNeutralButton("删除", (d, w) -> new AlertDialog.Builder(this)
                    .setTitle("删除“" + skill.name + "”？").setNegativeButton("取消", null)
                    .setPositiveButton("删除", (confirm, which) -> toast(skills.remove(skill) ? "技能已删除" : "删除失败"))
                    .show()).setNegativeButton("取消", null).show();
        }).setNegativeButton("关闭", null).show();
    }
    // 分别设置返程前进与倒车的时长比例，默认100%；只影响轨迹返回，不修改技能。
    private void calibration() {
        stop();
        LinearLayout fields = new LinearLayout(this); fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(20), 0, dp(20), 0);
        fields.addView(label("返程前进时长百分比（50～200）", 14));
        EditText forward = new EditText(this); forward.setInputType(2);
        forward.setText("" + prefs.getInt("return_forward", 100)); fields.addView(forward);
        fields.addView(label("返程倒车时长百分比（50～200）", 14));
        EditText backward = new EditText(this); backward.setInputType(2);
        backward.setText("" + prefs.getInt("return_reverse", 100)); fields.addView(backward);
        // 弹窗使用系统浅色背景，把复用的文字改成深色，避免浅色字看不清。
        for (int i = 0; i < fields.getChildCount(); i++) if (fields.getChildAt(i) instanceof TextView)
            ((TextView) fields.getChildAt(i)).setTextColor(0xff172333);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("返程校准")
            .setMessage("默认100。返程走短了调大，走过头调小。先用直线校准；转弯、打滑和电量变化仍会产生误差。")
            .setView(fields).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                int f = Integer.parseInt(forward.getText().toString()), b = Integer.parseInt(backward.getText().toString());
                // 限制为50%～200%，避免误输入造成极长返程；保存到本地供以后返回使用。
                if (f < 50 || f > 200 || b < 50 || b > 200) throw new NumberFormatException();
                prefs.edit().putInt("return_forward", f).putInt("return_reverse", b).apply();
                dialog.dismiss();
            } catch (NumberFormatException failure) { toast("请输入50～200的整数"); }
        }));
        dialog.show();
    }
    // 根据引擎状态绘制文字，不改变控制意图；Wi-Fi 连接成功后仍需确认 HTTP 可达。
    private void render() {
        if (status == null || engine == null) return;
        setTextIfChanged(status, (engine.oldFirmware() ? "需要升级ESP固件" : engine.recovering() ? "通信重试中" : engine.connected() ? "小车通信正常" : wifi.network() != null ? "小车未响应，正在确认通信" : wifiMessage) + "  · 油门 " + speed + "%");
        MotionEngine.State state = engine.state();
        boolean recording = state == MotionEngine.State.RECORDING;
        setTextIfChanged(recordButton, state == MotionEngine.State.READY ? "开始执行" :
            recording && !engine.isSkill() ? "结束录制" : state == MotionEngine.State.PLAYING ? "执行中·取消" : "轨迹录制");
        setTextIfChanged(skillButton, recording && engine.isSkill() ? "结束并保存" : "技能录制");
        setTextIfChanged(mode, state == MotionEngine.State.READY ? "轨迹已录制，点击开始执行原路退回；其他操作将作废" :
            recording ? (engine.isSkill() ? "技能录制中：记录行走、转向和油门" : "轨迹录制中：结束后可原路退回") :
            state == MotionEngine.State.STARTING ? "正在开始录制…" :
            state == MotionEngine.State.UPLOADING ? "正在发送完整技能…" :
            state == MotionEngine.State.SAVING ? "技能已录好，请命名保存" :
            state == MotionEngine.State.FINISHING ? "正在确认记录…" :
            state == MotionEngine.State.PLAYING ? "自动执行中，操作其他控制可立即取消" : "手动控制 · 已保存技能 " + skills.all().size());
    }
    // 文字未变化时不触发 setText 和重新排版，避免每150毫秒干扰正在持续触摸的布局。
    private void setTextIfChanged(TextView view, String text) {
        if (!text.contentEquals(view.getText())) view.setText(text);
    }
    // 显示短提示，仅在主线程调用。
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }
    // 把系统权限申请结果交回连接管理器，由它检查是否继续连接。
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == CarWifi.PERMISSION_REQUEST) wifi.permissionResult();
    }
    // 页面回到前台：清掉旧任务、重新确认通信，启动指定热点连接和状态刷新。
    @Override protected void onResume() {
        super.onResume(); active = true;
        engine.enable(true); wifi.start();
        ui.removeCallbacks(refresh); ui.post(refresh);
    }
    // 页面暂停：立即使录制/回放失效，停掉显示刷新，并由后台发送停车。
    @Override protected void onPause() {
        active = false; ui.removeCallbacks(refresh); clearControls(); engine.enable(false);
        // 保留网络请求以兼容系统权限/连接弹窗；弹窗可能令 Activity 暂停。
        // 此时已停用运动心跳，后台仍会尝试发一次停车；连接请求在页面销毁时释放。
        super.onPause();
    }
    // 页面销毁：释放指定网络请求、移除全部界面任务，并关闭后台线程池。
    @Override protected void onDestroy() {
        wifi.stop(); ui.removeCallbacksAndMessages(null); link.shutdown(); super.onDestroy();
    }
}
