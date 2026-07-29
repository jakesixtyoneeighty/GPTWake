package com.desmond.gptwake;

import android.content.res.AssetManager;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Converts a human-typed wake phrase into the phone/pinyin token sequence the KWS model expects,
 * entirely on device. Mirrors what `sherpa-onnx-cli text2token --tokens-type phone+ppinyin` does:
 * Chinese characters become initial + toned final, English words become CMU phones.
 */
public final class WakeWordTokenizer {

    public static final class Result {
        public final boolean ok;
        public final String tokens;      // space separated model tokens
        public final String readable;    // pinyin / phones for display
        public final String keywordLine; // ready for KeywordSpotter.createStream()
        public final String error;

        Result(boolean ok, String tokens, String readable, String keywordLine, String error) {
            this.ok = ok;
            this.tokens = tokens;
            this.readable = readable;
            this.keywordLine = keywordLine;
            this.error = error;
        }
    }

    private final Map<Character, String[]> han = new HashMap<>(32768);
    private final Map<String, String> english = new HashMap<>(160000);
    private final Set<String> valid = new HashSet<>(512);
    private volatile boolean loaded;

    public boolean isLoaded() {
        return loaded;
    }

    public synchronized void load(AssetManager am) throws Exception {
        if (loaded) return;
        long t0 = System.currentTimeMillis();

        try (BufferedReader r = reader(am, "kws/tokens.txt")) {
            String line;
            while ((line = r.readLine()) != null) {
                int sp = line.lastIndexOf(' ');
                if (sp > 0) valid.add(line.substring(0, sp));
            }
        }

        try (BufferedReader r = reader(am, "kws/pinyin_tokens.txt")) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.split("\t");
                if (f.length >= 3 && f[0].length() == 1) {
                    han.put(f[0].charAt(0), new String[]{f[1], f[2]});
                }
            }
        }

        try (BufferedReader r = reader(am, "kws/en.phone")) {
            String line;
            while ((line = r.readLine()) != null) {
                int sp = line.indexOf(' ');
                if (sp > 0) english.put(line.substring(0, sp).toUpperCase(Locale.US),
                        line.substring(sp + 1).trim());
            }
        }

        loaded = true;
        L.i("TOKENIZER_READY han=" + han.size() + " english=" + english.size()
                + " modelTokens=" + valid.size() + " loadMs=" + (System.currentTimeMillis() - t0));
    }

    private static BufferedReader reader(AssetManager am, String path) throws Exception {
        return new BufferedReader(new InputStreamReader(am.open(path), StandardCharsets.UTF_8), 1 << 16);
    }

    private static boolean isHan(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF);
    }

    public Result convert(String phrase) {
        if (!loaded) return new Result(false, "", "", "", "词典尚未加载完成");
        if (phrase == null) return new Result(false, "", "", "", "唤醒词为空");

        String p = phrase.trim().replaceAll("\\s+", " ");
        if (p.isEmpty()) return new Result(false, "", "", "", "唤醒词为空");

        List<String> tokens = new ArrayList<>();
        List<String> readable = new ArrayList<>();
        int syllables = 0;

        int i = 0;
        while (i < p.length()) {
            char c = p.charAt(i);
            if (c == ' ') {
                i++;
                continue;
            }
            if (isHan(c)) {
                String[] e = han.get(c);
                if (e == null) {
                    return new Result(false, "", "", "", "不支持的汉字：" + c);
                }
                for (String t : e[0].split(" ")) tokens.add(t);
                readable.add(e[1]);
                syllables++;
                i++;
                continue;
            }
            if (Character.isLetter(c) || c == '\'') {
                int j = i;
                while (j < p.length()
                        && (Character.isLetter(p.charAt(j)) || p.charAt(j) == '\'')) j++;
                String word = p.substring(i, j).toUpperCase(Locale.US);
                String phones = english.get(word);
                if (phones == null) {
                    return new Result(false, "", "", "", "英文词典中没有：" + word);
                }
                for (String t : phones.split("\\s+")) tokens.add(t);
                readable.add(phones);
                syllables += Math.max(1, word.length() / 3);
                i = j;
                continue;
            }
            return new Result(false, "", "", "", "不支持的字符：" + c + "（只支持中文和英文）");
        }

        if (tokens.isEmpty()) return new Result(false, "", "", "", "无法解析这个唤醒词");

        for (String t : tokens) {
            if (!valid.contains(t)) {
                return new Result(false, "", "", "", "模型不支持的发音单元：" + t);
            }
        }

        String tok = String.join(" ", tokens);
        String display = String.join(" ", readable);
        String line = tok + " @" + p.replace(' ', '_');

        if (syllables < 3) {
            return new Result(true, tok, display, line,
                    "警告：只有约 " + syllables + " 个音节，太短容易误唤醒，建议 4–6 个音节");
        }
        return new Result(true, tok, display, line, null);
    }

    /** Rough syllable estimate, used by the UI to warn about over-short phrases. */
    public static int estimateSyllables(String phrase) {
        int n = 0;
        for (char c : phrase.toCharArray()) if (isHan(c)) n++;
        return n;
    }
}
