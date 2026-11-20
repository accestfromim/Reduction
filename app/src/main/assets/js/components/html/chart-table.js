// Both the semantic editor and the renderer use this interpretation of a chart table.
export function chartData(view, cells) {
  if (!['share', 'bar'].includes(view) || !cells.length || cells.some(row => row.length !== 2)) return null;
  const values = cells.map(row => {
    const text = String(row[1] ?? '').trim();
    if (!/^(?:\d+|\d{1,3}(?:,\d{3})+)(?:\.\d+)?$/.test(text)) return NaN;
    return Number(text.replaceAll(',', ''));
  });
  if (!values.every(Number.isFinite)) return null;
  const sum = values.reduce((a,b) => a+b, 0);
  const max = Math.max(...values);
  return max > 0 && Number.isFinite(sum) ? {values,sum,max} : null;
}

// A chart is a two-column HTML table: label, nonnegative value. The table is the saved data.
export function drawChart(table) {
  if (!table || !['share', 'bar'].includes(table.dataset.chart)) return;
  const rows = Array.from(table.tBodies).flatMap(body => Array.from(body.rows));
  const doc = table.ownerDocument;
  const previous = table.nextElementSibling;
  if (previous?.classList.contains('reduction-chart-output')) previous.remove();
  const data = chartData(table.dataset.chart, rows.map(row => Array.from(row.cells, cell => cell.textContent)));
  if (!data) return;
  const {values, sum, max} = data;
  const chart = doc.createElement('div');
  chart.className = 'reduction-chart-output';
  chart.setAttribute('aria-hidden','true');
  const share = table.dataset.chart === 'share';
  const strip = doc.createElement('div');
  if (share) { strip.style.cssText='display:flex;height:14px;overflow:hidden;border-radius:3px'; chart.append(strip); }
  rows.forEach((row,i) => {
    const color = ['#ddd','#888','#b4b4b4','#666'][i%4];
    const fraction = values[i]/(share ? sum : max);
    const label = doc.createElement('div');
    label.style.cssText='font-size:.83em;margin:.55em 0 .15em;line-height:1.4';
    label.textContent=row.cells[0].textContent+' · '+(share ? (fraction*100).toFixed(1)+'%' : row.cells[1].textContent);
    chart.append(label);
    const bar = doc.createElement('div');
    bar.style.cssText='height:9px;border-radius:2px;background:'+color+';width:'+(100*fraction)+'%';
    if(share) { bar.style.height='100%'; strip.append(bar); label.style.borderLeft='4px solid '+color; label.style.paddingLeft='.5em'; }
    else chart.append(bar);
  });
  chart.style.cssText='margin:.55em 0 1.4em';
  table.after(chart);
}
