export function project(source, rule) {
  const result = source.replace(new RegExp(rule.pattern, 'g'), '');
  if (/data-card-privacy|<\/?card-private/i.test(result)) throw new Error('隐私标记不完整，请先修正');
  return result;
}
