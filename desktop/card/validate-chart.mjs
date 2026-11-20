import {chartData} from '../../app/src/main/assets/js/components/html/chart-table.js';
let input = '';
process.stdin.setEncoding('utf8');
for await (const chunk of process.stdin) input += chunk;
const [view, rows] = JSON.parse(input);
process.exitCode = chartData(view, rows) ? 0 : 1;
