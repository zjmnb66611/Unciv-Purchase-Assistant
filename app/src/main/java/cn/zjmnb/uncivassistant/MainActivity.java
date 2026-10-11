package cn.zjmnb.uncivassistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private TextView status;
    private Calibration calibration;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        calibration = new Calibration(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(30, 35, 30, 30);
        scroll.addView(body); setContentView(scroll);
        TextView title = new TextView(this); title.setText("Unciv 购买助手 · 预览版0.1.0"); title.setTextSize(25); body.addView(title);
        TextView description = new TextView(this);
        description.setText("用于你现有的原版Unciv，不包含游戏。适配Android 11及以上，面向Magic7 / Android16。\n\n用途与权限：你主动启用后，使用无障碍识别前台应用、获取游戏截图、在本机识别文字并模拟购买点击。只操作com.unciv.app。截图不落盘、不上传，应用不申请网络权限。离开游戏或点击停止即中止。\n\n首次使用：进入自己的城市，展开队列并保持横屏；依次校准5个区域，预检并核对识别清单，再点击购买。跨页队列会滚动识别；包含连续重复项目的队列请特别核对数量。清单不完整时取消。\n\n无障碍设置由你手动开启。部分系统安装来源会要求先在应用信息右上角允许受限设置，再启用无障碍。未开启时无法操作。此版本尚需你的真机校准，识别不确定时停止。\n");
        description.setTextSize(16); body.addView(description);
        button(body, "1. 阅读用途并前往无障碍设置", () -> new AlertDialog.Builder(this)
                .setTitle("启用游戏购买辅助")
                .setMessage("助手会读取前台应用和游戏截图，并模拟点击原版购买按钮。仅本机处理，不上传截图。是否前往设置，由你手动启用“Unciv 购买助手”？")
                .setNegativeButton("取消", null)
                .setPositiveButton("前往设置", (d,w) -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))).show());
        button(body, "2. 显示悬浮工具条", () -> {
            if (PurchaseService.instance == null) {
                new AlertDialog.Builder(this).setMessage("请先在系统无障碍设置中启用购买助手。").setPositiveButton("知道了", null).show();
            } else { PurchaseService.instance.showToolbar(); refresh(); }
        });
        TextView budgetLabel = new TextView(this); budgetLabel.setText("每次批量购买的金币预算上限（不是增加金币）："); body.addView(budgetLabel);
        EditText budget = new EditText(this); budget.setInputType(InputType.TYPE_CLASS_NUMBER); budget.setText(Long.toString(calibration.budget())); body.addView(budget);
        button(body, "保存预算", () -> {
            try { calibration.budget(Long.parseLong(budget.getText().toString())); refresh(); }
            catch (NumberFormatException e) { budget.setError("请输入有效的非负整数"); }
        });
        button(body, "停止并隐藏工具条", () -> { if (PurchaseService.instance != null) PurchaseService.instance.hideAll(); refresh(); });
        status = new TextView(this); status.setTextSize(15); body.addView(status); refresh();
    }
    private void button(LinearLayout body, String text, Runnable run) {
        Button b = new Button(this); b.setText(text); b.setOnClickListener(v -> run.run()); body.addView(b);
    }
    @Override protected void onResume() { super.onResume(); if (status != null) refresh(); }
    private void refresh() {
        status.setText("\n无障碍：" + (PurchaseService.instance == null ? "未启用" : "已启用") +
                "\n区域校准：" + (calibration.ready() ? "已记录；字体改变需重做" : "未完成") +
                "\n预算：" + calibration.budget() + "金币\n\n最近结果：\n" + calibration.report());
    }
}
