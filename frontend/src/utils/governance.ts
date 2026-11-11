/** Prompt 变量契约项 */
export interface PromptVariable {
  name: string;
  type: string;
  required: boolean;
  defaultValue?: string;
  enumValues?: string[];
}

/** 门禁检查值展示格式化 */
export const formatCheckValue = (value: unknown): string => {
  if (value === null || value === undefined) return '—';
  if (typeof value === 'object') return JSON.stringify(value);
  if (typeof value === 'number' && !Number.isInteger(value)) return value.toFixed(2);
  return String(value);
};

/** 解析变量契约 JSON,非法输入返回空数组,不抛异常 */
export const parseVariableContract = (raw: string): PromptVariable[] => {
  if (!raw || !raw.trim()) return [];
  try {
    const parsed = JSON.parse(raw);
    const vars = Array.isArray(parsed?.vars) ? parsed.vars : [];
    return vars
      .filter((item: any) => item && typeof item.name === 'string' && item.name.trim())
      .map((item: any) => ({
        name: String(item.name).trim(),
        type: typeof item.type === 'string' ? item.type : 'string',
        required: item.required === true,
        defaultValue: item.default === undefined ? undefined : String(item.default),
        enumValues: Array.isArray(item.enum) ? item.enum.map(String) : undefined
      }));
  } catch {
    return [];
  }
};

/** SSE 断点游标:只前进、忽略非法或回退的序号 */
export const nextSseCursor = (current: number, eventId: number): number => {
  if (!Number.isFinite(eventId) || eventId <= current) return current;
  return Math.trunc(eventId);
};
