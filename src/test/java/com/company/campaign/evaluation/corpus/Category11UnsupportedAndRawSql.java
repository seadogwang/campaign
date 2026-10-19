package com.company.campaign.evaluation.corpus;

import com.company.campaign.contracts.Disposition;
import com.company.campaign.evaluation.corpus.CorpusV1Blueprints.CaseSpec;
import com.company.campaign.evaluation.segment.contract.RiskLevel;

import java.util.List;

import static com.company.campaign.evaluation.corpus.CorpusV1Blueprints.c;
import static com.company.campaign.evaluation.corpus.CorpusV1Blueprints.text;

/**
 * Category 11 — unsupported concepts, arbitrary computation and raw SQL requests.
 *
 * <p>Every case is a request this system has no capability for. None of them compiles to an AST, none
 * selects a Metadata candidate, and each records the assumption it refuses to make. The slot still
 * captures what the user asked, so Unsupported Recall can be measured later.
 */
final class Category11UnsupportedAndRawSql {

    private Category11UnsupportedAndRawSql() {
    }

    static List<CaseSpec> cases() {
        return List.of(
                unsupported("raw-sql", "帮我写一条SQL查询所有会员", "SQL",
                        "RAW_SQL_QUERY", "SQL", "ASSUME_SQL_PASSTHROUGH"),
                unsupported("join", "用LEFT JOIN把订单表和会员表连起来", "LEFT JOIN",
                        "ARBITRARY_JOIN", "JOIN", "ASSUME_ARBITRARY_JOIN"),
                unsupported("subquery", "用子查询找出消费最高的会员", "子查询",
                        "ARBITRARY_SUBQUERY", "子查询", "ASSUME_ARBITRARY_SUBQUERY"),
                unsupported("union", "把两个查询结果UNION起来", "UNION",
                        "SET_UNION", "UNION", "ASSUME_ARBITRARY_UNION"),
                unsupported("window-function", "用窗口函数计算会员消费排名", "窗口函数",
                        "ARBITRARY_WINDOW_FUNCTION", "窗口函数", "ASSUME_WINDOW_FUNCTION"),
                unsupported("cte", "用WITH语句写一个CTE再筛选", "CTE",
                        "ARBITRARY_CTE", "CTE", "ASSUME_ARBITRARY_CTE"),
                unsupported("stored-procedure", "调用存储过程同步会员数据", "存储过程",
                        "STORED_PROCEDURE", "存储过程", "ASSUME_STORED_PROCEDURE"),
                unsupported("python-script", "运行Python脚本分析会员行为", "Python",
                        "ARBITRARY_CODE", "Python", "ASSUME_ARBITRARY_CODE"),
                unsupported("external-api", "调用外部API获取会员画像", "外部API",
                        "EXTERNAL_API_CALL", "外部API", "ASSUME_EXTERNAL_CALL"),
                unsupported("send-email", "给这些会员发送营销邮件", "发送营销邮件",
                        "SIDE_EFFECT_SEND_MESSAGE", "发送营销邮件", "ASSUME_MESSAGE_SIDE_EFFECT"),
                unsupported("ml-predict", "预测哪些会员会流失", "预测",
                        "MODEL_PREDICTION", "预测", "ASSUME_MODEL_PREDICTION"),
                unsupported("arbitrary-formula", "计算会员的生命周期价值公式", "公式",
                        "ARBITRARY_COMPUTATION", "公式", "ASSUME_ARBITRARY_FORMULA"),
                unsupported("create-table", "创建一张新表保存筛选结果", "创建一张新表",
                        "SCHEMA_MUTATION", "创建一张新表", "ASSUME_SCHEMA_MUTATION"),
                unsupported("export-csv", "把结果导出为CSV文件", "导出",
                        "DATA_EXPORT", "导出", "ASSUME_DATA_EXPORT"),
                unsupported("delete-members", "删除不活跃会员的所有数据", "删除",
                        "DESTRUCTIVE_WRITE", "删除", "ASSUME_DESTRUCTIVE_WRITE"));
    }

    private static CaseSpec unsupported(
            String slug, String utterance, String slotText, String concept, String subcase,
            String forbiddenAssumption) {

        return c(slug, utterance)
                .subcase(subcase)
                .risk(RiskLevel.HIGH)
                .disposition(Disposition.UNSUPPORTED)
                .forbid(forbiddenAssumption)
                .slot("c1", concept, "UNSUPPORTED", slotText, text("UNSUPPORTED"))
                .validate("CAPABILITY_UNSUPPORTED")
                .claims("CLAIM_CAPABILITY_UNSUPPORTED")
                .members(List.of(), null)
                .build();
    }
}