package cn.zjmnb.uncivassistant;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 与Android无关的识别策略：信息不足时拒绝确认，不推断价格或替换项目。 */
public final class PurchasePolicy {
    private PurchasePolicy() { }
    public static String normalized(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }
    public static boolean isQueueName(String text) {
        String n = normalized(text);
        if (n.isEmpty() || !n.matches(".*\\p{L}.*")) return false;
        if (n.matches(".*(回合| turns?|金币|消耗|剩余|需要|维护).*")) return false;
        if (n.matches("(当前项目|等待中的项目|生产队列|当前建造|队列|队列为空|选择建造项目|闲置|金钱|科研|currentconstruction|constructionqueue|queueempty|pickaconstruction|gold|science|nothing)")) return false;
        // 英文倒计时与资源消耗行通常以数字起头；歼36等名称仍能通过。
        return !n.matches("^[0-9].*");
    }
    public static boolean hasBuyWord(String text) {
        String n = normalized(text);
        return n.contains("购买") || n.startsWith("buy") || n.equals("purchase");
    }
    public static long buttonPrice(String text) {
        Matcher m = Pattern.compile("(?:购买|Buy|Purchase)\\s*([0-9][0-9,， ]*)", Pattern.CASE_INSENSITIVE).matcher(text);
        if (!m.find()) return -1;
        try { return Long.parseLong(m.group(1).replaceAll("[^0-9]", "")); }
        catch (NumberFormatException e) { return -1; }
    }
    public static boolean mayConfirm(String dialog, String item, long price, boolean hasGoldColor) {
        if (!hasGoldColor || price < 0 || normalized(item).isEmpty()) return false;
        String n = normalized(dialog);
        String target = normalized(item);
        if (n.contains("faith") || n.contains("信仰")) return false;
        Matcher chinese = Pattern.compile("你确定用([0-9]+)(?:金币|金钱|gold)?购买(.+?)(?:吗)?$").matcher(n);
        Matcher english = Pattern.compile("wouldyouliketopurchase(.+?)for([0-9]+)(?:gold)?$").matcher(n);
        try {
            if (chinese.find()) return Long.parseLong(chinese.group(1)) == price && chinese.group(2).equals(target);
            if (english.find()) return Long.parseLong(english.group(2)) == price && english.group(1).equals(target);
        } catch (NumberFormatException ignored) { return false; }
        return false;
    }
    public static boolean budgetAllows(long committed, long next, long budget) {
        return committed >= 0 && next >= 0 && budget >= committed && next <= budget - committed;
    }
    /** 重叠页面按队列顺序合并；返回null表示丢失连续性，必须停止重新预检。 */
    public static List<String> mergePages(List<String> before, List<String> page) {
        if (before.isEmpty()) return new ArrayList<>(page);
        if (page.isEmpty()) return null;
        for (int overlap = Math.min(before.size(), page.size()); overlap > 0; overlap--) {
            boolean same = true;
            for (int j = 0; j < overlap; j++) {
                if (!normalized(before.get(before.size() - overlap + j)).equals(normalized(page.get(j)))) same = false;
            }
            if (same) {
                ArrayList<String> result = new ArrayList<>(before);
                result.addAll(page.subList(overlap, page.size()));
                return result;
            }
        }
        return null;
    }
    public static boolean sameQueue(List<String> a, List<String> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!normalized(a.get(i)).equals(normalized(b.get(i)))) return false;
        return true;
    }
}
