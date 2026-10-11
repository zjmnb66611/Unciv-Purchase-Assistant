package cn.zjmnb.uncivassistant;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.AlertDialog;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/** 只通过原版可见界面购买。任何异步动作都受会话编号和前台应用双重约束。 */
public final class PurchaseService extends AccessibilityService {
    static PurchaseService instance;
    private static final String GAME = "com.unciv.app";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windows;
    private Calibration calibration;
    private TextRecognizer recognizer;
    private LinearLayout toolbar;
    private TextView status;
    private Button calibrateButton;
    private WindowManager.LayoutParams toolbarParams;
    private View calibrationView;
    private AlertDialog review;
    private boolean wanted, busy, calibrating, capturing;
    private int epoch, calibrationStep;
    private long lastScreenshot, committed;
    private int purchased;
    private String cityIdentity = "";
    private Rect queueViewport;
    private final List<String> log = new ArrayList<>();

    private static final class SeenLine {
        final String text;
        final Rect bounds;
        SeenLine(String text, Rect bounds) { this.text = text; this.bounds = bounds; }
    }
    private static final class Frame {
        final Bitmap bitmap;
        final List<SeenLine> lines = new ArrayList<>();
        Frame(Bitmap bitmap, Text text) {
            this.bitmap = bitmap;
            for (Text.TextBlock block : text.getTextBlocks()) for (Text.Line line : block.getLines()) {
                Rect bounds = line.getBoundingBox();
                if (bounds != null) lines.add(new SeenLine(line.getText(), bounds));
            }
            lines.sort(Comparator.comparingInt((SeenLine l) -> l.bounds.top).thenComparingInt(l -> l.bounds.left));
        }
        List<SeenLine> inside(Rect region) {
            ArrayList<SeenLine> found = new ArrayList<>();
            for (SeenLine line : lines) if (region.contains(line.bounds.centerX(), line.bounds.centerY())) found.add(line);
            return found;
        }
        String text(Rect region) {
            StringBuilder result = new StringBuilder();
            for (SeenLine line : inside(region)) result.append(line.text).append('\n');
            return result.toString();
        }
        boolean gold(Rect region) {
            int count = 0;
            Rect r = new Rect(region);
            if (!r.intersect(0, 0, bitmap.getWidth(), bitmap.getHeight())) return false;
            for (int y = r.top; y < r.bottom; y += 2) for (int x = r.left; x < r.right; x += 2) {
                int c = bitmap.getPixel(x, y);
                int red = Color.red(c), green = Color.green(c), blue = Color.blue(c);
                if (red > 150 && green > 100 && blue < 125 && red > blue * 1.4 && green > blue * 1.25) count++;
            }
            return count >= 4;
        }
    }

    @Override protected void onServiceConnected() {
        instance = this;
        windows = getSystemService(WindowManager.class);
        calibration = new Calibration(this);
        recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        // 只启用无障碍不会自行开始游戏或购买，工具条须由用户主动打开。
    }
    private boolean gameForeground() {
        AccessibilityWindowInfo topApp = null;
        for (AccessibilityWindowInfo window : getWindows()) {
            if (window.getType() == AccessibilityWindowInfo.TYPE_SYSTEM && window.isFocused()) return false;
            if (window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION && (topApp == null || window.getLayer() > topApp.getLayer())) topApp = window;
        }
        if (topApp != null) {
            AccessibilityNodeInfo root = topApp.getRoot();
            return root != null && GAME.contentEquals(root.getPackageName() == null ? "" : root.getPackageName());
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        return root != null && GAME.contentEquals(root.getPackageName() == null ? "" : root.getPackageName());
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (toolbar == null || calibrating) return;
        boolean game = gameForeground();
        if (!game && busy) stop("已离开原版Unciv，操作中止。若刚确认过购买，请检查游戏队列后再开始。");
        toolbar.setVisibility(wanted && game && !capturing ? View.VISIBLE : View.GONE);
    }
    @Override public void onInterrupt() { stop("系统中断无障碍，操作停止。"); }
    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        if (busy || calibrating) hideAll();
    }
    @Override public void onDestroy() {
        hideAll();
        if (recognizer != null) recognizer.close();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    void showToolbar() {
        wanted = true;
        if (toolbar != null) { toolbar.setVisibility(View.VISIBLE); return; }
        toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.VERTICAL);
        toolbar.setPadding(dp(6), dp(4), dp(6), dp(4));
        toolbar.setBackgroundColor(0xF0183049);
        status = new TextView(this); status.setTextColor(Color.WHITE); status.setTextSize(12);
        status.setText("购买助手 · 拖动此行移动"); toolbar.addView(status);
        LinearLayout row = new LinearLayout(this); toolbar.addView(row);
        calibrateButton = toolButton(row, "校准1/5", this::calibrate);
        toolButton(row, "预检", () -> prepare(false));
        toolButton(row, "购买", () -> prepare(true));
        toolButton(row, "停止", () -> stop("你已停止；已完成的原版购买不会撤销。"));
        toolButton(row, "隐藏", this::hideAll);
        toolbarParams = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT);
        toolbarParams.gravity = Gravity.TOP | Gravity.LEFT;
        toolbarParams.x = dp(250); toolbarParams.y = dp(10);
        status.setOnTouchListener(new View.OnTouchListener() {
            float startX, startY; int x, y;
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) { startX=e.getRawX(); startY=e.getRawY(); x=toolbarParams.x; y=toolbarParams.y; return true; }
                if (e.getAction() == MotionEvent.ACTION_MOVE) {
                    toolbarParams.x=Math.max(0, x+(int)(e.getRawX()-startX)); toolbarParams.y=Math.max(0, y+(int)(e.getRawY()-startY));
                    windows.updateViewLayout(toolbar, toolbarParams); return true;
                }
                return true;
            }
        });
        windows.addView(toolbar, toolbarParams);
        Toast.makeText(this, "进入原版Unciv的城市界面后使用工具条", Toast.LENGTH_LONG).show();
    }
    private Button toolButton(LinearLayout row, String text, Runnable action) {
        Button button = new Button(this); button.setText(text); button.setTextSize(12); button.setMinWidth(0);
        button.setPadding(dp(8), 0, dp(8), 0); button.setOnClickListener(v -> action.run());
        row.addView(button, new LinearLayout.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, dp(44)));
        return button;
    }
    private int dp(int value) { return (int)(value * getResources().getDisplayMetrics().density + .5f); }
    void hideAll() {
        stop("工具条已隐藏，助手没有继续购买。"); wanted = false;
        if (toolbar != null) { windows.removeView(toolbar); toolbar = null; }
        removeCalibration();
    }
    private void stop(String reason) {
        epoch++; busy = false; capturing = false;
        if (review != null) { review.dismiss(); review = null; }
        if (calibration != null) calibration.report(reason + "\n已通过队列变化验证购买：" + purchased + "项\n确认价格累计：" + committed + "金币\n" + String.join("\n", log));
        if (status != null) status.setText(reason.length() > 30 ? reason.substring(0,30) + "…" : reason);
    }
    private boolean valid(int token) {
        if (epoch != token || !busy) return false;
        if (!gameForeground()) { stop("前台应用改变，已停止。"); return false; }
        return true;
    }
    private void later(int token, long delay, Runnable action) {
        handler.postDelayed(() -> { if (valid(token)) action.run(); }, delay);
    }

    private void rawScreenshot(int token, Consumer<Bitmap> callback) {
        if (!valid(token)) return;
        long wait = Math.max(100, 500 - (SystemClock.uptimeMillis() - lastScreenshot));
        capturing = true;
        if (toolbar != null) toolbar.setVisibility(View.INVISIBLE);
        later(token, wait, () -> {
            lastScreenshot = SystemClock.uptimeMillis();
            takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                @Override public void onSuccess(ScreenshotResult result) {
                    HardwareBuffer buffer = result.getHardwareBuffer();
                    Bitmap hardware = null, bitmap = null;
                    try {
                        hardware = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                        if (hardware != null) bitmap = hardware.copy(Bitmap.Config.ARGB_8888, false);
                    } catch (RuntimeException ignored) {
                        // 返回空图像并停止，不在截图失败后继续任何点击。
                    } finally { if (hardware != null) hardware.recycle(); buffer.close(); }
                    if (epoch == token) {
                        capturing = false;
                        if (toolbar != null && wanted && gameForeground()) toolbar.setVisibility(View.VISIBLE);
                    }
                    if (!valid(token)) { if (bitmap != null) bitmap.recycle(); return; }
                    if (bitmap == null) { stop("系统未提供可读截图，停止。"); return; }
                    callback.accept(bitmap);
                }
                @Override public void onFailure(int code) {
                    if (epoch == token) {
                        capturing = false;
                        if (toolbar != null && wanted) toolbar.setVisibility(View.VISIBLE);
                        stop("截图失败（系统代码" + code + "），未继续点击。");
                    }
                }
            });
        });
    }
    private void screenshot(int token, Consumer<Frame> callback) {
        rawScreenshot(token, bitmap -> {
            if (!calibration.matches(bitmap.getWidth(), bitmap.getHeight())) {
                bitmap.recycle(); stop("屏幕方向或分辨率与校准不同，请重新校准5个区域。"); return;
            }
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener(text -> {
                        try { if (valid(token)) callback.accept(new Frame(bitmap, text)); }
                        catch (RuntimeException e) { stop("处理识别画面失败：" + e.getClass().getSimpleName()); }
                        finally { bitmap.recycle(); }
                    })
                    .addOnFailureListener(e -> { bitmap.recycle(); if (epoch == token) stop("本机文字识别失败，已停止。"); });
        });
    }
    private void gesture(int token, float x1, float y1, float x2, float y2, long duration, Runnable after) {
        if (!valid(token)) return;
        Path path = new Path(); path.moveTo(x1,y1); if (x1 != x2 || y1 != y2) path.lineTo(x2,y2);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(path, 0, duration)).build();
        boolean accepted = dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription description) { later(token, 650, after); }
            @Override public void onCancelled(GestureDescription description) {
                if (epoch == token) stop("触摸被中断；没有重试购买，请先检查队列。");
            }
        }, handler);
        if (!accepted) stop("系统没有接受点击，已停止。");
    }
    private void tap(int token, Rect rect, Runnable after) { gesture(token, rect.centerX(), rect.centerY(), rect.centerX(), rect.centerY(), 70, after); }
    private void scrollQueue(int token, boolean down, Runnable after) {
        Rect r = queueViewport == null ? calibration.get("queue") : queueViewport;
        float top = r.top + r.height() * .25f, bottom = r.top + r.height() * .70f;
        gesture(token, r.centerX(), down ? bottom : top, r.centerX(), down ? top : bottom, 420, after);
    }
    private boolean checkCity(Frame frame) {
        String city = PurchasePolicy.normalized(frame.text(calibration.get("city")));
        if (city.isEmpty() || !city.equals(cityIdentity)) { stop("城市标识改变或识别不清，请重新预检。"); return false; }
        return true;
    }
    private boolean purchaseDialog(Frame frame) {
        String text = PurchasePolicy.normalized(frame.text(calibration.get("dialog")));
        return text.contains("你确定用") || text.contains("wouldyouliketopurchase");
    }
    private List<SeenLine> queueLines(Frame frame) {
        ArrayList<SeenLine> found = new ArrayList<>();
        for (SeenLine line : frame.inside(queueRegion(frame))) if (PurchasePolicy.isQueueName(line.text)) found.add(line);
        return found;
    }
    /** 队列变短时购买栏、可建目录会上移，不能继续扫描到目录里的项目。 */
    private Rect queueRegion(Frame frame) {
        Rect region = new Rect(calibration.get("queue"));
        for (SeenLine line : frame.lines) {
            if (line.bounds.left >= region.right || line.bounds.right <= region.left || line.bounds.top <= region.top) continue;
            if (PurchasePolicy.queueFooter(line.text)) region.bottom = Math.min(region.bottom, line.bounds.top - dp(6));
        }
        region.bottom = Math.max(region.top + dp(20), region.bottom);
        queueViewport = region;
        return region;
    }
    /** 原版购买按钮的Y坐标会随队列高度变化，X坐标仍由用户指定的金币栏约束。 */
    private Rect buyRegion(Frame frame) {
        Rect sample = calibration.get("buy");
        for (SeenLine line : frame.lines) {
            if (!PurchasePolicy.hasBuyWord(line.text) || line.bounds.left >= sample.right || line.bounds.right <= sample.left) continue;
            int top = Math.max(0, line.bounds.centerY() - sample.height()/2);
            Rect r = new Rect(sample.left, top, sample.right, Math.min(frame.bitmap.getHeight(), top + sample.height()));
            if (frame.gold(r) && PurchasePolicy.buttonPrice(frame.text(r)) >= 0) return r;
        }
        return null;
    }
    private List<String> names(Frame frame) {
        ArrayList<String> found = new ArrayList<>();
        for (SeenLine line : queueLines(frame)) found.add(line.text);
        return found;
    }
    private String pageKey(Frame frame) {
        StringBuilder key = new StringBuilder();
        for (SeenLine line : frame.inside(queueRegion(frame))) key.append(PurchasePolicy.normalized(line.text)).append('@').append(Math.round(line.bounds.top/12f)).append(';');
        return key.toString();
    }
    private void moveTop(int token, int attempt, Runnable callback) {
        screenshot(token, frame -> {
            if (!checkCity(frame)) return;
            if (purchaseDialog(frame)) { stop("请先手动取消游戏购买弹窗，再开始。"); return; }
            String text = PurchasePolicy.normalized(frame.text(queueRegion(frame)));
            if (text.contains("当前项目") || text.contains("currentconstruction")) { callback.run(); return; }
            if (attempt >= 9) { stop("未找到队列顶部“当前项目”，请重新框选队列区域并展开队列。"); return; }
            scrollQueue(token, false, () -> moveTop(token, attempt+1, callback));
        });
    }
    private void scan(int token, Consumer<List<String>> callback) {
        moveTop(token, 0, () -> scanPage(token, new ArrayList<>(), "", 0, callback));
    }
    private void scanPage(int token, List<String> accumulated, String lastKey, int page, Consumer<List<String>> callback) {
        screenshot(token, frame -> {
            if (!checkCity(frame)) return;
            List<String> visible = names(frame);
            String key = pageKey(frame);
            if (page > 0 && key.equals(lastKey)) { callback.accept(accumulated); return; }
            List<String> merged = PurchasePolicy.mergePages(accumulated, visible);
            if (merged == null || merged.size() > 40) { stop("队列跨页识别不连续或超过40项，请缩小字体后重新预检。"); return; }
            if (page >= 14) { stop("没有确认队列底部，停止；请缩小字体或减少队列后再预检。"); return; }
            scrollQueue(token, true, () -> scanPage(token, merged, key, page+1, callback));
        });
    }

    private void prepare(boolean purchase) {
        if (busy || calibrating) { Toast.makeText(this, "请先停止当前操作", Toast.LENGTH_SHORT).show(); return; }
        if (!gameForeground()) { Toast.makeText(this, "请先进入原版Unciv自己的城市", Toast.LENGTH_SHORT).show(); return; }
        if (!calibration.ready()) { Toast.makeText(this, "请先完成5个区域校准", Toast.LENGTH_LONG).show(); return; }
        busy = true; int token = ++epoch;
        purchased = 0; committed = 0; log.clear(); status.setText("正在读取队列，点击停止可中止");
        screenshot(token, frame -> {
            cityIdentity = PurchasePolicy.normalized(frame.text(calibration.get("city")));
            if (!cityIdentity.matches(".*\\p{L}.*")) { stop("未识别到城市名，请重新校准城市名区域。"); return; }
            scan(token, plan -> {
                if (plan.isEmpty()) { stop("没有识别到可购买的队列项目。"); return; }
                showReview(token, plan, purchase);
            });
        });
    }
    private void showReview(int token, List<String> plan, boolean purchase) {
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(16), dp(8), dp(16), dp(8));
        TextView text = new TextView(this); text.setTextColor(Color.BLACK); text.setTextSize(16);
        StringBuilder description = new StringBuilder("识别到" + plan.size() + "项，预算" + calibration.budget() + "金币：\n");
        for (int i=0;i<plan.size();i++) description.append(i+1).append(". ").append(plan.get(i)).append('\n');
        description.append("\n请核对全部项目和重复数量。只购买这份清单中的项目，原版价格与限制照常生效。识别错漏请取消并重新校准。跨页清单经过OCR拼接，必须由你核对。");
        text.setText(description); ScrollView scroll = new ScrollView(this); scroll.addView(text);
        content.addView(scroll, new LinearLayout.LayoutParams(dp(430), dp(220)));
        CheckBox agree = new CheckBox(this); agree.setText("已核对，清单与完整生产队列一致"); content.addView(agree);
        review = new AlertDialog.Builder(this).setTitle(purchase ? "确认批量购买" : "预检：不会执行购买")
                .setView(content).setNegativeButton("取消", (d,w) -> stop("已取消预检，没有继续购买。"))
                .setPositiveButton(purchase ? "按原价购买" : "关闭预检", null).create();
        review.getWindow().setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY);
        review.setOnCancelListener(d -> stop("已取消，没有继续购买。"));
        review.show();
        review.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!purchase) { stop("预检完成；实际购买前请点击工具条“购买”并再次核对。"); return; }
            if (!agree.isChecked()) { Toast.makeText(this, "请先核对清单并勾选确认", Toast.LENGTH_SHORT).show(); return; }
            review.dismiss(); review = null;
            later(token, 300, () -> process(token, plan, new ArrayList<>(plan), 0));
        });
    }
    private void process(int token, List<String> plan, List<String> remaining, int index) {
        if (!valid(token)) return;
        if (index >= plan.size()) { stop("批量处理完成。未能购买的项目保留在原队列。"); return; }
        String target = plan.get(index); status.setText("处理 " + (index+1) + "/" + plan.size() + "：" + target);
        moveTop(token, 0, () -> find(token, target, "", 0, row -> {
            tap(token, row, () -> screenshot(token, frame -> {
                if (!checkCity(frame)) return;
                Rect buy = buyRegion(frame);
                String buyText = buy == null ? "" : frame.text(buy);
                long price = PurchasePolicy.buttonPrice(buyText);
                if (buy == null || !PurchasePolicy.hasBuyWord(buyText) || price < 0 || !frame.gold(buy)) {
                    log.add(target + "：没有明确的金币购买按钮，跳过");
                    process(token, plan, remaining, index+1); return;
                }
                if (!PurchasePolicy.budgetAllows(committed, price, calibration.budget())) {
                    stop("下一项目超过本次预算上限，已停止。"); return;
                }
                String background = PurchasePolicy.normalized(frame.text(calibration.get("dialog")));
                tap(token, buy, () -> awaitDialog(token, target, price, background, 0, () -> {
                    // 不重试确认点击。即使回执不明确，也必须停止、让用户检查游戏状态。
                    committed += price;
                    tap(token, calibration.get("confirm"), () -> screenshot(token, after -> {
                        if (!checkCity(after)) return;
                        if (purchaseDialog(after)) { stop("确认后购买弹窗仍存在，没有重复点击，请检查游戏。"); return; }
                        ArrayList<String> expected = new ArrayList<>(remaining);
                        int removal = -1;
                        for (int i=0;i<expected.size();i++) if (PurchasePolicy.normalized(expected.get(i)).equals(PurchasePolicy.normalized(target))) { removal=i; break; }
                        if (removal < 0) { stop("目标已不在初始队列，停止。"); return; }
                        expected.remove(removal);
                        scan(token, actual -> {
                            if (PurchasePolicy.sameQueue(expected, actual)) {
                                purchased++; log.add(target + "：已购买，确认价格" + price);
                                process(token, plan, expected, index+1);
                            } else if (expected.isEmpty() && !PurchasePolicy.sameQueue(remaining, actual)) {
                                purchased++; log.add(target + "：原队列已处理，未购买游戏自动补入的新项目");
                                stop("初始队列处理完成，游戏自动补入的项目不继续购买。");
                            } else {
                                stop("确认后队列变化与预期不符，已停止且未重试。请查看游戏，不能据此确定本项是否成功。");
                            }
                        });
                    }));
                }, () -> {
                    log.add(target + "：原版按钮未打开确认窗口，可能受金币、资源或放置限制，跳过");
                    process(token, plan, remaining, index+1);
                }));
            }));
        }));
    }
    private void find(int token, String target, String lastKey, int page, Consumer<Rect> callback) {
        screenshot(token, frame -> {
            if (!checkCity(frame)) return;
            if (purchaseDialog(frame)) { stop("出现未处理购买窗口，停止。"); return; }
            for (SeenLine line : queueLines(frame)) if (PurchasePolicy.normalized(line.text).equals(PurchasePolicy.normalized(target))) { callback.accept(new Rect(line.bounds)); return; }
            String key = pageKey(frame);
            if (page >= 14 || (page > 0 && key.equals(lastKey))) { stop("未找到初始队列项目“" + target + "”，停止。"); return; }
            scrollQueue(token, true, () -> find(token, target, key, page+1, callback));
        });
    }
    private void awaitDialog(int token, String target, long price, String background, int attempt, Runnable yes, Runnable blocked) {
        screenshot(token, frame -> {
            String dialog = frame.text(calibration.get("dialog"));
            if (PurchasePolicy.mayConfirm(dialog, target, price, frame.gold(calibration.get("dialog")))) {
                String confirm = PurchasePolicy.normalized(frame.text(calibration.get("confirm")));
                if (!confirm.equals("购买") && !confirm.equals("purchase")) { stop("无法唯一识别购买确认按钮，停止。"); return; }
                yes.run(); return;
            }
            if (purchaseDialog(frame) || !PurchasePolicy.normalized(dialog).equals(background)) { stop("购买弹窗的项目、金额或币种不匹配，停止。"); return; }
            if (!checkCity(frame)) return;
            if (attempt >= 2) { blocked.run(); return; }
            later(token, 500, () -> awaitDialog(token, target, price, background, attempt+1, yes, blocked));
        });
    }

    private void calibrate() {
        if (busy || calibrating) { Toast.makeText(this, "先停止当前任务", Toast.LENGTH_SHORT).show(); return; }
        if (!gameForeground()) { Toast.makeText(this, "请进入原版Unciv城市界面", Toast.LENGTH_SHORT).show(); return; }
        AlertDialog prompt = new AlertDialog.Builder(this).setTitle("校准 " + (calibrationStep+1) + "/5")
                .setMessage(Calibration.PROMPTS[calibrationStep] + "\n\n截图后拖动框选，再点击“保存区域”。本步骤只记录位置，不点击游戏。")
                .setNegativeButton("先去调整游戏", null)
                .setPositiveButton("截图并框选", (d,w) -> {
                    busy = true; int token=++epoch;
                    rawScreenshot(token, bitmap -> {
                        busy=false; calibrating=true;
                        if (toolbar != null) toolbar.setVisibility(View.INVISIBLE);
                        BoxView view = new BoxView(bitmap, r -> {
                            if (calibrationStep == 0) calibration.clearAreas();
                            calibration.set(Calibration.KEYS[calibrationStep], r, bitmap.getWidth(), bitmap.getHeight());
                            removeCalibration();
                            calibrationStep=(calibrationStep+1)%Calibration.KEYS.length;
                            calibrateButton.setText("校准"+(calibrationStep+1)+"/5");
                            status.setText(calibrationStep==0 ? "校准完成，取消游戏弹窗后预检" : "已保存，准备校准"+(calibrationStep+1)+"/5");
                        }, this::removeCalibration);
                        calibrationView=view;
                        WindowManager.LayoutParams p=new WindowManager.LayoutParams(-1,-1,
                                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                                android.graphics.PixelFormat.TRANSLUCENT);
                        p.gravity=Gravity.TOP|Gravity.LEFT;
                        p.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
                        p.setFitInsetsTypes(0);
                        windows.addView(view,p);
                    });
                }).create();
        prompt.getWindow().setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY);
        prompt.show();
    }
    private void removeCalibration() {
        calibrating=false;
        if (calibrationView != null) {
            View view=calibrationView; calibrationView=null; windows.removeView(view);
            if (view instanceof BoxView) ((BoxView)view).bitmap.recycle();
        }
        if (toolbar != null && wanted) toolbar.setVisibility(View.VISIBLE);
    }
    private final class BoxView extends View {
        final Bitmap bitmap;
        private final Consumer<Rect> save;
        private final Runnable cancel;
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Rect chosen=new Rect();
        private float startX,startY;
        BoxView(Bitmap bitmap, Consumer<Rect> save, Runnable cancel) { super(PurchaseService.this); this.bitmap=bitmap; this.save=save; this.cancel=cancel; }
        @Override protected void onDraw(Canvas canvas) {
            canvas.drawBitmap(bitmap,null,new Rect(0,0,getWidth(),getHeight()),paint);
            paint.setColor(0x55000000); canvas.drawRect(0,0,getWidth(),getHeight(),paint);
            paint.setColor(Color.GREEN); paint.setStrokeWidth(dp(2)); paint.setStyle(Paint.Style.STROKE); canvas.drawRect(chosen,paint); paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xEE17385A); canvas.drawRect(getWidth()-dp(400),0,getWidth(),dp(58),paint);
            paint.setColor(Color.WHITE); paint.setTextSize(dp(16));
            canvas.drawText("拖动框选 · "+(calibrationStep+1)+"/5",getWidth()-dp(385),dp(34),paint);
            canvas.drawText("取消",getWidth()-dp(190),dp(34),paint);
            canvas.drawText("保存区域",getWidth()-dp(105),dp(34),paint);
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction()==MotionEvent.ACTION_DOWN) {
                if (e.getY()<dp(58) && e.getX()>getWidth()-dp(400)) {
                    if(e.getX()>getWidth()-dp(115) && chosen.width()>dp(15) && chosen.height()>dp(10)) {
                        Rect raw=new Rect(Math.round(chosen.left*(float)bitmap.getWidth()/getWidth()),Math.round(chosen.top*(float)bitmap.getHeight()/getHeight()),
                                Math.round(chosen.right*(float)bitmap.getWidth()/getWidth()),Math.round(chosen.bottom*(float)bitmap.getHeight()/getHeight()));
                        save.accept(raw);
                    } else if(e.getX()>getWidth()-dp(205)) cancel.run();
                    return true;
                }
                startX=e.getX(); startY=e.getY(); chosen.set((int)startX,(int)startY,(int)startX,(int)startY); invalidate(); return true;
            }
            if(e.getAction()==MotionEvent.ACTION_MOVE || e.getAction()==MotionEvent.ACTION_UP) {
                chosen.set((int)Math.max(0,Math.min(startX,e.getX())),(int)Math.max(0,Math.min(startY,e.getY())),
                        (int)Math.min(getWidth(),Math.max(startX,e.getX())),(int)Math.min(getHeight(),Math.max(startY,e.getY()))); invalidate(); return true;
            }
            return true;
        }
    }
}
