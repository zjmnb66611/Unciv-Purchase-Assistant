package cn.zjmnb.uncivassistant;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.List;

public final class PurchasePolicyTest {
    @Test public void recognizesChineseAircraftWithDigits() { assertTrue(PurchasePolicy.isQueueName("歼36")); }
    @Test public void ignoresQueueHeadings() { assertFalse(PurchasePolicy.isQueueName("等待中的项目")); assertFalse(PurchasePolicy.isQueueName("当前项目")); }
    @Test public void ignoresTurnsAndPerpetualProduction() { assertFalse(PurchasePolicy.isQueueName("12 回合")); assertFalse(PurchasePolicy.isQueueName("金钱")); }
    @Test public void normalizesFullWidthCharacters() { assertEquals("歼36", PurchasePolicy.normalized(" 歼３６ ")); }
    @Test public void parsesNativeBuyPrice() { assertEquals(1200, PurchasePolicy.buttonPrice("购买 1,200 ⛃")); }
    @Test public void rejectsNoPrice() { assertEquals(-1, PurchasePolicy.buttonPrice("购买")); }
    @Test public void confirmsMatchingChineseNativeDialog() { assertTrue(PurchasePolicy.mayConfirm("你确定用1,200⛃购买图书馆吗？", "图书馆",1200,true)); }
    @Test public void rejectsPrefixPriceMismatch() { assertFalse(PurchasePolicy.mayConfirm("你确定用1450⛃购买歼36吗？", "歼36",145,true)); }
    @Test public void rejectsDifferentItem() { assertFalse(PurchasePolicy.mayConfirm("你确定用200⛃购买大学吗？", "图书馆",200,true)); }
    @Test public void rejectsFaithPurchase() { assertFalse(PurchasePolicy.mayConfirm("你确定用200信仰购买图书馆吗？", "图书馆",200,true)); }
    @Test public void requiresGoldGlyphEvidence() { assertFalse(PurchasePolicy.mayConfirm("你确定用200购买图书馆吗？", "图书馆",200,false)); }
    @Test public void confirmsEnglishWithExactName() { assertTrue(PurchasePolicy.mayConfirm("Would you like to purchase Tank for 200 Gold?", "Tank",200,true)); }
    @Test public void rejectsEnglishLongerName() { assertFalse(PurchasePolicy.mayConfirm("Would you like to purchase Modern Tank for 200 Gold?", "Tank",200,true)); }
    @Test public void rejectsMalformedNumericOverflow() { assertFalse(PurchasePolicy.mayConfirm("你确定用999999999999999999999999购买图书馆吗？", "图书馆",200,true)); }
    @Test public void permitsZeroCostWithGoldEvidence() { assertTrue(PurchasePolicy.mayConfirm("你确定用0购买图书馆吗？", "图书馆",0,true)); }
    @Test public void enforcesBudgetWithoutOverflow() { assertTrue(PurchasePolicy.budgetAllows(200,300,500)); assertFalse(PurchasePolicy.budgetAllows(200,301,500)); assertFalse(PurchasePolicy.budgetAllows(Long.MAX_VALUE,1,Long.MAX_VALUE)); }
    @Test public void preservesDuplicateQueueItemsAcrossOverlap() { assertEquals(List.of("市场","坦克","坦克","大学"), PurchasePolicy.mergePages(List.of("市场","坦克","坦克"),List.of("坦克","大学"))); }
    @Test public void rejectsDiscontinuousPage() { assertNull(PurchasePolicy.mergePages(List.of("市场"),List.of("大学"))); }
    @Test public void queueChangeRequiresMultiplicityAndOrder() { assertFalse(PurchasePolicy.sameQueue(List.of("坦克","坦克"),List.of("坦克"))); assertFalse(PurchasePolicy.sameQueue(List.of("市场","坦克"),List.of("坦克","市场"))); }
    @Test public void normalizationDoesNotTreatDistinctAircraftAsSame() { assertFalse(PurchasePolicy.sameQueue(List.of("歼36"),List.of("歼35"))); }
    @Test public void neverTreatsAvailableCatalogHeadersAsQueueEntries() { assertTrue(PurchasePolicy.queueFooter("建筑")); assertFalse(PurchasePolicy.isQueueName("单位")); assertFalse(PurchasePolicy.isQueueName("选择建造目标")); }
}
