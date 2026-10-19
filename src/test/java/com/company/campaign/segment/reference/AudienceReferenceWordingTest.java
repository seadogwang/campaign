package com.company.campaign.segment.reference;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T18 section 6: the one deterministic reading of dynamic audience wording.
 */
class AudienceReferenceWordingTest {

    @Test
    void dynamicAudiencePhrasesAreRecognised() {
        for (String text : new String[] {
                "当前人群", "最新人群", "最新的人群", "使用当前客群", "最新版本的人群",
                "the current audience", "the latest segment", "latest version"}) {
            assertTrue(AudienceReferenceWording.isDynamic(text), text);
        }
    }

    @Test
    void staticNamesAreNotDynamic() {
        for (String text : new String[] {
                "高价值客户人群", "VIP会员", "黄金会员人群", "campaign audience 2026", null, ""}) {
            assertFalse(AudienceReferenceWording.isDynamic(text), String.valueOf(text));
        }
    }

    @Test
    void anAsciiQualifierMustBeAWholeWord() {
        // "currency" contains "current" but is not the qualifier, so a name like this stays static.
        assertFalse(AudienceReferenceWording.isDynamic("currency holders audience"));
        assertTrue(AudienceReferenceWording.isDynamic("current audience"));
    }

    @Test
    void aQualifierWithoutAnAudienceHeadIsNotDynamic() {
        // "最新" alone describes something else, so this is not a request about an audience.
        assertFalse(AudienceReferenceWording.isDynamic("最新的消费金额"));
    }
}