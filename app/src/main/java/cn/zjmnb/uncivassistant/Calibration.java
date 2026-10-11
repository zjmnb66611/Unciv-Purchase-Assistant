package cn.zjmnb.uncivassistant;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;

/** 保存截图原始像素坐标。分辨率、方向或游戏字体变化后要求重新校准。 */
final class Calibration {
    static final String[] KEYS = {"queue", "city", "buy", "dialog", "confirm"};
    static final String[] PROMPTS = {
        "框选展开后的队列文字列：包含当前项目和等待项目，避开左侧图标和右侧箭头。先把队列展开并滚到顶部。",
        "框选底部城市名及人口的文字区域，用于检测是否仍在同一城市。",
        "先手动选中一个可用金币购买的队列项目，再框选完整的金币购买按钮（包含黄色金币图标）。",
        "先手动打开金币购买确认窗口，再框选“你确定用…购买…吗？”整句话（包含黄色金币图标）。",
        "仍在购买确认窗口，框选底部右侧的“购买”确认按钮。框选不会执行购买，完成后请手动取消窗口。"
    };
    private final SharedPreferences prefs;
    Calibration(Context context) { prefs = context.getSharedPreferences("assistant", Context.MODE_PRIVATE); }
    void set(String key, Rect r, int w, int h) {
        prefs.edit().putString(key, r.left + "," + r.top + "," + r.right + "," + r.bottom)
                .putInt("width", w).putInt("height", h).apply();
    }
    Rect get(String key) {
        String value = prefs.getString(key, "");
        if (value.isEmpty()) return null;
        String[] parts = value.split(",");
        try { return new Rect(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3])); }
        catch (RuntimeException e) { return null; }
    }
    boolean ready() {
        int width = prefs.getInt("width", 0), height = prefs.getInt("height", 0);
        for (String key : KEYS) {
            Rect r = get(key);
            if (r == null || r.isEmpty() || r.left < 0 || r.top < 0 || r.right > width || r.bottom > height) return false;
        }
        return true;
    }
    void clearAreas() {
        SharedPreferences.Editor edit = prefs.edit();
        for (String key : KEYS) edit.remove(key);
        edit.apply();
    }
    boolean matches(int w, int h) { return w == prefs.getInt("width", 0) && h == prefs.getInt("height", 0); }
    long budget() { return prefs.getLong("budget", 100000); }
    void budget(long value) { prefs.edit().putLong("budget", Math.max(0, value)).apply(); }
    void report(String text) { prefs.edit().putString("report", text).apply(); }
    String report() { return prefs.getString("report", "尚未执行购买"); }
}
