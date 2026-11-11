-- UX-03 Token 用量实时反馈:模型上下文窗口与定价字段(评审决策 #4)
-- 说明:context_window 此前不存在,一并补上;定价用于会话成本估算,未配置时不返回 costEstimate
ALTER TABLE agent_model
  ADD COLUMN context_window INT NULL COMMENT '上下文窗口(tokens),未配置时会话页不展示剩余进度' AFTER max_tokens,
  ADD COLUMN price_input DECIMAL(10,6) NULL COMMENT '输入单价(美元/百万token)' AFTER context_window,
  ADD COLUMN price_output DECIMAL(10,6) NULL COMMENT '输出单价(美元/百万token)' AFTER price_input,
  ADD COLUMN price_currency VARCHAR(8) NOT NULL DEFAULT 'USD' COMMENT '定价币种' AFTER price_output;
