const fs = require('fs'), vm = require('vm'), assert = require('assert'), path = require('path');
const html = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/terminal/index.html'),'utf8');
const code = html.match(/<script>\s*([\s\S]*?)<\/script>/)[1];
const lines = new Map();
function cells(text) { const list=[]; for (const ch of text) { const width=/[\u4e00-\u9fff]/.test(ch)?2:1; list.push({getChars:()=>ch,getWidth:()=>width}); if(width===2)list.push({getChars:()=>'',getWidth:()=>0}); } return {getCell:i=>list[i]}; }
lines.set(7,cells('hello 中文 world')); lines.set(8,cells('second line'));
class Terminal {constructor(o){this.options=o;this.cols=20;this.rows=4;this.buffer={active:{viewportY:7,baseY:10,getLine:i=>lines.get(i)}}} open(){}loadAddon(){}onData(){}select(c,r,n){this.selection=[c,r,n]}hasSelection(){return !!this.selection}scrollToLine(n){this.scrolled=n}}
const context={Terminal,FitAddon:{FitAddon:class{fit(){}}},document:{getElementById:()=>({style:{}}),querySelector:()=>({getBoundingClientRect:()=>({left:0,top:0,right:200,bottom:80,width:200,height:80})})},window:{innerWidth:200,innerHeight:80,addEventListener(){}},AndroidBridge:{write(){}}};
vm.createContext(context); vm.runInContext(code,context);
assert(context.terminalSelectAt(.12,.1,false)); assert.deepEqual([...context.term.selection],[0,7,5]);
assert(context.terminalSelectAt(.36,.1,false)); assert.deepEqual([...context.term.selection],[6,7,4]);
context.terminalSelectAt(.12,.1,false);context.terminalSelectAt(.15,.35,true);assert.deepEqual([...context.term.selection],[0,7,24]);
context.setTerminalFontSize(100);assert.equal(context.term.options.fontSize,26);assert.equal(context.term.scrolled,7);
context.setTerminalFontSize(1);assert.equal(context.term.options.fontSize,9);
console.log('JS checks passed: word selection, Chinese wide cells, history/multiline selection, font limits and scroll position');
