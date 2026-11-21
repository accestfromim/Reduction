// The inline editor changes text nodes. Keep the remote document, including new figures and attachments.
export function planMerge(base, local, remote) {
  const parse = s => new DOMParser().parseFromString(s,'text/html');
  const [a,b,c] = [base,local,remote].map(parse);
  const nodes = d => [...d.body.children];
  const [before,mine,latest] = [a,b,c].map(nodes);
  const skeleton = el => {
    const copy = el.cloneNode(true);
    const walker = copy.ownerDocument.createTreeWalker(copy,NodeFilter.SHOW_TEXT);
    while (walker.nextNode()) walker.currentNode.data = '';
    return copy.outerHTML;
  };
  const shell = d => { const copy = d.cloneNode(true); copy.body.replaceChildren(); return copy.documentElement.outerHTML; };
  if (shell(a) !== shell(b) || before.length !== mine.length) return null;
  const bareText = d => [...d.body.childNodes].filter(n=>n.nodeType !== 1).map(n=>n.nodeValue);
  if (JSON.stringify(bareText(a)) !== JSON.stringify(bareText(b))) return null;
  const edits = [], conflicts = [];
  for (let i=0;i<before.length;i++) {
    const old = before[i], next = mine[i];
    if (old.isEqualNode(next)) continue;
    if (skeleton(old) !== skeleton(next)) return null;
    const matches = latest.filter(el=>el.isEqualNode(old));
    let target;
    if (matches.length === 1 && before.filter(el=>el.isEqualNode(old)).length === 1) target = matches[0];
    else if (before.length === latest.length && before.every((el,j)=>skeleton(el)===skeleton(latest[j])) &&
      before.every((el,j)=>j===i || el.isEqualNode(latest[j]))) target = latest[i];
    else return null;
    const texts = el => {const w=el.ownerDocument.createTreeWalker(el,NodeFilter.SHOW_TEXT),list=[];while(w.nextNode())list.push(w.currentNode);return list;};
    const [x,y,z]=[old,next,target].map(texts);
    if(x.length!==y.length || x.length!==z.length) return null;
    for(let j=0;j<x.length;j++){
      const original=x[j].data, draft=y[j].data, current=z[j].data;
      if(original===draft || draft===current) continue;
      if(current===original) edits.push([z[j],draft]);
      else conflicts.push({node:z[j],original,draft,current,private:!!z[j].parentElement.closest('card-private,[data-card-privacy]')});
    }
  }
  return {conflicts, finish(choices=[]) {
    if(choices.length!==conflicts.length) throw Error('请逐项确认分歧');
    for(const [node,value] of edits) node.data=value;
    conflicts.forEach((entry,i)=>{entry.node.data=choices[i]});
    return '<!doctype html>\n'+c.documentElement.outerHTML;
  }};
}
