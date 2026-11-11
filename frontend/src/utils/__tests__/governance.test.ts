import { describe, expect, it } from 'vitest';
import { formatCheckValue, nextSseCursor, parseVariableContract } from '@/utils/governance';

describe('formatCheckValue', () => {
  it('空值统一显示占位符', () => {
    expect(formatCheckValue(null)).toBe('—');
    expect(formatCheckValue(undefined)).toBe('—');
  });

  it('对象序列化、小数保留两位、其余转字符串', () => {
    expect(formatCheckValue({ score: 80 })).toBe('{"score":80}');
    expect(formatCheckValue(12.345)).toBe('12.35');
    expect(formatCheckValue(true)).toBe('true');
    expect(formatCheckValue('passed')).toBe('passed');
  });
});

describe('parseVariableContract', () => {
  it('解析合法契约并归一化字段', () => {
    const result = parseVariableContract(JSON.stringify({
      vars: [
        { name: 'tone', type: 'enum', required: true, default: '专业', enum: ['专业', '口语'] },
        { name: 'maxWords', type: 'number', required: false }
      ]
    }));
    expect(result).toHaveLength(2);
    expect(result[0]).toEqual({
      name: 'tone', type: 'enum', required: true, defaultValue: '专业', enumValues: ['专业', '口语']
    });
    expect(result[1].type).toBe('number');
    expect(result[1].required).toBe(false);
  });

  it('非法 JSON 或缺 name 的项安全降级', () => {
    expect(parseVariableContract('')).toEqual([]);
    expect(parseVariableContract('not json')).toEqual([]);
    expect(parseVariableContract('{"vars":[{"type":"string"},{"name":"ok"}]}')).toHaveLength(1);
    expect(parseVariableContract('{"vars":"bad"}')).toEqual([]);
  });
});

describe('nextSseCursor', () => {
  it('按序号单调递增,忽略回退与非法值', () => {
    expect(nextSseCursor(0, 1)).toBe(1);
    expect(nextSseCursor(5, 9)).toBe(9);
    expect(nextSseCursor(9, 3)).toBe(9);
    expect(nextSseCursor(9, Number.NaN)).toBe(9);
    expect(nextSseCursor(2, 7.9)).toBe(7);
  });
});
