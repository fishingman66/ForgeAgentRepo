package com.forgeagent.customerservice.domain.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;

/** B4 调查详情(含证据链)。 */
@Data
public class CsInvestigationVo {

    private Long id;
    private Long ticketId;
    private Long conversationId;
    private Long datasourceId;
    private Long agentId;
    private String templateCode;
    private String status;
    private String reportMd;
    private BigDecimal confidence;
    private String errorMessage;
    /** hypotheses_json 解析后的结构化列表,解析失败时为 null */
    private List<Map<String, Object>> hypotheses;
    private Date confirmedAt;
    private Date createTime;
    private List<Evidence> evidences;

    @Data
    public static class Evidence {
        private Integer seq;
        private String evidenceType;
        private String hypothesisTitle;
        private String queryText;
        private String resultDigest;
        private Integer rowCount;
        private Integer elapsedMs;
        private boolean success;
        private String errorMessage;
    }
}
