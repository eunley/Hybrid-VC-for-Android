package com.svc.voice;

import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/**
 * 官方 SoC → HTP 架构表（assets/htp-soc-table.json）。
 * 只做「查表」，查不到就返回 null，由上层按未知处理。
 *
 * 手写极简 JSON 解析，避免给只有 5 个字段的表引入完整解析器依赖。
 */
final class HtpTable {

    static final class Row {
        final String soc;
        final int arch;
        final int vtcmMiB;
        final int qnnSocModel;
        final String note;
        /** 官方表明确标注该芯片无 HTP（N/A），与"表里查不到"不同 */
        final boolean noHtp;
        Row(String soc, int arch, int vtcmMiB, int qnnSocModel, String note, boolean noHtp) {
            this.soc = soc; this.arch = arch; this.vtcmMiB = vtcmMiB;
            this.qnnSocModel = qnnSocModel; this.note = note; this.noHtp = noHtp;
        }
    }

    private static HtpTable INSTANCE;
    private final Map<String, Row> bySoc = new HashMap<>();
    private final List<Row> all = new ArrayList<>();

    static synchronized HtpTable get() {
        // 若之前在没有 Context 时加载过（表为空），attach 之后重新尝试一次
        if (INSTANCE == null || (INSTANCE.all.isEmpty() && appCtx != null)) INSTANCE = load();
        return INSTANCE;
    }

    static synchronized boolean isEmpty() { return get().all.isEmpty(); }

    Row find(String model) {
        if (model == null) return null;
        String k = SocInfo.norm(model);
        Row r = bySoc.get(k);
        if (r != null) return r;
        // 常见别名：SM8250-AB → SM8250；SD 8 Gen 2 → 无；用前缀再匹配一次
        int dash = k.indexOf('-');
        if (dash > 0) return bySoc.get(k.substring(0, dash));
        return null;
    }

    List<Row> all() { return all; }

    private static android.content.Context appCtx;

    static void attach(android.content.Context c) { appCtx = c == null ? null : c.getApplicationContext(); }

    private static HtpTable load() {
        HtpTable t = new HtpTable();
        InputStream in = open();
        try (InputStream in0 = in) {
            if (in0 == null) return t;
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in0.read(buf)) > 0) bo.write(buf, 0, n);
            String s = new String(bo.toByteArray(), "UTF-8");
            t.parse(s);
        } catch (Throwable ignore) { /* 表读不到 → 全部未知，安全 */ }
        return t;
    }

    /** 优先用 Android AssetManager（APK 内 assets 的可靠方式），退化为 classloader 资源 */
    private static InputStream open() {
        try {
            if (appCtx != null) {
                InputStream a = appCtx.getAssets().open("htp-soc-table.json");
                if (a != null) return a;
            }
        } catch (Throwable ignore) {}
        try { return HtpTable.class.getClassLoader().getResourceAsStream("assets/htp-soc-table.json"); }
        catch (Throwable ignore) { return null; }
    }

    /** 只解析我们自己的表结构：{"rows":[{"soc":..,"arch":..,"vtcmMiB":..,"qnnSocModel":..,"note":..}]} */
    private void parse(String json) {
        int i = json.indexOf("\"rows\"");
        if (i < 0) return;
        int p = json.indexOf('[', i);
        if (p < 0) return;
        while (true) {
            int ob = json.indexOf('{', p);
            if (ob < 0) break;
            int cb = json.indexOf('}', ob);
            if (cb < 0) break;
            String obj = json.substring(ob + 1, cb);
            p = cb + 1;
            String soc = str(obj, "soc");
            if (soc == null) continue;
            Row r = new Row(soc.toUpperCase(Locale.ROOT), num(obj, "arch"),
                    num(obj, "vtcmMiB"), num(obj, "qnnSocModel"), str(obj, "note"),
                    obj.contains("\"noHtp\""));
            bySoc.put(r.soc, r);
            all.add(r);
        }
    }

    private static String str(String obj, String key) {
        int k = obj.indexOf('"' + key + '"');
        if (k < 0) return null;
        int c = obj.indexOf(':', k);
        if (c < 0) return null;
        int q1 = obj.indexOf('"', c + 1);
        if (q1 < 0) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = q1 + 1; i < obj.length(); i++) {
            char ch = obj.charAt(i);
            if (ch == '\\') { i++; if (i < obj.length()) sb.append(obj.charAt(i)); continue; }
            if (ch == '"') break;
            sb.append(ch);
        }
        return sb.toString();
    }

    private static int num(String obj, String key) {
        int k = obj.indexOf('"' + key + '"');
        if (k < 0) return 0;
        int c = obj.indexOf(':', k);
        if (c < 0) return 0;
        int i = c + 1;
        while (i < obj.length() && (obj.charAt(i) == ' ' || obj.charAt(i) == '\n')) i++;
        int st = i;
        while (i < obj.length() && (Character.isDigit(obj.charAt(i)) || obj.charAt(i) == '-')) i++;
        if (i == st) return 0;
        try { return Integer.parseInt(obj.substring(st, i)); } catch (Throwable e) { return 0; }
    }
}
