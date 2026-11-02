package dev.linjian.peek;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * App 内「查岗与管束」设置页。
 *
 * 巡回周期、强制拉回周期、冷却、安静时段、深夜时段、单 App 锁阈值与时长、
 * 豁免应用、弹窗语气、拉回分级阈值——全部由用户自己设定。
 *
 * 这里读写的是服务端的 /api/tour/schedule，也就是 MCP 的 set_tour_schedule 用的同一份配置，
 * 手机改完立刻生效，MCP 改完这里刷新就能看到，不存在第二套存储。
 */
public class PolicySettingsActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());

    private CheckBox policyEnabled;
    private EditText policyCheckInInput, policyForceBackInput, policyCooldownInput;
    private EditText policyQuietStart, policyQuietEnd, policyNightStart, policyNightEnd;
    private EditText policyLockMinutes, policyLockDuration, policyExemptPackages, policyMissThreshold;
    private EditText policyEmotionTag, policyEmotionMiss;
    private RadioButton policyToneGentle, policyToneAngry;
    private TextView policyStatusText, policyEmotionInfo;
    private Button policyEmotionSaveButton, policyTestSoftButton, policyTestStrongButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_policy_settings);

        policyEnabled = findViewById(R.id.policyEnabled);
        policyCheckInInput = findViewById(R.id.policyCheckInInput);
        policyForceBackInput = findViewById(R.id.policyForceBackInput);
        policyCooldownInput = findViewById(R.id.policyCooldownInput);
        policyQuietStart = findViewById(R.id.policyQuietStart);
        policyQuietEnd = findViewById(R.id.policyQuietEnd);
        policyNightStart = findViewById(R.id.policyNightStart);
        policyNightEnd = findViewById(R.id.policyNightEnd);
        policyLockMinutes = findViewById(R.id.policyLockMinutes);
        policyLockDuration = findViewById(R.id.policyLockDuration);
        policyExemptPackages = findViewById(R.id.policyExemptPackages);
        policyMissThreshold = findViewById(R.id.policyMissThreshold);
        policyEmotionTag = findViewById(R.id.policyEmotionTag);
        policyEmotionMiss = findViewById(R.id.policyEmotionMiss);
        policyToneGentle = findViewById(R.id.policyToneGentle);
        policyToneAngry = findViewById(R.id.policyToneAngry);
        policyStatusText = findViewById(R.id.policyStatusText);
        policyEmotionInfo = findViewById(R.id.policyEmotionInfo);

        Button save = findViewById(R.id.policySaveButton);
        Button checkNow = findViewById(R.id.policyCheckNowButton);
        Button testAlert = findViewById(R.id.policyTestAlertButton);
        policyEmotionSaveButton = findViewById(R.id.policyEmotionSaveButton);
        policyTestSoftButton = findViewById(R.id.policyTestSoftButton);
        policyTestStrongButton = findViewById(R.id.policyTestStrongButton);

        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { savePolicy(); }
        });
        checkNow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { checkNow(); }
        });
        testAlert.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { testAlert(); }
        });
        policyEmotionSaveButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { saveEmotion(); }
        });
        policyTestSoftButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { testPullBack(false); }
        });
        policyTestStrongButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { testPullBack(true); }
        });

        loadPolicy();
        loadEmotion();
    }

    private boolean ready() {
        String base = AppPrefs.server(this), token = AppPrefs.token(this);
        if (base == null || base.trim().isEmpty() || token == null || token.trim().isEmpty()) {
            status("还没连上服务器。请先回首页填好服务器地址和 Token，再回来保存策略。");
            return false;
        }
        return true;
    }

    private void loadPolicy() {
        if (!ready()) return;
        status("正在读取当前策略…");
        final String base = AppPrefs.server(this), token = AppPrefs.token(this);
        new Thread(new Runnable() {
            @Override public void run() {
                final String resp = request(base, token, "GET", "/api/tour/state", null);
                main.post(new Runnable() {
                    @Override public void run() {
                        try {
                            JSONObject root = new JSONObject(resp);
                            JSONObject s = root.optJSONObject("schedule");
                            if (s == null) { status("读取失败：" + resp); return; }
                            applySchedule(s);
                            status("已读取当前策略。改完记得点「保存并生效」。");
                        } catch (Exception e) {
                            status("读取失败：" + e.getMessage());
                        }
                    }
                });
            }
        }, "policy-load").start();
    }

    private void applySchedule(JSONObject s) {
        policyEnabled.setChecked(s.optBoolean("enabled", false));
        policyCheckInInput.setText(String.valueOf(s.optInt("check_in_interval_minutes", 15)));
        policyForceBackInput.setText(String.valueOf(s.optInt("force_back_interval_minutes", 30)));
        policyCooldownInput.setText(String.valueOf(s.optInt("cooldown_minutes", 5)));
        JSONObject q = s.optJSONObject("quiet_hours");
        policyQuietStart.setText(q == null ? "" : q.optString("start", ""));
        policyQuietEnd.setText(q == null ? "" : q.optString("end", ""));
        JSONObject n = s.optJSONObject("night_hours");
        policyNightStart.setText(n == null ? "23:00" : n.optString("start", "23:00"));
        policyNightEnd.setText(n == null ? "06:00" : n.optString("end", "06:00"));
        policyLockMinutes.setText(String.valueOf(s.optInt("single_app_lock_minutes", 0)));
        policyLockDuration.setText(String.valueOf(s.optInt("single_app_lock_duration_minutes", 5)));
        JSONArray arr = s.optJSONArray("exempt_packages");
        List<String> pkgs = new ArrayList<String>();
        if (arr != null) for (int i = 0; i < arr.length(); i++) pkgs.add(arr.optString(i, ""));
        policyExemptPackages.setText(TextUtils.join(", ", pkgs));
        boolean angry = "angry".equals(s.optString("alert_tone", "gentle"));
        policyToneAngry.setChecked(angry);
        policyToneGentle.setChecked(!angry);
        policyMissThreshold.setText(String.valueOf(s.optInt("force_back_miss_threshold", 70)));
    }

    private void savePolicy() {
        if (!ready()) return;
        final String base = AppPrefs.server(this), token = AppPrefs.token(this);
        JSONObject body = new JSONObject();
        try {
            body.put("enabled", policyEnabled.isChecked());
            body.put("check_in_interval_minutes", num(policyCheckInInput, 15, 1, 1440));
            body.put("force_back_interval_minutes", num(policyForceBackInput, 30, 0, 1440));
            body.put("cooldown_minutes", num(policyCooldownInput, 5, 0, 240));
            body.put("single_app_lock_minutes", num(policyLockMinutes, 0, 0, 600));
            body.put("single_app_lock_duration_minutes", num(policyLockDuration, 5, 1, 120));
            body.put("force_back_miss_threshold", num(policyMissThreshold, 70, 1, 100));
            body.put("alert_tone", policyToneAngry.isChecked() ? "angry" : "gentle");

            String qs = text(policyQuietStart), qe = text(policyQuietEnd);
            if ("none".equalsIgnoreCase(qs) || "none".equalsIgnoreCase(qe)) {
                body.put("quiet_hours", JSONObject.NULL);
            } else if (!qs.isEmpty() || !qe.isEmpty()) {
                JSONObject q = new JSONObject();
                q.put("start", qs.isEmpty() ? "23:00" : qs);
                q.put("end", qe.isEmpty() ? "08:00" : qe);
                body.put("quiet_hours", q);
            } else {
                body.put("quiet_hours", JSONObject.NULL);
            }

            JSONObject night = new JSONObject();
            String ns = text(policyNightStart), ne = text(policyNightEnd);
            night.put("start", ns.isEmpty() ? "23:00" : ns);
            night.put("end", ne.isEmpty() ? "06:00" : ne);
            body.put("night_hours", night);

            JSONArray arr = new JSONArray();
            String raw = text(policyExemptPackages);
            if (!raw.isEmpty()) {
                String[] parts = raw.split("[,，;；\\s]+");
                for (String p : parts) if (!p.trim().isEmpty()) arr.put(p.trim());
            }
            body.put("exempt_packages", arr);
        } catch (Exception e) {
            status("组装参数失败：" + e.getMessage());
            return;
        }

        status("保存中…");
        new Thread(new Runnable() {
            @Override public void run() {
                final String resp = request(base, token, "POST", "/api/tour/schedule", body.toString());
                main.post(new Runnable() {
                    @Override public void run() {
                        if (resp.startsWith("ERROR") || resp.startsWith("HTTP 4") || resp.startsWith("HTTP 5")) {
                            status("保存失败：" + resp);
                            Toast.makeText(PolicySettingsActivity.this, "保存失败", Toast.LENGTH_SHORT).show();
                        } else {
                            status("已保存并生效。");
                            Toast.makeText(PolicySettingsActivity.this, "策略已保存", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        }, "policy-save").start();
    }

    private void checkNow() {
        if (!ready()) return;
        final String base = AppPrefs.server(this), token = AppPrefs.token(this);
        status("正在查岗…");
        new Thread(new Runnable() {
            @Override public void run() {
                final String resp = request(base, token, "POST", "/api/tour/check_now", "{}");
                main.post(new Runnable() {
                    @Override public void run() {
                        status(resp.startsWith("ERROR") || resp.startsWith("HTTP 4") ? ("查岗失败：" + resp) : "已查岗一次，结果见下方返回。");
                    }
                });
            }
        }, "policy-check").start();
    }

    private void testAlert() {
        if (!ready()) return;
        final String base = AppPrefs.server(this), token = AppPrefs.token(this);
        try {
            JSONObject payload = new JSONObject();
            payload.put("title", "掌心窗");
            payload.put("message", "这样弹在最中间，看得见吗？");
            payload.put("tone", policyToneAngry.isChecked() ? "angry" : "gentle");
            payload.put("button_text", "知道啦");
            JSONObject body = new JSONObject();
            body.put("device_id", AppPrefs.device(this));
            body.put("action", "show_center_alert");
            body.put("payload", payload);
            status("已下发居中弹窗命令，手机下次轮询时会弹出…");
            new Thread(new Runnable() {
                @Override public void run() {
                    final String resp = request(base, token, "POST", "/api/command", body.toString());
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (resp.startsWith("ERROR") || resp.startsWith("HTTP 4")) status("下发失败：" + resp);
                        }
                    });
                }
            }, "policy-test-alert").start();
        } catch (Exception e) {
            status("下发失败：" + e.getMessage());
        }
    }

    /** 本地直接触发分级拉回弹窗，方便验收两种级别。 */
    private void testPullBack(boolean strong) {
        try {
            JSONObject cfg = new JSONObject();
            int miss = strong ? Math.max(num(policyMissThreshold, 70, 1, 100), 80) : 35;
            cfg.put("level", strong ? "strong" : "soft");
            cfg.put("strong", strong);
            cfg.put("allow_decline", !strong);
            cfg.put("emotion_tag", strong ? "生气" : CompanionEmotionState.emotionTag(this));
            cfg.put("miss_percent", miss);
            cfg.put("intensity", strong ? 85 : 45);
            cfg.put("threshold", num(policyMissThreshold, 70, 1, 100));
            cfg.put("title", strong ? AppPrefs.companionName(this) + "要你回来" : AppPrefs.companionName(this) + "在等你");
            cfg.put("message", CompanionEmotionState.message(this));
            cfg.put("tone", strong ? "angry" : (policyToneAngry.isChecked() ? "angry" : "gentle"));
            PullBackActivity.show(this, cfg);
            status(strong ? "已触发强级别弹窗：只有一个按钮，点击后强制回到聊天界面。" : "已触发软级别弹窗：可以选「再等一会儿」，但必须写理由。");
        } catch (Exception e) {
            status("触发失败：" + e.getMessage());
        }
    }

    /** 读取陪伴对象情绪：本机快照 + 服务端镜像，取更新更晚的那份。 */
    private void loadEmotion() {
        JSONObject local = CompanionEmotionState.snapshot(this);
        showEmotion(local);
        if (!ready()) return;
        final String base = AppPrefs.server(this), token = AppPrefs.token(this);
        new Thread(new Runnable() {
            @Override public void run() {
                final String resp = request(base, token, "GET", "/api/companion/emotion", null);
                main.post(new Runnable() {
                    @Override public void run() {
                        try {
                            JSONObject remote = new JSONObject(resp);
                            JSONObject merged = mergeEmotion(local, remote);
                            showEmotion(merged);
                        } catch (Exception ignored) { }
                    }
                });
            }
        }, "policy-emotion-load").start();
    }

    private JSONObject mergeEmotion(JSONObject local, JSONObject remote) {
        try {
            JSONObject snap = remote.optJSONObject("emotion");
            if (snap == null) snap = remote;
            long localMs = local.optLong("updated_at_ms", 0);
            long remoteMs = snap.optLong("updated_at_ms", 0);
            if (remoteMs > localMs) {
                JSONObject merged = new JSONObject();
                merged.put("emotion_tag", snap.optString("emotion_tag", CompanionEmotionState.emotionTag(this)));
                merged.put("miss_percent", snap.optInt("miss_percent", 0));
                merged.put("intensity", snap.optInt("intensity", 0));
                merged.put("note", snap.optString("note", ""));
                merged.put("updated_at", snap.optString("updated_at", ""));
                merged.put("updated_at_ms", remoteMs);
                return merged;
            }
        } catch (Exception ignored) { }
        return local;
    }

    private void showEmotion(JSONObject emotion) {
        if (policyEmotionInfo == null || emotion == null) return;
        String tag = emotion.optString("emotion_tag", CompanionEmotionState.TAG_CALM);
        int miss = emotion.optInt("miss_percent", 0);
        int intensity = emotion.optInt("intensity", 0);
        String note = emotion.optString("note", "");
        String updated = emotion.optString("updated_at", "");
        StringBuilder sb = new StringBuilder();
        sb.append("情绪：").append(tag).append("　想她：").append(miss).append("%　强度：").append(intensity).append("\n");
        if (note.length() > 0) sb.append("备注：").append(note).append("\n");
        if (updated.length() > 0) sb.append("更新于：").append(updated);
        policyEmotionInfo.setText(sb.toString().trim());
    }

    /** 写一次情绪：先写本机（立即生效），再同步给服务端，MCP 就能读到。 */
    private void saveEmotion() {
        String tag = text(policyEmotionTag);
        if (tag.isEmpty()) tag = CompanionEmotionState.emotionTag(this);
        int miss = num(policyEmotionMiss, CompanionEmotionState.effectiveMissPercent(this), 0, 100);
        JSONObject snap = CompanionEmotionState.write(this, tag, null, Integer.valueOf(miss), "");
        showEmotion(snap);
        Toast.makeText(this, "情绪已更新", Toast.LENGTH_SHORT).show();
        if (!ready()) return;
        final String base = AppPrefs.server(this), token = AppPrefs.token(this);
        final JSONObject body = new JSONObject();
        try {
            body.put("emotion_tag", tag);
            body.put("miss_percent", miss);
            body.put("intensity", snap.optInt("intensity", 0));
            body.put("note", "");
        } catch (Exception e) { return; }
        new Thread(new Runnable() {
            @Override public void run() {
                final String resp = request(base, token, "POST", "/api/companion/emotion", body.toString());
                main.post(new Runnable() {
                    @Override public void run() {
                        status(resp.startsWith("ERROR") || resp.startsWith("HTTP 4")
                                ? ("情绪已写入本机，但同步服务端失败：" + resp)
                                : "情绪已写入本机并同步给服务端。");
                    }
                });
            }
        }, "policy-emotion-save").start();
    }

    private int num(EditText box, int fallback, int min, int max) {
        try {
            String t = text(box);
            if (t.isEmpty()) return fallback;
            int v = Integer.parseInt(t);
            if (v < min) return min;
            if (v > max) return max;
            return v;
        } catch (Exception e) {
            return fallback;
        }
    }

    private String text(EditText box) {
        try {
            String t = box.getText().toString().trim();
            return t == null ? "" : t;
        } catch (Exception e) {
            return "";
        }
    }

    private void status(String s) {
        if (policyStatusText != null) policyStatusText.setText(s == null ? "" : s);
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String request(String base, String token, String method, String path, String body) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(base.replaceAll("/+$", "") + path).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(7000);
            c.setReadTimeout(7000);
            c.setRequestProperty("X-Auth-Token", token);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            if (body != null) {
                c.setDoOutput(true);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = readAll(in);
            return code >= 400 ? ("HTTP " + code + " " + text) : text;
        } catch (Exception e) {
            return "ERROR " + e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
